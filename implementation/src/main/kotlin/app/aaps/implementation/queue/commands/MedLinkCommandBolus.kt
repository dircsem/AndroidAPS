package app.aaps.implementation.queue.commands

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkCallbackBridge
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.resources.ResourceHelper
import javax.inject.Provider

/** MedLink manual bolus: the driver answers when the MedLink reports the bolus (carbs are stored by the queue after success). */
class MedLinkCommandBolus(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    override val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val bolusProgressData: BolusProgressData,
    private val detailedBolusInfo: DetailedBolusInfo,
    override val callback: Callback?,
    type: Command.CommandType,
    private val bolusGeneration: Long,
) : Command {

    override var commandType: Command.CommandType = type

    override suspend fun execute(): PumpEnactResult {
        val pump = activePlugin.activePumpInternal
        aapsLogger.info(LTag.PUMPQUEUE, "Command bolus plugin: $pump")
        val r = if (pump is MedLinkPumpPluginBase)
            MedLinkCallbackBridge.awaitResult(
                onTimeout = { pumpEnactResultProvider.get().enacted(false).success(false).comment("MedLink did not answer the bolus") }
            ) { done -> pump.deliverTreatment(detailedBolusInfo, done) }
        else pumpEnactResultProvider.get().enacted(false).success(false).comment("active pump is not MedLink")
        aapsLogger.info(LTag.PUMPQUEUE, "Result success: ${r.success} enacted: ${r.enacted}")
        if (r.success) bolusProgressData.completeAndAutoClear(bolusGeneration)
        else bolusProgressData.clear(bolusGeneration)
        return r
    }

    override fun status(): String =
        (if (detailedBolusInfo.insulin > 0) rh.gs(app.aaps.core.ui.R.string.bolus_u_min, detailedBolusInfo.insulin) else "") +
            if (detailedBolusInfo.carbs > 0) rh.gs(app.aaps.core.ui.R.string.carbs_g, detailedBolusInfo.carbs.toInt()) else ""

    override fun log(): String = "BOLUS"

    override fun cancel(commentResId: Int, success: Boolean) {
        super.cancel(commentResId, success)
        bolusProgressData.clear(bolusGeneration)
    }
}
