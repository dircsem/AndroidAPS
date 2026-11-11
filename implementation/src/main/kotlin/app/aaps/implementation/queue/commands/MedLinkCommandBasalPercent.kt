package app.aaps.implementation.queue.commands

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.MedLinkCallbackBridge
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.resources.ResourceHelper
import javax.inject.Provider

/**
 * MedLink TBR: emulated with suspensions and microboluses, so the driver needs the profile.
 * Command type TEMPBASAL (it was BASAL_PROFILE before the AAPS 4.0 merge, so a profile switch removed a queued TBR
 * and a TBR cancel did not).
 */
class MedLinkCommandBasalPercent(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val activePlugin: ActivePlugin,
    override val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val percent: Int,
    private val durationInMinutes: Int,
    private val enforceNew: Boolean,
    private val profile: Profile,
    override val callback: Callback?,
) : Command {

    override val commandType: Command.CommandType = Command.CommandType.TEMPBASAL

    override suspend fun execute(): PumpEnactResult {
        val pump = activePlugin.activePumpInternal
        val r = if (pump is MedLinkPumpPluginBase)
            MedLinkCallbackBridge.awaitResult(
                onTimeout = { pumpEnactResultProvider.get().enacted(false).success(false).comment("MedLink did not answer the temp basal") }
            ) { done -> pump.setTempBasalPercent(percent, durationInMinutes, profile, enforceNew, done) }
        else pumpEnactResultProvider.get().enacted(false).success(false).comment("active pump is not MedLink")
        aapsLogger.debug(LTag.PUMPQUEUE, "Result percent: $percent durationInMinutes: $durationInMinutes success: ${r.success} enacted: ${r.enacted}")
        return r
    }

    override fun status(): String = "TEMP BASAL $percent% $durationInMinutes min"
    override fun log(): String = "TEMP BASAL PERCENT"
}
