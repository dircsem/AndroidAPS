package app.aaps.plugins.source

import android.content.Context
import android.os.Bundle
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginDescription
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.sync.DataSyncSelector
import app.aaps.core.interfaces.sync.XDripBroadcast
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.utils.receivers.DataWorkerStorage
import dagger.android.HasAndroidInjector

import javax.inject.Inject
import javax.inject.Singleton

import java.text.SimpleDateFormat
import java.util.*

@Singleton
class MedLinkPlugin @Inject constructor(
    injector: HasAndroidInjector,
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val sp: SP,
    private val medLinkMediator: MedLinkMediator,
    config: Config,
    private val persistenceLayer: PersistenceLayer,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_minilink)
        .pluginName(R.string.medlink_app_patched)
        .shortName(R.string.medlink_short)
        .preferencesId(R.xml.pref_bgsourcemedlink)
        .description(R.string.description_source_enlite),
    aapsLogger, rh
), BgSource {

    init {
        if (!config.NSCLIENT) {
            pluginDescription.setDefault()
        }
    }

    override fun advancedFilteringSupported(): Boolean {
        return true
    }


    //
    // override fun onStart() {
    //     super.onStart()
    //    requestPermissionIfNeeded()
    // }

    // cannot be inner class because of needed injection
    class MedLinkWorker(
        context: Context,
        params: WorkerParameters,
    ) : Worker(context, params) {

        @Inject lateinit var persistenceLayer: PersistenceLayer
        @Inject lateinit var aapsLogger: AAPSLogger
        @Inject lateinit var injector: HasAndroidInjector
        @Inject lateinit var medLinkPlugin: MedLinkPlugin
        @Inject lateinit var sp: SP
        @Inject lateinit var dateUtil: DateUtil
        @Inject lateinit var dataWorker: DataWorkerStorage
        @Inject lateinit var xDripBroadcast: XDripBroadcast
        @Inject lateinit var uel: UserEntryLogger
        @Inject lateinit var trendCalculator: TrendCalculator
        @Inject lateinit var profileUtil: ProfileUtil
        init {
            (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
        }

        override fun doWork(): Result {
            lateinit var ret: Result

            if (!medLinkPlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            val bundle = dataWorker.pickupBundle(inputData.getLong(DataWorkerStorage.STORE_KEY, -1))
                ?: return Result.failure(workDataOf("Error" to "missing input data"))
            try {
                handleGlucoseAndCalibrations(bundle)
                // handleTreatments(bundle)
            } catch (e: Exception) {
                aapsLogger.error("Error while processing intent from Dexcom App", e)
                Result.failure(workDataOf("Error" to e.toString()))
            }.also { ret = it }
            return ret
        }

        private fun handleGlucoseAndCalibrations(bundle: Bundle): Result {
            var ret = Result.success()
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
            val glucoseValuesBundle = bundle.getBundle("glucoseValues")
                ?: return Result.failure(workDataOf("Error" to "missing glucoseValues"))
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
                    raw = null,
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
                        aapsLogger.debug(LTag.DATABASE, "Inserted bg $it")
                        DataSyncSelector.PairGlucoseValue(it, it.id)
                        xDripBroadcast.sendToXdrip("bg", DataSyncSelector.PairGlucoseValue(it,it.id, true),"$startId/$lastDbId")

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
                        aapsLogger.debug(LTag.DATABASE, "Updated bg $it")
                        xDripBroadcast.sendToXdrip("bg", DataSyncSelector.PairGlucoseValue(it,it.id, true),"$startId/$lastDbId")
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

    }

    companion object {

        private val PACKAGE_NAMES = arrayOf(
            "com.dexcom.cgm.region1.mgdl", "com.dexcom.cgm.region1.mmol",
            "com.dexcom.cgm.region2.mgdl", "com.dexcom.cgm.region2.mmol",
            "com.dexcom.g6.region1.mmol", "com.dexcom.g6.region2.mgdl",
            "com.dexcom.g6.region3.mgdl", "com.dexcom.g6.region3.mmol", "com.dexcom.g6"
        )
        const val PERMISSION = "com.dexcom.cgm.EXTERNAL_PERMISSION"
    }

    class MedLinkMediator @Inject constructor(val context: Context) {

    }

    // override fun handleNewData(intent: Intent) {
    //     if (!isEnabled(PluginType.BGSOURCE)) return
    //     try {
    //         val sensorType = intent.getStringExtra("sensorType") ?: ""
    //         val glucoseValues = intent.getBundleExtra("glucoseValues")
    //         val isigValues = intent.getBundleExtra("isigValues")
    //         for (i in 0 until glucoseValues.size()) {
    //             glucoseValues.getBundle(i.toString())?.let { glucoseValue ->
    //                 val bgReading = BgReading()
    //                 bgReading.value = glucoseValue.getDouble("value")
    //                 bgReading.direction = glucoseValue.getString("direction")
    //                 bgReading.date = glucoseValue.getLong("date")
    //                 bgReading.raw = 0.0
    //                 val dbHelper = MainApp.getDbHelper()
    //                 // aapsLogger.info(LTag.DATABASE, "bgneedupdate? "+dbHelper.thisBGNeedUpdate(bgReading))
    //                 if (dbHelper.thisBGNeedUpdate(bgReading)) {
    //                     if (dbHelper.createIfNotExists(bgReading, "MedLink$sensorType")) {
    //                         if (sp.getBoolean(R.string.key_medlink_nsupload, false) &&
    //                             (isigValues == null || isigValues.size() == 0)) {
    //                             nsUpload.uploadBg(bgReading, "AndroidAPS-MedLink$sensorType")
    //                         }
    //                         if (sp.getBoolean(R.string.key_medlink_xdripupload, false)) {
    //                             nsUpload.sendToXdrip(bgReading)
    //                         }
    //                     }
    //                 }
    //             }
    //         }
    //         if (isigValues != null) {
    //             for (i in 0 until isigValues.size()) {
    //                 isigValues.getBundle(i.toString())?.let { isigValue ->
    //                     val dbHelper = MainApp.getDbHelper()
    //
    //                     val bgReading = BgReading()
    //                     bgReading.value = isigValue.getDouble("value")
    //                     bgReading.direction = isigValue.getString("direction")
    //                     bgReading.date = isigValue.getLong("date")
    //                     bgReading.raw = 0.0
    //
    //                     val sensReading = SensorDataReading()
    //                     sensReading.bgValue = isigValue.getDouble("value")
    //                     sensReading.direction = isigValue.getString("direction")
    //                     sensReading.date = isigValue.getLong("date")
    //                     sensReading.isig = isigValue.getDouble("isig")
    //                     sensReading.deltaSinceLastBG = isigValue.getDouble("delta")
    //                     sensReading.sensorUptime = dbHelper.sensorAge
    //                     sensReading.bgReading = bgReading
    //                     if(isigValue.getDouble("calibrationFactor") == 0.0){
    //                         sensReading.calibrationFactor = dbHelper.getCalibrationFactor(sensReading.date)?.calibrationFactor ?: 0.0
    //                     }else {
    //                         sensReading.calibrationFactor = isigValue.getDouble("calibrationFactor")
    //                     }
    //                     // aapsLogger.info(LTag.DATABASE, "bgneedupdate? "+dbHelper.thisBGNeedUpdate(bgReading))
    //                     if (dbHelper.thisSensNeedUpdate(sensReading)) {
    //                         if (dbHelper.createIfNotExists(sensReading, "MedLink$sensorType")) {
    //                             if (sp.getBoolean(R.string.key_medlink_nsupload, false)) {
    //                                 //
    //                                 nsUpload.uploadEnliteData(sensReading, "AndroidAPS-MedLink$sensorType")
    //                             }
    //                             if (sp.getBoolean(R.string.key_medlink_xdripupload, false)) {
    //                                 nsUpload.sendToXdrip(sensReading.bgReading)
    //                             }
    //                         }
    //                     }
    //                     val calibrationFactor = CalibrationFactorReading()
    //                     calibrationFactor.date = isigValue.getLong("date")
    //                     calibrationFactor.calibrationFactor = isigValue.getDouble("calibrationFactor")
    //                     dbHelper.createIfNotExists(calibrationFactor, "MedLink$sensorType")
    //                 }
    //             }
    //         }
    //         val meters = intent.getBundleExtra("meters")
    //         for (i in 0 until meters.size()) {
    //             val meter = meters.getBundle(i.toString())
    //             meter?.let {
    //                 val timestamp = it.getLong("timestamp")
    //                 aapsLogger.info(LTag.DATABASE, "meters timestamp $timestamp")
    //                 val now = DateUtil.now()
    //                 if (timestamp > now - T.months(1).msecs() && timestamp < now)
    //                     if (MainApp.getDbHelper().getCareportalEventFromTimestamp(timestamp) == null) {
    //                         val jsonObject = JSONObject()
    //                         jsonObject.put("enteredBy", "AndroidAPS-MedLink$sensorType")
    //                         jsonObject.put("created_at", DateUtil.toISOString(timestamp))
    //                         jsonObject.put("eventType", CareportalEvent.BGCHECK)
    //                         jsonObject.put("glucoseType", "Finger")
    //                         jsonObject.put("glucose", meter.getDouble("meterValue"))
    //                         jsonObject.put("units", Constants.MGDL)
    //                         jsonObject.put("mills", timestamp)
    //
    //                         val careportalEvent = CareportalEvent(injector)
    //                         careportalEvent.date = timestamp
    //                         careportalEvent.source = Source.USER
    //                         careportalEvent.eventType = CareportalEvent.BGCHECK
    //                         careportalEvent.json = jsonObject.toString()
    //                         MainApp.getDbHelper().createOrUpdate(careportalEvent)
    //                         nsUpload.uploadCareportalEntryToNS(jsonObject)
    //                     }
    //             }
    //         }
    //
    //         if (sp.getBoolean(R.string.key_medlink_lognssensorchange, false) && intent.hasExtra("sensorInsertionTime")) {
    //             intent.extras?.let {
    //                 val sensorInsertionTime = it.getLong("sensorInsertionTime") * 1000
    //                 val now = DateUtil.now()
    //                 if (sensorInsertionTime > now - T.months(1).msecs() && sensorInsertionTime < now)
    //                     if (MainApp.getDbHelper().getCareportalEventFromTimestamp(sensorInsertionTime) == null) {
    //                         val jsonObject = JSONObject()
    //                         jsonObject.put("enteredBy", "AndroidAPS-MedLink$sensorType")
    //                         jsonObject.put("created_at", DateUtil.toISOString(sensorInsertionTime))
    //                         jsonObject.put("eventType", CareportalEvent.SENSORCHANGE)
    //                         val careportalEvent = CareportalEvent(injector)
    //                         careportalEvent.date = sensorInsertionTime
    //                         careportalEvent.source = Source.USER
    //                         careportalEvent.eventType = CareportalEvent.SENSORCHANGE
    //                         careportalEvent.json = jsonObject.toString()
    //                         MainApp.getDbHelper().createOrUpdate(careportalEvent)
    //                         nsUpload.uploadCareportalEntryToNS(jsonObject)
    //                     }
    //             }
    //         }
    //     } catch (e: Exception) {
    //         aapsLogger.error("Error while processing intent from MedLink App", e)
    //     }
    // }

    // override fun syncBgWithTempId(
    //     bgValues: List<BgSync.BgHistory.BgValue>,
    //     calibrations: List<BgSync.BgHistory.Calibration>
    // ): Boolean {
    //
    //     // if (!confirmActivePump(timestamp, pumpType, pumpSerial)) return false
    //     val glucoseValue = bgValues.map { bgValue ->
    //         CgmSourceTransaction.TransactionGlucoseValue(
    //             timestamp = bgValue.timestamp,
    //             raw = bgValue.raw,
    //             noise = bgValue.noise,
    //             value = bgValue.value,
    //             trendArrow = GlucoseValue.TrendArrow.NONE,
    //             sourceSensor = bgValue.sourceSensor.toDbType(),
    //             isig = bgValue.isig
    //         )
    //     }
    //     val calibrations = calibrations.map{
    //             calibration ->
    //         CgmSourceTransaction.Calibration(timestamp = calibration.timestamp,
    //                                          value = calibration.value, glucoseUnit = calibration.glucoseUnit.toDbType() )
    //     }
    //
    // }
    //
    // override fun addBgTempId(
    //     timestamp: Long,
    //     raw: Double?,
    //     value: Double,
    //     noise: Double?,
    //     arrow: BgSync.BgArrow,
    //     sourceSensor: BgSync.SourceSensor,
    //     isig: Double?,
    //     calibrationFactor: Double?,
    //     sensorUptime: Int?
    // ): Boolean {
    //     val glucoseValue = listOf(
    //         GlucoseValue(timestamp = timestamp,
    //                      raw = raw,
    //                      noise = noise,
    //                      value = value,
    //                      trendArrow = GlucoseValue.TrendArrow.NONE,
    //                      sourceSensor = sourceSensor.toDbType(),
    //                      isig = isig).toj
    //         // CgmSourceTransaction.TransactionGlucoseValue(
    //         //     timestamp = timestamp,
    //         //     raw = raw,
    //         //     noise = noise,
    //         //     value = value,
    //         //     trendArrow = GlucoseValue.TrendArrow.NONE,
    //         //     sourceSensor = sourceSensor.toDbType(),
    //         //     isig = isig
    //         // )
    //     )
    //     val calibrations = listOf<CgmSourceTransaction.Calibration>()
    //
    //     repository.runTransactionForResult(CgmSourceTransaction(glucoseValue, calibrations = calibrations, sensorInsertionTime = null))
    //         .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving Bg", it) }
    //         .blockingGet()
    //         .also { result ->
    //             result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted Bg $it") }
    //             return result.inserted.size > 0
    //         }
    // }
}
