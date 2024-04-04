package app.aaps.implementation.queue.commands

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.objects.Instantiator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.BolusProgressData.bolusEnded
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import javax.inject.Inject

class MedLinkCommandBasalPercent(
    private val percent: Int,
    private val durationInMinutes: Int,
    private val enforceNew: Boolean,
    private val profile: Profile,
    override val callback: Callback?,
    override val commandType: Command.CommandType = Command.CommandType.BASAL_PROFILE,

    ) : Command {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var instantiator: Instantiator

    override fun execute() {
        val pump = activePlugin.activePump
        aapsLogger.info(LTag.PUMPQUEUE, "Command bolus plugin: ${pump} ")
        val func = { r: PumpEnactResult ->

            bolusEnded = true
            aapsLogger.debug(LTag.PUMPQUEUE, "Result percent: $percent durationInMinutes: $durationInMinutes success: ${r.success} enacted: ${r.enacted}")
            callback?.result(r)?.run()
            null
        }

        if (pump is MedLinkPumpPluginBase) {
            val result = pump.setTempBasalPercent(percent, durationInMinutes, profile, enforceNew, func)
            callback?.result(result)?.run()
        }
    }

    override fun status(): String = "TEMP BASAL $percent% $durationInMinutes min"
    override fun log(): String = "TEMP BASAL PERCENT"
    override fun cancel() {
        aapsLogger.debug(LTag.PUMPQUEUE, "Result cancel")
        callback?.result(instantiator.providePumpEnactResult().success(false).comment(app.aaps.core.ui.R.string.connectiontimedout))?.run()
    }
}