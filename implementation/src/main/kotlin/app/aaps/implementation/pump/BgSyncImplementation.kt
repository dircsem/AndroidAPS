package app.aaps.implementation.pump

import android.os.Bundle
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.database.entities.GlucoseValue
import app.aaps.core.interfaces.pump.BgSync
import app.aaps.core.interfaces.sync.DataSyncSelector
import app.aaps.core.interfaces.sync.XDripBroadcast
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.database.impl.AppRepository
import app.aaps.database.impl.transactions.CgmSourceTransaction
import dagger.android.HasAndroidInjector

import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

class BgSyncImplementation @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val repository: AppRepository
) : BgSync {
    @Inject lateinit var dataWorker: DataWorkerStorage
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var xDripBroadcast: XDripBroadcast
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var injector: HasAndroidInjector
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var profileUtil: ProfileUtil
    // @Inject lateinit var medLinkPlugin: MedLinkPlugin.MedLinkWorker

    override fun syncBgWithTempId(
        bundle: Bundle
    ) {
        val jsonObject = JSONObject()
        val keys = bundle.keySet()
        for (key in keys) {
            try {
                jsonObject.put(key, bundle[key])
            } catch (e: JSONException) {
                //Handle exception here
            }
        }
        // dataWorker.enqueue(
        //     OneTimeWorkRequest.Builder(MedLinkPlugin.MedLinkWorker::class.java)
        //         .setInputData(dataWorker.storeInputData(bundle, null))
        //         .build()
        // )
                // medLinkPlugin.storeBG(jsonObject)
        // dataWorker.storeInputData()
        // medLinkPlugin.MedLinkWorker.storeBG(jsonObject)
        val bd = dataWorker.pickupBundle(bundle.getLong(DataWorkerStorage.STORE_KEY, -1))

            // ?: return ListenableWorker.Result.failure(workDataOf("Error" to "missing input data"))
        try {
            bd.let { aapsLogger.info(LTag.PUMP, it.toString())}
            aapsLogger.info(LTag.PUMP, bundle.toString())
            handleGlucoseAndCalibrations(bundle)
            // handleTreatments(bundle)
        } catch (e: Exception) {
            aapsLogger.error("Error while processing intent from Dexcom App", e)
            ListenableWorker.Result.failure(workDataOf("Error" to e.toString()))
        }
    }

    private fun handleGlucoseAndCalibrations(bundle: Bundle): ListenableWorker.Result {
        var ret = ListenableWorker.Result.success()
        aapsLogger.info(LTag.GLUCOSE,"inserting bg")
        val sourceSensor = when (bundle.getString("sensorType") ?: "") {
            "Enlite" -> SourceSensor.MM_ENLITE
            else     -> SourceSensor.UNKNOWN
        }
        val calibrations = mutableListOf<PersistenceLayer.Calibration>()
        bundle.getBundle("meters")?.let { meters ->
            for (i in 0 until meters.size()) {
                meters.getBundle(i.toString())?.let {
                    val timestamp = it.getLong("timestamp")
                    val now = dateUtil.now()
                    val value = it.getDouble("meterValue")
                    if (timestamp > now - T.months(1).msecs() && timestamp < now) {
                        calibrations.add(
                            PersistenceLayer.Calibration(
                                timestamp = it.getLong("timestamp"),
                                value = value,
                                glucoseUnit = profileUtil.unitsDetect(value)
                            )
                        )
                    }
                }
            }
        }
        aapsLogger.info(LTag.GLUCOSE,"meters")

        val glucoseValuesBundle = bundle.getBundle("glucoseValues")
            ?: return ListenableWorker.Result.failure(workDataOf("Error" to "missing glucoseValues"))
        val glucoseValues = mutableListOf<GV>()
        for (i in 0 until glucoseValuesBundle.size()) {
            val glucoseValueBundle = glucoseValuesBundle.getBundle(i.toString())!!
            val timestamp = glucoseValueBundle.getLong("timestamp")
            val isig = glucoseValueBundle.getDouble("isig")
            val deltaSinceLastBg = glucoseValueBundle.getDouble("delta_since_last_bg")
            val sensorUptime = glucoseValueBundle.getInt("sensor_uptime")
            val calibrationFactor = glucoseValueBundle.getDouble("calibration_factor")

            // G5 calibration bug workaround (calibration is sent as glucoseValue too)
            // var valid = true
            // if (sourceSensor == GlucoseValue.SourceSensor.DEXCOM_G5_NATIVE)
            //     calibrations.forEach { calibration -> if (calibration.timestamp == timestamp) valid = false }
            // if (valid) {
            // val glucoseValue = AutosensDataStoreObject(
            //     timestamp = timestamp,
            //     value = glucoseValueBundle.getDouble("value"),
            //     noise = null,
            //     raw = null,
            //     trendArrow = TrendArrow.NONE,
            //     sourceSensor = sourceSensor,
            //     isig = isig
            // )
            // xDripBroadcast.send(glucoseValue)
            glucoseValues += GV(

                timestamp = timestamp,
                value = glucoseValueBundle.getDouble("value"),
                noise = null,
                raw = 0.0,
                isig = isig,
                delta = deltaSinceLastBg,
                sensorUptime = sensorUptime,
                calibrationFactor = calibrationFactor,
                trendArrow = TrendArrow.NONE,
                sourceSensor = sourceSensor
            )
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

            aapsLogger.info(LTag.GLUCOSE, format.format(timestamp))

            // }
        }
        val sensorStartTime = if (bundle.containsKey("sensor_uptime")) {
            System.currentTimeMillis() - bundle.getLong("sensor_uptime", 0) * 60 * 1000
        } else {
            null
        }


        persistenceLayer.insertCgmSourceData(Sources.Enlite, glucoseValues, calibrations, sensorStartTime)

            .blockingGet()
            .also { result ->
                var startId=0L
                var lastDbId=0L
                val inserted = result.inserted.map {
                    if((startId == 0L) || (startId > it.id)){
                        startId=it.id
                    }
                    if(lastDbId< it.id){
                        lastDbId=it.id
                    }
                    aapsLogger.info(LTag.DATABASE, "Inserted bg $it")
                    DataSyncSelector.PairGlucoseValue(it, it.id)
                    xDripBroadcast.sendToXdrip("bg", DataSyncSelector.PairGlucoseValue(it, it.id, true), "$startId/$lastDbId")

                }
                // if(inserted.isNotEmpty()){

                // xDripBroadcast.sendToXdrip("entries", inserted, progress = "$startId/$lastDbId")
                // }
                startId = 0L
                lastDbId=0L
                val updated = result.updated.map {
                    if((startId == 0L) || (startId > it.id)){
                        startId=it.id
                    }
                    if(lastDbId< it.id){
                        lastDbId=it.id
                    }
                    aapsLogger.info(LTag.DATABASE, "Updated bg $it")
                    xDripBroadcast.sendToXdrip("bg", DataSyncSelector.PairGlucoseValue(it, it.id, true), "$startId/$lastDbId")
                    DataSyncSelector.PairGlucoseValue(it, it.id)
                }
                // if(updated.isNotEmpty()){
                //     xDripBroadcast.sendToXdrip("entries", updated, progress = "$startId/$lastDbId")
                // }
                result.sensorInsertionsInserted.forEach {
                    uel.log(
                        Action.CAREPORTAL,
                        Sources.Enlite,
                        ValueWithUnit.Timestamp(it.timestamp).toString(),
                        ValueWithUnit.TEType(it.type)
                    )
                    aapsLogger.debug(LTag.DATABASE, "Inserted sensor insertion $it")
                }
                result.calibrationsInserted.forEach { calibration ->
                    calibration.glucose?.let { glucoseValue ->
                        uel.log(
                            Action.CALIBRATION,
                            Sources.Enlite,
                            ValueWithUnit.TEType(calibration.type).toString(),
                            calibration.timestamp,
                            listOf(ValueWithUnit.fromGlucoseUnit(glucoseValue, calibration.glucoseUnit))
                        )
                    }
                    aapsLogger.debug(LTag.DATABASE, "Inserted calibration $calibration")
                }
            }
        return ret
    }

        override fun syncBgWithTempId(
            bgValues: List<BgSync.BgHistory.BgValue>,
            calibrations: List<BgSync.BgHistory.Calibration>
    ): Boolean {

        // if (!confirmActivePump(timestamp, pumpType, pumpSerial)) return false
        val glucoseValue = bgValues.map { bgValue ->
            GlucoseValue(
                timestamp = bgValue.timestamp,
                raw = bgValue.raw,
                noise = bgValue.noise,
                value = bgValue.value,
                trendArrow = GlucoseValue.TrendArrow.NONE,
                sourceSensor = bgValue.sourceSensor.toDbType(),
                isig = bgValue.isig
            )
        }
        val calibrations = calibrations.map{
            calibration ->
            CgmSourceTransaction.Calibration(timestamp = calibration.timestamp,
                                             value = calibration.value, glucoseUnit = calibration.glucoseUnit.toDbType())
        }
        repository.runTransactionForResult(CgmSourceTransaction(glucoseValue, calibrations = calibrations, sensorInsertionTime = null))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving BG", it) }
            .blockingGet()
            .also { result ->
                result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated BG $it") }
                return result.updated.size > 0
            }

    }

    override fun addBgTempId(
        timestamp: Long,
        raw: Double?,
        value: Double,
        noise: Double?,
        arrow: BgSync.BgArrow,
        sourceSensor: GlucoseValue.SourceSensor,
        isig: Double?,
        calibrationFactor: Double?,
        sensorUptime: Int?
    ): Boolean {
        val glucoseValue = listOf(
            GlucoseValue(
                timestamp = timestamp,
                raw = raw,
                noise = noise,
                value = value,
                trendArrow = GlucoseValue.TrendArrow.NONE,
                sourceSensor = sourceSensor,
                isig = isig
            )
        )
        val calibrations = listOf<CgmSourceTransaction.Calibration>()
        repository.runTransactionForResult(CgmSourceTransaction(glucoseValue, calibrations = calibrations, sensorInsertionTime = null))
            .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving Bg", it) }
            .blockingGet()
            .also { result ->
                result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted Bg $it") }
                return result.inserted.size > 0
            }
    }


}