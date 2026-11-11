package app.aaps.implementation.queue.commands

import kotlinx.coroutines.runBlocking
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
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
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import javax.inject.Provider

/**
 * MedLink SMB. Not upstream's CommandSMBBolus: that one refuses an SMB whose deliverAt is older than 1 min, which
 * refused MedLink SMBs (logs/smbbolus_fail.log); MedLink keeps the user's rule in [decide].
 */
class MedLinkCommandSMBBolus(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val activePlugin: ActivePlugin,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val bolusProgressData: BolusProgressData,
    override val pumpEnactResultProvider: Provider<PumpEnactResult>,
    private val detailedBolusInfo: DetailedBolusInfo,
    override val callback: Callback?,
    private val bolusGeneration: Long,
) : Command {

    override val commandType: Command.CommandType = Command.CommandType.SMB_BOLUS

    override suspend fun execute(): PumpEnactResult {
        // SMB interval (loop "smbinterval") since the last SMB or manual bolus; TBRs and their microboluses do not count
        val smbInterval = preferences.get(IntKey.ApsMaxSmbFrequency)
        val lastBolusTime = runBlocking { persistenceLayer.getLastNonTBRBolusTime() }?.timestamp ?: 0L
        val r = when (decide(lastBolusTime, detailedBolusInfo.deliverAtTheLatest, smbInterval, dateUtil.now())) {
            Decision.IN_INTERVAL   -> {
                aapsLogger.debug(LTag.PUMPQUEUE, "SMB requested but still in $smbInterval min interval (last SMB/manual bolus ${dateUtil.dateAndTimeAndSecondsString(lastBolusTime)})")
                pumpEnactResultProvider.get().enacted(false).success(false).comment("SMB requested but still in $smbInterval min interval")
            }

            Decision.NO_DELIVER_AT -> {
                aapsLogger.debug(LTag.PUMPQUEUE, "SMB bolus canceled: request without deliverAt")
                pumpEnactResultProvider.get().enacted(false).success(false).comment("SMB request without deliverAt")
            }

            Decision.DELIVER       -> {
                val pump = activePlugin.activePumpInternal
                if (pump is MedLinkPumpPluginBase) {
                    aapsLogger.info(LTag.PUMP, detailedBolusInfo.toString())
                    MedLinkCallbackBridge.awaitResult(
                        onTimeout = { pumpEnactResultProvider.get().enacted(false).success(false).comment("MedLink did not answer the SMB") }
                    ) { done -> pump.deliverTreatment(detailedBolusInfo, done) }
                } else pumpEnactResultProvider.get().enacted(false).success(false).comment("active pump is not MedLink")
            }
        }
        aapsLogger.debug(LTag.PUMPQUEUE, "Result success: ${r.success} enacted: ${r.enacted}")
        // generation-scoped: never wipe a newer bolus enqueued behind this SMB
        bolusProgressData.clear(bolusGeneration)
        return r
    }

    override fun status(): String = rh.gs(app.aaps.core.ui.R.string.smb_bolus_u, detailedBolusInfo.insulin)
    override fun log(): String = "SMB BOLUS ${rh.gs(app.aaps.core.ui.R.string.format_insulin_units, detailedBolusInfo.insulin)}"

    override fun cancel(commentResId: Int, success: Boolean) {
        super.cancel(commentResId, success)
        bolusProgressData.clear(bolusGeneration)
    }

    enum class Decision { DELIVER, IN_INTERVAL, NO_DELIVER_AT }

    companion object {

        /**
         * The user's rule (4q): an SMB waits the SMB interval (loop "smbinterval", minutes) after the last SMB or manual
         * bolus ([lastSmbOrManualBolusAt], 0 = none). TBRs do not count: deliverAt is the loop's last TBR time and is
         * only required to be set.
         */
        fun decide(lastSmbOrManualBolusAt: Long, deliverAtTheLatest: Long, smbIntervalMinutes: Int, now: Long): Decision = when {
            lastSmbOrManualBolusAt != 0L && lastSmbOrManualBolusAt + T.mins(smbIntervalMinutes.toLong()).msecs() > now -> Decision.IN_INTERVAL
            deliverAtTheLatest == 0L                                                                                 -> Decision.NO_DELIVER_AT
            else                                                                                                     -> Decision.DELIVER
        }
    }
}
