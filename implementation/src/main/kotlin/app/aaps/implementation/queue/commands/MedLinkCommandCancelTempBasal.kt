package app.aaps.implementation.queue.commands

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.objects.Instantiator
import app.aaps.core.interfaces.plugin.ActivePlugin
import dagger.android.HasAndroidInjector
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.resources.ResourceHelper
import javax.inject.Inject

class MedLinkCommandCancelTempBasal(
    injector: HasAndroidInjector,
    private val enforceNew: Boolean,
    override val callback: Callback?, override val commandType: Command.CommandType = Command.CommandType.TEMPBASAL
) : Command {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var instantiator: Instantiator


    override fun execute() {
        val pump = activePlugin.activePump
        aapsLogger.info(LTag.PUMPQUEUE, "cancelling temp basal: ")
        if(pump is MedLinkPumpPluginBase){
            pump.cancelTempBasal(enforceNew, callback)
        }else {
            val r = activePlugin.activePump.cancelTempBasal(enforceNew)
            aapsLogger.debug(LTag.PUMPQUEUE, "Result success: ${r.success} enacted: ${r.enacted}")
            callback?.result(r)?.run()
        }
    }

    override fun status(): String = "CANCEL TEMPBASAL"
    override fun log(): String = "CANCEL TEMPBASAL"

    override fun cancel() {
        aapsLogger.debug(LTag.PUMPQUEUE, "Result cancel")
        callback?.result(instantiator.providePumpEnactResult().success(false).comment(app.aaps.core.ui.R.string.connectiontimedout))?.run()
    }
}