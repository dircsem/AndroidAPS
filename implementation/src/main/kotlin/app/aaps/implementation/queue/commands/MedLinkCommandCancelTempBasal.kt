package app.aaps.implementation.queue.commands

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.MedLinkCallbackBridge
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.resources.ResourceHelper
import javax.inject.Provider

class MedLinkCommandCancelTempBasal(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    override val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val enforceNew: Boolean,
    override val callback: Callback?,
) : Command {

    override val commandType: Command.CommandType = Command.CommandType.TEMPBASAL

    override suspend fun execute(): PumpEnactResult {
        val pump = activePlugin.activePumpInternal
        aapsLogger.info(LTag.PUMPQUEUE, "cancelling temp basal")
        val r = if (pump is MedLinkPumpPluginBase)
            MedLinkCallbackBridge.await(
                onTimeout = { pumpEnactResultProvider.get().enacted(false).success(false).comment("MedLink did not answer the temp basal cancel") }
            ) { cb -> pump.cancelTempBasal(false, enforceNew, cb) }
        else pump.cancelTempBasal(enforceNew)
        aapsLogger.debug(LTag.PUMPQUEUE, "Result success: ${r.success} enacted: ${r.enacted}")
        return r
    }

    override fun status(): String = rh.gs(app.aaps.core.ui.R.string.uel_cancel_temp_basal)
    override fun log(): String = "CANCEL TEMPBASAL"
}
