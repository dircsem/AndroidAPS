package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.workflow.LoggingWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONException

@HiltWorker
class OttaiWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    aapsLogger: AAPSLogger,
    fabricPrivacy: FabricPrivacy,
    private val ottaiPlugin: OttaiPlugin,
    private val persistenceLayer: PersistenceLayer
) : LoggingWorker(context, params, Dispatchers.IO, aapsLogger, fabricPrivacy) {

    @SuppressLint("CheckResult")
    override suspend fun doWorkAndLog(): Result {
        var ret = Result.success()
        if (!ottaiPlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
        val collection = inputData.getString("collection") ?: return Result.failure(workDataOf("Error" to "missing collection"))
        if (collection == "entries") {
            val data = inputData.getString("data")
            aapsLogger.debug(LTag.BGSOURCE, "Received Ottai Data $data")
            if (!data.isNullOrEmpty()) {
                try {
                    val glucoseValues = mutableListOf<GV>()
                    val jsonArray = JSONArray(data)
                    for (i in 0 until jsonArray.length()) {
                        val jsonObject = jsonArray.getJSONObject(i)
                        when (val type = jsonObject.getString("type")) {
                            "sgv" ->
                                glucoseValues += GV(
                                    timestamp = jsonObject.getLong("date"),
                                    value = jsonObject.getDouble("sgv"),
                                    raw = jsonObject.getDouble("sgv"),
                                    noise = null,
                                    trendArrow = TrendArrow.fromString(jsonObject.getString("direction")),
                                    sourceSensor = SourceSensor.OTTAI
                                )

                            else -> aapsLogger.debug(LTag.BGSOURCE, "Unknown entries type: $type")
                        }
                    }
                    try {
                        persistenceLayer.insertCgmSourceData(Sources.Ottai, glucoseValues, emptyList(), null)
                    } catch (e: Exception) {
                        ret = Result.failure(workDataOf("Error" to e.toString()))
                    }
                } catch (e: JSONException) {
                    aapsLogger.error("Exception: ", e)
                    ret = Result.failure(workDataOf("Error" to e.toString()))
                }
            }
        }
        return ret
    }
}

