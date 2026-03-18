package app.aaps.pump.medtronic.comm.activities

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.common.defs.PumpDriverState
import app.aaps.pump.common.defs.PumpRunningState
import app.aaps.pump.common.hw.medlink.activities.BaseCallback
import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.medtronic.MedLinkMedtronicPumpPlugin
import java.util.Optional
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Stream

/**
 * Created by Dirceu on 19/01/21.
 */
class ChangeStatusCallback(
    aapsLogger: AAPSLogger,
    type: OperationType?, plugin: MedLinkMedtronicPumpPlugin
) : BaseCallback<PumpDriverState, Supplier<Stream<String>>>() {
    private val type: OperationType?
    private val aapsLogger: AAPSLogger
    private val medLinkMedtronicPumpPlugin: MedLinkMedtronicPumpPlugin


    enum class OperationType {
        START,
        STOP
    }

    init {
        this.aapsLogger = aapsLogger
        this.type = type
        this.medLinkMedtronicPumpPlugin = plugin
    }

    override fun apply(answer: Supplier<Stream<String>>): MedLinkStandardReturn<PumpDriverState> {
        aapsLogger.info(LTag.PUMPBTCOMM, "StartStop Function")
        val applied = answer.get()
        for (`val` in answer.get().toArray()) {
            aapsLogger.info(LTag.PUMPBTCOMM, `val`.toString())
        }
        val filtered =
            applied.filter { f: String? ->
                !f!!.contains("switching") && f.contains("pump") && f.contains(
                    "state"
                )
            }
        val result: Optional<PumpDriverState> =
            filtered!!.reduce { first: String?, second: String? -> second }.map(
                Function { f: String? ->
                    if (f!!.contains("normal")) {
                        medLinkMedtronicPumpPlugin.setPumpRunningState(
                            PumpRunningState.Running)
                        medLinkMedtronicPumpPlugin.storeCancelTempBasal()
                        //                if (type == OperationType.START) {
//
//                }
                         PumpDriverState.Initialized
                    } else if (f.contains("suspend")) {
                        val tempBasal = medLinkMedtronicPumpPlugin.temporaryBasal
                        if (tempBasal!!.durationInMinutes > 0) {
                            medLinkMedtronicPumpPlugin.setPumpRunningState(
                                PumpRunningState.TempBasalSuspended)
                            aapsLogger.info(LTag.PUMPBTCOMM, "pump temp basal suspend")
                        } else {
                            medLinkMedtronicPumpPlugin.setPumpRunningState(
                                PumpRunningState.Suspended)
                            aapsLogger.info(LTag.PUMPBTCOMM, "pump suspend")
                        }
                        val tempBasalData = medLinkMedtronicPumpPlugin.temporaryBasal
                        if (tempBasalData != null && tempBasalData.plannedRemainingMinutes > 0) {
                            medLinkMedtronicPumpPlugin.createTemporaryBasalData(
                                medLinkMedtronicPumpPlugin.durationInMinutes,
                                0.0
                            )
                        } else {
                            medLinkMedtronicPumpPlugin.createTemporaryBasalData(30, 0.0)
                        }
                         PumpDriverState.Suspended
                    } else {
                        aapsLogger.info(LTag.PUMPBTCOMM, "pump busy")
                        medLinkMedtronicPumpPlugin.changeStatusTime(System.currentTimeMillis())
                         PumpDriverState.Busy
                    }
                })
        return MedLinkStandardReturn<PumpDriverState>(answer, result.orElse(PumpDriverState.Busy))
    }
}
