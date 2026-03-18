package app.aaps.pump.common.hw.medlink.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import dagger.android.DaggerService
import dagger.android.HasAndroidInjector
import app.aaps.pump.common.hw.medlink.MedLinkCommunicationManager
import app.aaps.pump.common.hw.medlink.MedLinkUtil
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.ble.MedLinkRFSpy
import app.aaps.pump.common.hw.medlink.defs.MedLinkError
import app.aaps.pump.common.hw.medlink.defs.MedLinkServiceState
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.medlink.MedLinkConst.Prefs.LastGoodDeviceFrequency
import app.aaps.pump.common.hw.medlink.defs.MedLinkEncodingType
import app.aaps.pump.common.hw.medlink.keys.MedLinkDoubleKey

import app.aaps.pump.common.hw.rileylink.keys.RileyLinkDoubleKey
import javax.inject.Inject

/**
 * Created by Dirceu on 30/09/20.
 */
abstract class MedLinkService : DaggerService() {

    @Inject lateinit var rfSpy: MedLinkRFSpy

    @Inject
    lateinit var aapsLogger: AAPSLogger

    @Inject
    lateinit var preferences: Preferences

    @Inject
    lateinit var context: Context

    @Inject
    lateinit var rxBus: RxBus

    @Inject
    lateinit var medLinkUtil: MedLinkUtil

    @Inject
    lateinit var injector: HasAndroidInjector

    @Inject
    lateinit var resourceHelper: ResourceHelper

    @Inject
    lateinit var medLinkServiceData: MedLinkServiceData

    @Inject
    lateinit var activePlugin: ActivePlugin

    @Inject
    lateinit var medLinkBLE: MedLinkBLE

    protected val bluetoothAdapter: BluetoothAdapter? get() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    protected var mBroadcastReceiver: MedLinkBroadcastReceiver? = null
    protected var bluetoothStateReceiver: MedLinkBluetoothStateReceiver? = null
    override fun onCreate() {
        super.onCreate()
        aapsLogger.info(LTag.EVENTS, "OnCreate medlinkservice")
        medLinkUtil.encoding = encoding
        initRileyLinkServiceData()
        mBroadcastReceiver = MedLinkBroadcastReceiver(this)
        mBroadcastReceiver?.registerBroadcasts(this)
        bluetoothStateReceiver = MedLinkBluetoothStateReceiver()
        bluetoothStateReceiver?.registerBroadcasts(this)
    }

    /**
     * Get Encoding for RileyLink communication
     */
    abstract val encoding: MedLinkEncodingType

    /**
     * If you have customized RileyLinkServiceData you need to override this
     */
    abstract fun initRileyLinkServiceData()
    override fun onUnbind(intent: Intent): Boolean {
        //aapsLogger.warn(LTag.PUMPBTCOMM, "onUnbind");
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent) {
        //aapsLogger.warn(LTag.PUMPBTCOMM, "onRebind");
        super.onRebind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        //LOG.error("I die! I die!");

        // xyz rfspy.stopReader();
        medLinkBLE.disconnect() // dispose of Gatt (disconnect and close)
        mBroadcastReceiver?.unregisterBroadcasts(this)
        bluetoothStateReceiver?.unregisterBroadcasts(this)
    }

    abstract val deviceCommunicationManager: MedLinkCommunicationManager

    // Here is where the wake-lock begins:
    // We've received a service startCommand, we grab the lock.
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun bluetoothInit(): Boolean {
        aapsLogger.debug(LTag.PUMPBTCOMM, "bluetoothInit: attempting to get an adapter")
        medLinkServiceData.medLinkServiceState = MedLinkServiceState.BluetoothInitializing
        if (bluetoothAdapter == null) {
            aapsLogger.error("Unable to obtain a BluetoothAdapter.")
            medLinkServiceData.setServiceState(
                MedLinkServiceState.BluetoothError,
                MedLinkError.NoBluetoothAdapter)
        } else {
            if (!bluetoothAdapter!!.isEnabled) {
                aapsLogger.error("Bluetooth is not enabled.")
                medLinkServiceData.setServiceState(
                    MedLinkServiceState.BluetoothError,
                    MedLinkError.BluetoothDisabled)
            } else {
                medLinkServiceData.medLinkServiceState = MedLinkServiceState.BluetoothReady
                return true
            }
        }
        return false
    }

    // returns true if our Rileylink configuration changed
    fun reconfigureCommunicator(deviceAddress: String): Boolean {
        medLinkServiceData.medLinkServiceState = MedLinkServiceState.MedLinkInitializing
        return if (medLinkBLE.isConnected()) {
            if (deviceAddress == medLinkServiceData.rileylinkAddress) {
                aapsLogger.info(LTag.PUMPBTCOMM, "No change to RL address.  Not reconnecting.")
                false
            } else {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Disconnecting from old RL (" + medLinkServiceData.rileylinkAddress
                    + "), reconnecting to new: " + deviceAddress)
                medLinkBLE.disconnect()
                // prolly need to shut down listening thread too?
                // SP.putString(MedtronicConst.Prefs.RileyLinkAddress, deviceAddress);
                medLinkServiceData.rileylinkAddress = deviceAddress
                val medlinkAddress =
                medLinkBLE.findMedLink(deviceAddress)
                true
            }
        } else {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Using ML $deviceAddress")
            if (medLinkServiceData.medLinkServiceState == MedLinkServiceState.NotStarted) {
                if (!bluetoothInit()) {
                    aapsLogger.error("MedLink can't get activated, Bluetooth is not functioning correctly. {}",
                                       if (error != null) error!!.name else "Unknown error (null)")
                    return false
                }
            }
            medLinkBLE.findMedLink(deviceAddress)
            true
        }
    }

    // FIXME: This needs to be run in a session so that is interruptable, has a separate thread, etc.
    fun doTuneUpDevice() {
        aapsLogger.debug("DoTuneIp")
        medLinkServiceData.medLinkServiceState = MedLinkServiceState.TuneUpDevice
        setPumpDeviceState(PumpDeviceState.Sleeping)
        var lastGoodFrequency = 0.0
        lastGoodFrequency = if (medLinkServiceData.lastGoodFrequency != null) {
            medLinkServiceData.lastGoodFrequency!!
        } else {
            preferences.get(MedLinkDoubleKey.LastGoodDeviceFrequency)
        }
        val newFrequency: Double
        newFrequency = deviceCommunicationManager.tuneForDevice()
        if (newFrequency != 0.0 && newFrequency != lastGoodFrequency) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Saving new pump frequency of {} MHz", newFrequency)
            preferences.put(MedLinkDoubleKey.LastGoodDeviceFrequency, newFrequency)
            medLinkServiceData.lastGoodFrequency = newFrequency
            medLinkServiceData.tuneUpDone = true
            medLinkServiceData.lastTuneUpTime = System.currentTimeMillis()
        }
        aapsLogger.debug("New pump frequency $newFrequency")
        if (newFrequency == 0.0) {
            // error tuning pump, pump not present ??
            medLinkServiceData.setServiceState(
                MedLinkServiceState.PumpConnectorError,
                MedLinkError.TuneUpOfDeviceFailed)
        } else {
            deviceCommunicationManager.clearNotConnectedCount()
            medLinkServiceData.medLinkServiceState = MedLinkServiceState.PumpConnectorReady
        }
    }

    abstract fun setPumpDeviceState(pumpDeviceState: PumpDeviceState)
    fun disconnectRileyLink() {
        if (medLinkBLE.isConnected()) {
            aapsLogger.info(LTag.PUMPBTCOMM, "disconnectingMedlink")
            medLinkBLE.disconnect()
            medLinkServiceData.rileylinkAddress = null
        }
        medLinkServiceData.medLinkServiceState = MedLinkServiceState.BluetoothReady
    }



    fun changeMedLinkEncoding() {}
    val error: MedLinkError?
        get() = medLinkServiceData.medLinkError

    abstract fun verifyConfiguration(): Boolean
}