package app.aaps.implementation.queue.commands

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpPluginBase
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import javax.inject.Provider

class MedLinkCommandSMBBolus(
    injector: HasAndroidInjector,
    private val detailedBolusInfo: DetailedBolusInfo,
    override val callback: Callback?,
) : Command {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var persistenceLayer: PersistenceLayer

    @Inject lateinit var preferences: Preferences

    @Inject lateinit var pumpEnactResultProvider: Provider<PumpEnactResult>

    init {
        injector.androidInjector().inject(this)
    }

    override val commandType: Command.CommandType = Command.CommandType.SMB_BOLUS

    override fun execute() {
        val r: PumpEnactResult
        // SMB interval (loop "smbinterval") since the last SMB or manual bolus; TBRs and their microboluses do not count
        val smbInterval = preferences.get(IntKey.ApsMaxSmbFrequency)
        val lastBolusTime = persistenceLayer.getLastNonTBRBolusTime()?.timestamp ?: 0L
        when (decide(lastBolusTime, detailedBolusInfo.deliverAtTheLatest, smbInterval, dateUtil.now())) {
            Decision.IN_INTERVAL    -> {
                aapsLogger.debug(LTag.PUMPQUEUE, "SMB requested but still in $smbInterval min interval (last SMB/manual bolus ${dateUtil.dateAndTimeAndSecondsString(lastBolusTime)})")
                r = pumpEnactResultProvider.get().enacted(false).success(false).comment("SMB requested but still in $smbInterval min interval")
                callback?.result(r)?.run()
                aapsLogger.debug(LTag.PUMPQUEUE, "Result success: ${r.success} enacted: ${r.enacted}")
            }

            Decision.NO_DELIVER_AT -> {
                aapsLogger.debug(LTag.PUMPQUEUE, "SMB bolus canceled: request without deliverAt")
                r = pumpEnactResultProvider.get().enacted(false).success(false).comment("SMB request without deliverAt")
                callback?.result(r)?.run()
                aapsLogger.debug(LTag.PUMPQUEUE, "Result success: ${r.success} enacted: ${r.enacted}")
            }

            Decision.DELIVER       -> {
                val func = { it: PumpEnactResult ->
                    callback?.result(it)?.run()
                    aapsLogger.debug(LTag.PUMPQUEUE, "Result success: ${it.success} enacted: ${it.enacted}")
                    aapsLogger.debug(LTag.PUMPQUEUE, detailedBolusInfo.toString())
                }
                val pump = activePlugin.activePump
                if (pump is MedLinkPumpPluginBase) {
                    aapsLogger.info(LTag.PUMP, detailedBolusInfo.toString())
                    pump.deliverTreatment(detailedBolusInfo, func)
                } else {
                    func(pumpEnactResultProvider.get().enacted(false).success(false).comment("active pump is not MedLink"))
                }
            }
        }
    }

    override fun status(): String = "SMB BOLUS ${rh.gs(app.aaps.core.ui.R.string.format_insulin_units, detailedBolusInfo.insulin)}"
    override fun log(): String ="SMB BOLUS"

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

    override fun cancel() {
        aapsLogger.debug(LTag.PUMPQUEUE, "Result cancel")
        callback?.result(pumpEnactResultProvider.get().success(false).comment(app.aaps.core.ui.R.string.connectiontimedout))?.run()
    }
}