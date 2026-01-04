package app.aaps.pump.common.hw.medlink.ble.data

import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.common.hw.medlink.ble.CommandPriority
import app.aaps.pump.common.hw.medlink.ble.command.BleCommand
import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Stream

class BolusStatusMedLinkMessage<B>(
    baseCallback: Function<Supplier<Stream<String>>, MedLinkStandardReturn<B>>,
    btSleepTime: Long, bleCommand: BleCommand
) : MedLinkPumpMessage<B, Any>(
    MedLinkCommandType.BolusStatus, MedLinkCommandType.NoCommand, baseCallback, btSleepTime, bleCommand, CommandPriority.LOWER) {

}