package app.aaps.implementation.queue.commands

import kotlinx.coroutines.runBlocking
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import javax.inject.Provider

/**
 * 4v: MedLink cannot write the basal profile to the Veo (setNewBasalProfile stays off): the profile is accepted in
 * AAPS only, so the profile switch becomes effective (EffectiveProfileSwitch). The pump plugin warns when the pump
 * basal profile differs; the user changes it on the pump.
 */
class MedLinkCommandSetProfile(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val commandQueue: CommandQueue,
    private val persistenceLayer: PersistenceLayer,
    override val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val profile: EffectiveProfile,
    override val callback: Callback?,
) : Command {

    override val commandType: Command.CommandType = Command.CommandType.BASAL_PROFILE

    override suspend fun execute(): PumpEnactResult {
        if (runBlocking { commandQueue.isThisProfileSet(profile) } && runBlocking { persistenceLayer.getEffectiveProfileSwitchActiveAt(dateUtil.now()) } != null) {
            aapsLogger.debug(LTag.PUMPQUEUE, "Correct profile already set. profile: $profile")
            return pumpEnactResultProvider.get().success(true).enacted(false)
        }
        aapsLogger.info(LTag.PUMPQUEUE, "MedLink: basal profile not written to the pump, accepted in AAPS only. profile: $profile")
        return pumpEnactResultProvider.get().success(true).enacted(false)
    }

    override fun status(): String = rh.gs(app.aaps.core.ui.R.string.set_profile)
    override fun log(): String = "SET PROFILE"
}
