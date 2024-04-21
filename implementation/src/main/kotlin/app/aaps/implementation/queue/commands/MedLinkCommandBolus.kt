package app.aaps.implementation.queue.commands

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.objects.Instantiator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.BolusProgressData.bolusEnded

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.resources.ResourceHelper
import dagger.android.HasAndroidInjector
import javax.inject.Inject

class MedLinkCommandBolus(
    injector: HasAndroidInjector,
    private val detailedBolusInfo: DetailedBolusInfo,
    override val callback: Callback?,
) : Command {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var instantiator: Instantiator

    init {
        injector.androidInjector().inject(this)
    }

    override val commandType: Command.CommandType=Command.CommandType.BOLUS

    override fun execute() {
        val pump = activePlugin.activePump
        aapsLogger.info(LTag.PUMPQUEUE, "Command bolus plugin: ${pump} ")
        val func = { r: PumpEnactResult ->

                bolusEnded = true
                // rxBus.send(EventDismissBolusProgressIfRunning(r))
                aapsLogger.info(LTag.PUMPQUEUE, "Result success: ${r.success} enacted: ${r.enacted}")
                callback?.result(r)?.run()
                Unit
        }

        if (pump is MedLinkPumpPluginBase) {
            pump.deliverTreatment(detailedBolusInfo, func)
        }

        // val r = activePlugin.activePump

    }

    override fun status(): String {
        return (if (detailedBolusInfo.insulin > 0) "BOLUS " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, detailedBolusInfo.insulin) else "") +
            if (detailedBolusInfo.carbs > 0) "CARBS " + rh.gs(app.aaps.core.ui.R.string.format_carbs, detailedBolusInfo.carbs.toInt()) else ""
    }

    override fun log(): String =  "BOLUS"

    override fun cancel() {
        aapsLogger.debug(LTag.PUMPQUEUE, "Result cancel")
        callback?.result(instantiator.providePumpEnactResult().success(false).comment(app.aaps.core.ui.R.string.connectiontimedout))?.run()
    }
}