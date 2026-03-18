package app.aaps.pump.medtronic.comm.activities

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BGReadingStatus
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.source.BgSync
import app.aaps.pump.common.hw.medlink.activities. BaseStatusCallback
import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.common.hw.medlink.service.MedLinkStatusParser.Companion.parseStatus
import app.aaps.pump.medtronic.MedLinkMedtronicPumpPlugin
import java.util.*
import java.util.function.Supplier
import java.util.regex.Pattern
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Created by Dirceu on 19/01/21.
 */
class StatusCallback(
    private val aapsLogger: AAPSLogger,
    private val medLinkPumpPlugin: MedLinkMedtronicPumpPlugin, private val medLinkPumpStatus: MedLinkPumpStatus
) : BaseStatusCallback<MedLinkPumpStatus>(medLinkPumpStatus) {

    override fun apply(streamSupplier: Supplier<Stream<String>>): MedLinkStandardReturn<MedLinkPumpStatus> {
        val f = MedLinkStandardReturn(streamSupplier, medLinkPumpStatus)
        val answer = f.getAnswer()
        aapsLogger.info(LTag.PUMPBTCOMM, "BaseResultActivity")
        aapsLogger.info(LTag.PUMPBTCOMM, streamSupplier.get().collect(Collectors.joining()))
        val messages: Array<String> = answer.toArray().map { it.toString() }.toTypedArray()
        aapsLogger.debug("ready")
        val pattern = Pattern.compile("\\d+")
        val matcher = pattern.matcher(messages[0])
        if (matcher.find()) {
            medLinkPumpStatus.lastConnection = Objects.requireNonNull(matcher.group(0)).toLong()
        }
        val pumpStatus = medLinkPumpPlugin.medLinkPumpStatus
        parseStatus(messages, medLinkPumpStatus,  aapsLogger)
        aapsLogger.debug("Pumpstatus")
        aapsLogger.debug(pumpStatus.toString())
        medLinkPumpStatus.pumpDeviceState = PumpDeviceState.Active
        medLinkPumpPlugin.alreadyRun()
        medLinkPumpPlugin.setPumpTime(pumpStatus.lastDataTime)
        aapsLogger.info(LTag.PUMPBTCOMM, "statusmessage currentbasal " + pumpStatus.currentBasal)
        aapsLogger.info(LTag.PUMPBTCOMM, "statusmessage currentbasal " + pumpStatus.reservoirRemainingUnits)
        aapsLogger.info(LTag.PUMPBTCOMM, "status " + medLinkPumpStatus.currentBasal)
        medLinkPumpStatus.setLastCommunicationToNow()
        aapsLogger.info(LTag.PUMPBTCOMM, "bgreading " + pumpStatus.bgReading.toString())
        if (pumpStatus.bgReading != null) {
            medLinkPumpPlugin.handleNewSensorData(BgSync.BgHistory(listOf(pumpStatus.sensorDataReading), emptyList(), null))
            medLinkPumpStatus.lastReadingStatus = BGReadingStatus.SUCCESS
        } else {
            medLinkPumpPlugin.handleNewPumpData()
            medLinkPumpStatus.lastReadingStatus = BGReadingStatus.FAILED
        }
        medLinkPumpPlugin.sendPumpUpdateEvent()
        if (f.getAnswer().anyMatch { m -> m.contains("eomeomeom") || m.contains("ready") } || messages[messages.size - 1].lowercase(Locale.getDefault()) == "ready") {
            medLinkPumpStatus.pumpDeviceState = PumpDeviceState.Active
        } else {
            aapsLogger.debug("Apply last message" + messages[messages.size - 1])
            medLinkPumpStatus.pumpDeviceState = PumpDeviceState.PumpUnreachable
            //            String errorMessage = PumpDeviceState.PumpUnreachable.name();
            f.addError(MedLinkStandardReturn.ParsingError.Unreachable)
        }
        return f
    }
}