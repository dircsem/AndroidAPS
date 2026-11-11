package app.aaps.plugins.source

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.EversenseCalibrationSource
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withActivity
import app.aaps.core.keys.interfaces.withClick
import app.aaps.core.ui.compose.icons.IcPluginEversense
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.eversense.EversenseCGMPlugin
import app.aaps.plugins.eversense.callbacks.EversenseScanCallback
import app.aaps.plugins.eversense.callbacks.EversenseWatcher
import app.aaps.plugins.eversense.enums.CalibrationReadiness
import app.aaps.plugins.eversense.enums.EversenseAlarm
import app.aaps.plugins.eversense.enums.EversenseType
import app.aaps.plugins.eversense.models.ActiveAlarm
import app.aaps.plugins.eversense.models.EversenseCGMResult
import app.aaps.plugins.eversense.models.EversenseScanResult
import app.aaps.plugins.eversense.models.EversenseSecureState
import app.aaps.plugins.eversense.models.EversenseState
import app.aaps.plugins.eversense.util.StorageKeys
import app.aaps.plugins.source.activities.EversenseCalibrationActivity
import app.aaps.plugins.source.activities.EversensePlacementActivity
import app.aaps.plugins.source.activities.EversenseStatusActivity
import app.aaps.plugins.source.compose.BgSourceComposeContent
import app.aaps.plugins.source.keys.EversenseIntentKey
import app.aaps.plugins.source.keys.EversenseStringKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

class EversensePlugin @Inject constructor(
    rh: ResourceHelper,
    private val context: Context,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    config: Config,
    private val notificationManager: NotificationManager
) : AbstractBgSourcePlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .composeContent { _ ->
            BgSourceComposeContent(
                title = rh.gs(R.string.source_eversense)
            )
        }
        .icon(IcPluginEversense)
        .pluginName(R.string.source_eversense)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_eversense),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences, config
), BgSource, EversenseWatcher, EversenseCalibrationSource {

    @Inject lateinit var persistenceLayer: PersistenceLayer

    override var sensorBatteryLevel = -1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val json = Json { ignoreUnknownKeys = true }

    private val securePrefs by lazy {
        context.getSharedPreferences("EversenseCGMManager", Context.MODE_PRIVATE)
    }

    private fun cloudUploadEnabled() = preferences.get(BooleanKey.EversenseCloudUploadEnabled)
    private fun cloudUploadToastEnabled() = preferences.get(BooleanKey.EversenseCloudUploadToast)

    private val lastNotifiedFirmwareVersion: String get() = securePrefs.getString("last_notified_firmware_version", "") ?: ""
    private fun setLastNotifiedFirmwareVersion(version: String) = securePrefs.edit(commit = true) { putString("last_notified_firmware_version", version) }
    private fun isSensorExpiryDismissed(insertionDate: Long, days: Int): Boolean =
        securePrefs.getBoolean("eversense_expiry_dismissed_${insertionDate}_${days}", false)
    private fun setSensorExpiryDismissed(insertionDate: Long, days: Int) =
        securePrefs.edit(commit = true) { putBoolean("eversense_expiry_dismissed_${insertionDate}_${days}", true) }
    private fun isCalibrationDueDismissed(nextCalibrationDate: Long): Boolean =
        securePrefs.getBoolean("eversense_cal_due_dismissed_${nextCalibrationDate}", false)
    private fun setCalibrationDueDismissed(nextCalibrationDate: Long) =
        securePrefs.edit(commit = true) { putBoolean("eversense_cal_due_dismissed_${nextCalibrationDate}", true) }
    private fun isBatteryLowDismissed(): Boolean =
        securePrefs.getBoolean("eversense_battery_low_dismissed", false)
    private fun setBatteryLowDismissed() =
        securePrefs.edit(commit = true) { putBoolean("eversense_battery_low_dismissed", true) }
    private var consecutiveNoSignalReadings: Int = 0
    private val NO_SIGNAL_WARNING_THRESHOLD = 3
    private val CALIBRATION_RECONNECT_TIMEOUT_MS = 30_000L
    private var releaseForOfficialApp: Boolean = false
    @Volatile private var placementNotificationSnoozed: Boolean = false

    // connectGatt() calls made while the Bluetooth radio is off never fire any callback, so the
    // timer-based reconnect backoff in EversenseGattCallback can silently retry into a dead radio
    // and never recover. This receiver re-triggers a reconnect the moment the adapter is confirmed
    // back on. Uses forceReconnect() rather than connect() because Android also doesn't reliably
    // deliver a disconnect callback when the radio powers off, so the internal "connected" flag
    // may be stale true here.
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                aapsLogger.info(LTag.BGSOURCE, "Bluetooth turned back on — forcing Eversense reconnect")
                ioScope.launch {
                    eversense.forceReconnect()
                }
            }
        }
    }

    init {
        eversense.setContext(context, true)
    }

    override suspend fun onStart() {
        super.onStart()
        ioScope.cancel()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        eversense.addWatcher(this)
        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothStateReceiver, btFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(bluetoothStateReceiver, btFilter)
        }
        if (hasBluetoothPermissions()) {
            aapsLogger.debug(LTag.BGSOURCE, "onStart — permissions granted, attempting auto-reconnect")
            ioScope.launch {
                eversense.connect(null)
            }
        } else {
            aapsLogger.warn(LTag.BGSOURCE, "Bluetooth permissions not granted — requesting permissions")
            requestBluetoothPermissions()
        }
        checkCredentialsNotification()
    }

    override suspend fun onStop() {
        super.onStop()
        ioScope.cancel()
        eversense.removeWatcher(this)
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered — safe to ignore
        }
    }

    private fun requestBluetoothPermissions() {
        val intent = Intent(context, app.aaps.plugins.source.activities.RequestEversensePermissionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun syncCredentialsIfNeeded() = checkCredentialsNotification()

    // ── EversenseCalibrationSource ──
    // isEnabled() comes from PluginBase: true when Eversense is the BG source in use. Same shape as
    // XDripSource and DexcomBoyda.

    override fun isConnected(): Boolean = eversense.isConnected()

    override fun isReadyToCalibrate(): Boolean =
        eversense.getCurrentState()?.calibrationReadiness == CalibrationReadiness.READY

    override fun readinessMessage(): String {
        val state = eversense.getCurrentState()
        return when {
            state == null                                            -> rh.gs(R.string.eversense_not_connected)
            state.calibrationReadiness == CalibrationReadiness.READY -> ""
            else                                                     -> rh.gs(R.string.eversense_calibration_not_ready)
        }
    }

    override suspend fun calibrate(bgMgDl: Int): Boolean = withContext(Dispatchers.IO) {
        if (bgMgDl < EversenseCalibrationSource.MIN_CALIBRATION_MGDL || bgMgDl > EversenseCalibrationSource.MAX_CALIBRATION_MGDL) {
            // Belt and braces: the dialog already bounds the input. A value outside this range
            // would still be written to the transmitter and would shift every later reading.
            aapsLogger.warn(LTag.BGSOURCE, "Eversense calibration refused: $bgMgDl mg/dL is out of range")
            return@withContext false
        }
        if (!isReadyToCalibrate()) {
            aapsLogger.warn(LTag.BGSOURCE, "Eversense calibration refused: transmitter is not ready")
            return@withContext false
        }
        if (!eversense.isConnected()) {
            aapsLogger.info(LTag.BGSOURCE, "Eversense calibration: not connected, connecting first")
            val ready = withTimeoutOrNull(CALIBRATION_RECONNECT_TIMEOUT_MS) { awaitTransmitterReady() }
            if (ready == null) {
                aapsLogger.warn(LTag.BGSOURCE, "Eversense calibration: connect timed out")
                return@withContext false
            }
        }
        val success = eversense.sendCalibration(bgMgDl)
        aapsLogger.info(LTag.BGSOURCE, "Eversense calibration result: $success")
        if (success) eversense.triggerFullSync(force = true)
        success
    }

    // Turns EversenseWatcher's callback into a one-shot suspend wait. The caller's
    // withTimeoutOrNull cancels it, and invokeOnCancellation takes the watcher back off.
    private suspend fun awaitTransmitterReady(): Boolean = suspendCancellableCoroutine { cont ->
        lateinit var watcher: EversenseWatcher
        watcher = object : EversenseWatcher {
            override fun onTransmitterReady() {
                if (cont.isActive) {
                    eversense.removeWatcher(watcher)
                    cont.resume(true)
                }
            }

            override fun onConnectionChanged(connected: Boolean) {}
            override fun onStateChanged(state: EversenseState) {}
            override fun onCGMRead(type: EversenseType, readings: List<EversenseCGMResult>) {}
            override fun onAlarmReceived(alarm: ActiveAlarm) {}
            override fun onTransmitterNotPlaced() {}
        }
        eversense.addWatcher(watcher)
        cont.invokeOnCancellation { eversense.removeWatcher(watcher) }
        eversense.connect(null)
    }

    private fun checkCredentialsNotification() {
        val username = preferences.get(EversenseStringKey.EversenseUsername)
        val password = preferences.get(EversenseStringKey.EversensePassword)
        if (username.isEmpty() || password.isEmpty()) {
            notificationManager.post(
                NotificationId.EVERSENSE_CREDENTIALS,
                rh.gs(R.string.eversense_credentials_missing),
                level = NotificationLevel.URGENT
            )
        } else {
            val secureState = getSecureState()
            val europeanRegion = preferences.get(BooleanKey.EversenseEuropeanRegion)
            // The region picks which DMS host issued a cached token, so a region change has to
            // clear the token cache exactly like a password change does.
            val credentialsChanged = secureState.username != username || secureState.password != password || secureState.isEuropeanRegion != europeanRegion
            secureState.username = username
            secureState.password = password
            secureState.isEuropeanRegion = europeanRegion
            saveSecureState(secureState)
            eversense.username = username
            eversense.password = password
            if (credentialsChanged) {
                securePrefs.edit(commit = true) {
                    remove(StorageKeys.ACCESS_TOKEN)
                    remove(StorageKeys.ACCESS_TOKEN_EXPIRY)
                }
                aapsLogger.info(LTag.BGSOURCE, "Eversense: credentials synced to secure state")
            }
            notificationManager.dismiss(NotificationId.EVERSENSE_CREDENTIALS)
        }
    }

    private fun getSecureState(): EversenseSecureState {
        val stateJson = securePrefs.getString(StorageKeys.SECURE_STATE, null) ?: "{}"
        return json.decodeFromString(stateJson)
    }

    private fun saveSecureState(state: EversenseSecureState) {
        securePrefs.edit(commit = true) {
            putString(StorageKeys.SECURE_STATE, json.encodeToString(EversenseSecureState.serializer(), state))
        }
    }

    /**
     * The injected [context] is the application context, which has no Activity theme. AppCompat dialogs
     * require a Theme.AppCompat descendant, so wrap it before building a dialog from a non-Activity context
     * (e.g. a Compose preference click handler) — otherwise `.show()` throws "You need to use a Theme.AppCompat".
     */
    private fun Context.appCompatDialogContext(): Context =
        ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog_Alert)

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "eversense_settings",
        titleResId = R.string.source_eversense,
        summaryResId = R.string.eversense_plugin_summary,
        items = listOf(
            EversenseIntentKey.EversenseAbout.withClick {
                // The injected [context] is the application context (no Activity theme). An AppCompat
                // AlertDialog needs a Theme.AppCompat descendant, so wrap it — otherwise showing the dialog
                // from the Compose preference host crashes with "You need to use a Theme.AppCompat theme".
                AlertDialog.Builder(context.appCompatDialogContext())
                    .setTitle(rh.gs(R.string.eversense_about_title))
                    .setMessage(rh.gs(R.string.eversense_about_detail))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            },
            EversenseIntentKey.EversenseStatus.withActivity(EversenseStatusActivity::class.java),
            BooleanKey.EversenseCloudUploadEnabled,
            BooleanKey.EversenseCloudUploadToast,
            PreferenceSubScreenDef(
                key = "eversense_credentials_screen",
                titleResId = R.string.eversense_credentials_title,
                items = listOf(
                    EversenseStringKey.EversenseUsername,
                    EversenseStringKey.EversensePassword,
                    BooleanKey.EversenseEuropeanRegion
                )
            ),
            EversenseIntentKey.EversenseSignOut.withClick {
                val cleared = getSecureState().also {
                    it.username = ""
                    it.password = ""
                }
                saveSecureState(cleared)
                aapsLogger.info(LTag.BGSOURCE, "Eversense credentials cleared by user")
            },
            EversenseIntentKey.EversenseCalibration.withActivity(EversenseCalibrationActivity::class.java as Class<*>),
            EversenseIntentKey.EversensePlacement.withActivity(EversensePlacementActivity::class.java as Class<*>)
        ),
        icon = pluginDescription.icon
    )

    private fun startOfficialAppReleaseReconnectLoop() {
        if (false) return
        if (!releaseForOfficialApp) return
        aapsLogger.info(LTag.BGSOURCE, "Release mode — attempting reconnect")
        ioScope.launch {
            eversense.connect(null)
            mainHandler.postDelayed({
                if (eversense.isConnected()) {
                    aapsLogger.info(LTag.BGSOURCE, "Reconnected after official app release")
                    releaseForOfficialApp = false
                    mainHandler.post {
                        notificationManager.dismiss(NotificationId.EVERSENSE_RELEASE)
                    }
                } else {
                    aapsLogger.info(LTag.BGSOURCE, "Reconnect failed — retrying in 5 minutes")
                    mainHandler.postDelayed({ startOfficialAppReleaseReconnectLoop() }, 300000L)
                }
            }, 10000L)
        }
    }

    private fun signalToLabel(strength: Int): String = when {
        strength >= 75 -> "Excellent"
        strength >= 48 -> "Good"
        strength >= 30 -> "Low"
        strength >= 25 -> "Poor"
        strength > 0   -> "Very Poor"
        else           -> rh.gs(R.string.eversense_not_connected)
    }

    override fun onStateChanged(state: EversenseState) {
        aapsLogger.info(LTag.BGSOURCE, "New state received: ${Json.encodeToString(state)}")

        // Update sensor battery level for Overview status lights
        sensorBatteryLevel = if (state.batteryPercentage >= 0) state.batteryPercentage else -1

        // Keep SENSOR_CHANGE therapy event in sync with transmitter insertion date.
        // Disabled for E3: forced overwrite every BT cycle injected raw transmitter uptime
        // (subject to deep-sleep pauses), causing the home screen timer to drift.
        // E365 retains the original sync behavior.
        if (state.insertionDate > 0 && eversense.is365()) {
            ioScope.launch {
                persistenceLayer.insertCgmSourceData(Sources.Eversense, emptyList(), emptyList(), state.insertionDate)
                aapsLogger.debug(LTag.BGSOURCE, "Updated SENSOR_CHANGE event to insertionDate: ${state.insertionDate}")
            }
        }

        // Sync SAGE color thresholds to match Eversense sensor lifetime and notification days
        if (state.insertionDate > 0) {
            val lifetimeDays = if (eversense.is365()) 365 else 180
            val warnHours  = (lifetimeDays - 30) * 24   // orange when 30 days remaining
            val urgentHours = (lifetimeDays - 10) * 24  // red when 10 days remaining
            preferences.put(IntKey.OverviewSageWarning, warnHours)
            preferences.put(IntKey.OverviewSageCritical, urgentHours)
        }

        // Check for persistent no-signal — indicates transmitter not placed over sensor
        if (state.sensorSignalStrength == 0) {
            consecutiveNoSignalReadings++
            aapsLogger.warn(LTag.BGSOURCE, "No signal reading $consecutiveNoSignalReadings of $NO_SIGNAL_WARNING_THRESHOLD")
            if (consecutiveNoSignalReadings >= NO_SIGNAL_WARNING_THRESHOLD) {
                if (!placementNotificationSnoozed) {
                    onTransmitterNotPlaced()
                }
                consecutiveNoSignalReadings = 0
            }
        } else {
            consecutiveNoSignalReadings = 0
            placementNotificationSnoozed = false
            notificationManager.dismiss(NotificationId.EVERSENSE_PLACEMENT)
        }

        // Show sensor expiry notifications at 60, 30, and 10 days remaining — once each, at noon, keyed to insertionDate
        if (state.insertionDate > 0) {
            val sensorLifetimeMs = if (eversense.is365()) 365L * 24 * 60 * 60 * 1000 else 180L * 24 * 60 * 60 * 1000
            val expiryMs = state.insertionDate + sensorLifetimeMs
            val daysRemaining = ((expiryMs - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
            val isAfterNoon = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 12

            if (isAfterNoon && daysRemaining in 31..60 && !isSensorExpiryDismissed(state.insertionDate, 60)) {
                setSensorExpiryDismissed(state.insertionDate, 60)
                notificationManager.post(
                    NotificationId.EVERSENSE_ALARM,
                    "Eversense sensor expires in $daysRemaining days — plan your sensor replacement.",
                    level = NotificationLevel.INFO
                )
            } else if (isAfterNoon && daysRemaining in 11..30 && !isSensorExpiryDismissed(state.insertionDate, 30)) {
                setSensorExpiryDismissed(state.insertionDate, 30)
                notificationManager.post(
                    NotificationId.EVERSENSE_ALARM,
                    "Eversense sensor expires in $daysRemaining days — replace your sensor soon.",
                    level = NotificationLevel.NORMAL
                )
            } else if (isAfterNoon && daysRemaining in 1..10 && !isSensorExpiryDismissed(state.insertionDate, daysRemaining)) {
                setSensorExpiryDismissed(state.insertionDate, daysRemaining)
                notificationManager.post(
                    NotificationId.EVERSENSE_ALARM,
                    "Eversense sensor expires in $daysRemaining days — replace your sensor immediately.",
                    level = NotificationLevel.URGENT
                )
            }
        }

        // Battery low notification — fires once at noon when battery < 11%
        val isAfterNoonBattery = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 12
        if (isAfterNoonBattery && state.batteryPercentage in 1..10 && !isBatteryLowDismissed()) {
            setBatteryLowDismissed()
            notificationManager.post(
                NotificationId.EVERSENSE_ALARM,
                R.string.eversense_battery_low,
                state.batteryPercentage,
                level = NotificationLevel.NORMAL
            )
        }

        // Calibration due notification — fires once per calendar day
        val calDueDay = (state.nextCalibrationDate / 86_400_000L) * 86_400_000L
        if (state.nextCalibrationDate > 0
            && System.currentTimeMillis() >= state.nextCalibrationDate
            && !isCalibrationDueDismissed(calDueDay)) {
            setCalibrationDueDismissed(calDueDay)
            notificationManager.post(
                NotificationId.EVERSENSE_ALARM,
                "Eversense calibration is due — open AAPS to calibrate your sensor.",
                level = NotificationLevel.NORMAL
            )
        }

        // Show firmware notification only once per unique firmware version
        if (state.firmwareVersion.isNotEmpty() && state.firmwareVersion != lastNotifiedFirmwareVersion) {
            setLastNotifiedFirmwareVersion(state.firmwareVersion)
            aapsLogger.info(LTag.BGSOURCE, "Transmitter firmware: ${state.firmwareVersion}")
            notificationManager.post(
                NotificationId.EVERSENSE_FIRMWARE,
                "Eversense firmware: ${state.firmwareVersion} — open the official Eversense app to check for updates",
                level = NotificationLevel.INFO
            )
        }
    }

    override fun onTransmitterNotPlaced() {
        aapsLogger.warn(LTag.BGSOURCE, "Transmitter not placed — firing placement warning notification")
        mainHandler.post {
            notificationManager.post(
                NotificationId.EVERSENSE_PLACEMENT,
                rh.gs(R.string.eversense_transmitter_not_placed),
                level = NotificationLevel.URGENT
            )
        }
    }

    override fun onConnectionChanged(connected: Boolean) {
        aapsLogger.info(LTag.BGSOURCE, "Connection changed — connected: $connected")
        mainHandler.post { }
    }

    override fun onTransmitterReady() {
        aapsLogger.info(LTag.BGSOURCE, "Transmitter ready — scheduling immediate fullSync on bleExecutor")
        eversense.submitToExecutorAndSync(force = true)
    }

    override fun onAlarmReceived(alarm: ActiveAlarm) {
        aapsLogger.info(LTag.BGSOURCE, "Eversense alarm received: ${alarm.code.title}")
        // CRITICAL_FAULT (code 0) is sent for both hardware faults and calibration-overdue events.
        // If the stored next calibration date has already passed, treat it as a calibration alarm.
        val title = if (alarm.code == EversenseAlarm.CRITICAL_FAULT) {
            val stateJson = securePrefs.getString(StorageKeys.STATE, null)
            val state = stateJson?.let { json.decodeFromString<EversenseState>(it) }
            if (state != null && state.nextCalibrationDate > 0 && state.nextCalibrationDate < System.currentTimeMillis()) {
                "Eversense Calibration Due Now"
            } else {
                alarm.code.title
            }
        } else {
            alarm.code.title
        }
        val level = when {
            alarm.code.isCritical -> NotificationLevel.URGENT
            alarm.code.isWarning  -> NotificationLevel.NORMAL
            else                  -> NotificationLevel.INFO
        }
        mainHandler.post {
            notificationManager.post(
                NotificationId.EVERSENSE_ALARM,
                title,
                level = level
            )
        }
    }

    override fun onCGMRead(type: EversenseType, readings: List<EversenseCGMResult>) {
        val glucoseValues = readings.map { reading ->
            GV(
                timestamp = reading.datetime,
                value = reading.glucoseInMgDl.toDouble(),
                noise = null,
                raw = null,
                trendArrow = TrendArrow.fromString(reading.trend.type),
                sourceSensor = when (type) {
                    EversenseType.EVERSENSE_365 -> SourceSensor.EVERSENSE_365
                    EversenseType.EVERSENSE_E3  -> SourceSensor.EVERSENSE_E3
                }
            )
        }

        ioScope.launch {
            val state = eversense.getCurrentState()
            val insertionDate = state?.insertionDate?.takeIf { it > 0 }
            val result = persistenceLayer.insertCgmSourceData(
                Sources.Eversense,
                glucoseValues,
                listOf(),
                insertionDate
            )
            aapsLogger.info(LTag.BGSOURCE, "CGM insert complete — inserted: ${result.inserted}, updated: ${result.updated}")

            // Upload E365 readings to Eversense cloud so official app sees data without needing BLE
            if ((type == EversenseType.EVERSENSE_365 || type == EversenseType.EVERSENSE_E3) && state != null && cloudUploadEnabled()) {
                val prefs = context.getSharedPreferences("EversenseCGMManager", android.content.Context.MODE_PRIVATE)
                // Sync credentials from AAPS preferences into SECURE_STATE so EversenseHttp365Util can read them
                val username = preferences.get(EversenseStringKey.EversenseUsername)
                val password = preferences.get(EversenseStringKey.EversensePassword)
                if (username.isNotEmpty() && password.isNotEmpty()) {
                    val secureState = getSecureState()
                    val europeanRegion = preferences.get(BooleanKey.EversenseEuropeanRegion)
                    // Only clear the token cache when the credentials or the region really changed.
                    // The region picks which DMS host issued a cached token.
                    val credentialsChanged = secureState.username != username || secureState.password != password || secureState.isEuropeanRegion != europeanRegion
                    secureState.username = username
                    secureState.password = password
                    secureState.isEuropeanRegion = europeanRegion
                    saveSecureState(secureState)
                    eversense.username = username
                    eversense.password = password
                    if (credentialsChanged) {
                        securePrefs.edit(commit = true) {
                            remove(StorageKeys.ACCESS_TOKEN)
                            remove(StorageKeys.ACCESS_TOKEN_EXPIRY)
                        }
                        aapsLogger.info(LTag.BGSOURCE, "Eversense: credentials updated — token cache cleared, will re-login on next upload")
                    }
                    notificationManager.dismiss(NotificationId.EVERSENSE_CREDENTIALS)
                } else {
                    notificationManager.post(
                        NotificationId.EVERSENSE_CREDENTIALS,
                        rh.gs(R.string.eversense_credentials_missing),
                        level = NotificationLevel.URGENT
                    )
                }

                val uploadOk = when (type) {
                    EversenseType.EVERSENSE_E3 -> {
                        // E3 uses EU DMS endpoints; no PostEssentialLogs (no raw BLE hex on E3)
                        val latest = readings.firstOrNull() ?: return@launch
                        val portalOk = try {
                            app.aaps.plugins.eversense.util.EversenseHttpE3Util.putCurrentValues(
                                preferences = prefs,
                                glucose = latest.glucoseInMgDl,
                                timestamp = latest.datetime,
                                trend = latest.trend,
                                signalStrength = state.sensorSignalStrength,
                                batteryPercentage = state.batteryPercentage
                            )
                        } catch (e: Exception) {
                            aapsLogger.error(LTag.BGSOURCE, "Eversense E3 putCurrentValues EXCEPTION: ", e)
                            false
                        }
                        val eventsOk = try {
                            app.aaps.plugins.eversense.util.EversenseHttpE3Util.putDeviceEvents(
                                preferences = prefs,
                                readings = readings,
                                transmitterSerialNumber = state.transmitterSerialNumber
                            )
                        } catch (e: Exception) {
                            aapsLogger.error(LTag.BGSOURCE, "Eversense E3 putDeviceEvents EXCEPTION: ", e)
                            false
                        }
                        aapsLogger.info(LTag.BGSOURCE, "Eversense E3 portal sync: ${if (portalOk) "✅ ok" else "❌ failed"}")
                        aapsLogger.info(LTag.BGSOURCE, "Eversense E3 device events: ${if (eventsOk) "✅ ok" else "❌ failed"}")
                        portalOk && eventsOk
                    }
                    EversenseType.EVERSENSE_365 -> try {
                        app.aaps.plugins.eversense.util.EversenseHttp365Util.uploadGlucoseReadings(
                            preferences = prefs,
                            readings = readings,
                            transmitterSerialNumber = state.transmitterName.ifEmpty { state.transmitterSerialNumber },
                            firmwareVersion = state.firmwareVersion
                        )
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.BGSOURCE, "Eversense uploadGlucoseReadings EXCEPTION: : ", e)
                        false
                    }
                }
                val msg = if (uploadOk)
                    "Eversense cloud upload: ✅ ${readings.size} reading(s) sent"
                else
                    "Eversense cloud upload: ❌ failed — check credentials and internet"
                aapsLogger.info(LTag.BGSOURCE, msg)

                if (type == EversenseType.EVERSENSE_365) {
                    val latest = readings.firstOrNull { it.rawResponseHex.isNotEmpty() } ?: readings.firstOrNull()
                    if (latest != null) {
                        val portalOk = app.aaps.plugins.eversense.util.EversenseHttp365Util.putCurrentValues(
                            preferences = prefs,
                            glucose = latest.glucoseInMgDl,
                            timestamp = latest.datetime,
                            trend = latest.trend,
                            signalStrength = state.sensorSignalStrength,
                            batteryPercentage = state.batteryPercentage
                        )
                        aapsLogger.info(LTag.BGSOURCE, "Eversense portal sync: ${if (portalOk) "✅ ok" else "❌ failed"}")
                    }

                    val uploadableReadings = readings.filter { it.rawResponseHex.isNotEmpty() }
                    if (uploadableReadings.isNotEmpty()) {
                        val eventsOk = app.aaps.plugins.eversense.util.EversenseHttp365Util.putDeviceEvents(
                            preferences = prefs,
                            readings = uploadableReadings,
                            transmitterSerialNumber = state.transmitterSerialNumber
                        )
                        aapsLogger.info(LTag.BGSOURCE, "Eversense device events: ${if (eventsOk) "✅ ok" else "❌ failed"}")
                    }
                }

                if (cloudUploadToastEnabled()) {
                    mainHandler.post {
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showDeviceSelectionDialog(context: Context) {
        val dialogContext = context.appCompatDialogContext()
        val foundDevices = mutableListOf<EversenseScanResult>()
        var isCancelled = false
        var dialog: AlertDialog? = null

        val scanCallback = object : EversenseScanCallback {
            override fun onResult(item: EversenseScanResult) {
                val isEversenseTransmitter = item.name.matches(Regex("T\\d+.*"))
                if (!isCancelled && isEversenseTransmitter && foundDevices.none { it.name == item.name }) {
                    foundDevices.add(item)
                    aapsLogger.info(LTag.BGSOURCE, "Scan found device: ${item.name}")
                }
            }
        }

        eversense.startScan(scanCallback)

        mainHandler.postDelayed({
            if (isCancelled) return@postDelayed
            eversense.stopScan()
            dialog?.dismiss()

            if (foundDevices.isEmpty()) {
                AlertDialog.Builder(dialogContext)
                    .setTitle(rh.gs(R.string.eversense_scan_title))
                    .setMessage("No Eversense transmitters found. Make sure the transmitter is nearby and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                val items = foundDevices.map { it.name }.toTypedArray()
                AlertDialog.Builder(dialogContext)
                    .setTitle(rh.gs(R.string.eversense_scan_title))
                    .setItems(items) { _, position ->
                        val selected = foundDevices[position]
                        aapsLogger.info(LTag.BGSOURCE, "User selected device: ${selected.name}")
                        eversense.connect(selected.device)
                    }
                    .setNegativeButton(rh.gs(R.string.eversense_scan_cancel), null)
                    .show()
            }
        }, 10000)

        dialog = AlertDialog.Builder(dialogContext)
            .setTitle(rh.gs(R.string.eversense_scan_title))
            .setMessage("Scanning for Eversense devices (10 seconds)...")
            .setNegativeButton(rh.gs(R.string.eversense_scan_cancel)) { _, _ ->
                isCancelled = true
                eversense.stopScan()
            }
            .setCancelable(false)
            .show()
    }

    companion object {
        private val eversense get() = EversenseCGMPlugin.instance
    }
}

















