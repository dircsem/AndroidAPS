package app.aaps.pump.common.hw.medlink.service

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.pump.common.hw.medlink.MedLinkUtil
import app.aaps.pump.common.hw.medlink.data.MLHistoryItem
import app.aaps.pump.common.hw.medlink.defs.MedLinkError
import app.aaps.pump.common.hw.medlink.defs.MedLinkServiceState
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkFirmwareVersion
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import app.aaps.pump.common.events.EventMedLinkDeviceStatusChange
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by Dirceu on 24/09/2020.
 * copied from RileyLinkServiceData
 */
@Singleton
class MedLinkServiceData @Inject constructor() {
    var batteryLevel: Int = 0

    @Inject
    lateinit var aapsLogger: AAPSLogger

    @Inject
    lateinit var medLinkUtil: MedLinkUtil

    @Inject
    lateinit var rxBus: RxBus

    var tuneUpDone: Boolean = false
    var medLinkError: MedLinkError? = null
    var medLinkServiceState: MedLinkServiceState = MedLinkServiceState.NotStarted
    var firmwareVersion: RileyLinkFirmwareVersion? = null
    lateinit var rileyLinkTargetFrequency: RileyLinkTargetFrequency

    var rileylinkAddress: String? = null
    var rileylinkName: String? = null
    var lastTuneUpTime: Long = 0L
    var lastGoodFrequency: Double? = null

    // bt version
    var versionBLE113: String? = null

    // radio version
    var versionCC110: String? = null

    // Medtronic Pump
    var pumpIDs: String? = null

    var pumpIDBytes: ByteArray = byteArrayOf(0,0,0)

    var targetDevice: RileyLinkTargetDevice? = null

    fun setPumpID(pumpId: String, pumpIdBytes: ByteArray) {
        this.pumpIDs = pumpId
        this.pumpIDBytes = pumpIdBytes
    }

    fun setServiceState(newState: MedLinkServiceState, errorCode: MedLinkError?) {
        workWithServiceState(newState, errorCode, true)
    }


    @Synchronized
    private fun workWithServiceState(
        newState: MedLinkServiceState,
        errorCode: MedLinkError?, set: Boolean
    ): MedLinkServiceState? {
        if (set) {
            medLinkServiceState = newState
            this.medLinkError = errorCode

            aapsLogger!!.info(
                LTag.PUMP,
                "MedLink State Changed: {} {}",
                newState,
                if (errorCode == null) "" else " - Error State: " + errorCode.name
            )

            medLinkUtil.getMedLinkHistory().add(
                MLHistoryItem(
                    medLinkServiceState, errorCode,
                    targetDevice!!
                )
            )
            rxBus!!.send(EventMedLinkDeviceStatusChange(targetDevice!!, newState, errorCode))


            return null
        } else {
            if (MedLinkServiceState.PumpConnectorReady == newState) {
            }
            return medLinkServiceState
        }
    }
}
