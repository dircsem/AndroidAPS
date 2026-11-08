package app.aaps.pump.common.hw.medlink.activities

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissBolusProgressIfRunning
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.pump.common.hw.medlink.ble.CommandExecutor
import app.aaps.pump.common.hw.medlink.bolus.BolusOutcome
import app.aaps.pump.common.hw.medlink.bolus.BolusProgressRule
import app.aaps.pump.common.hw.medlink.defs.MedLinkPumpDevice
import app.aaps.pump.common.hw.medlink.service.MedLinkStatusParser
import java.util.Date
import java.util.function.Supplier
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Follows a bolus with `M` answers and records it once the pump shows it.
 *
 * Root cause 7: `square bolus: 0.0u delivered: 0.000u` only says that nothing is in progress.
 * The bolus is recorded only when [BolusProgressRule] finds a pump bolus newer than the one known
 * before sending ([baselineTime], pump clock), so a bolus that was never applied is never recorded.
 */
data class BolusProgressCallback(
    val resourceHelper: ResourceHelper,
    val rxBus: RxBus,
    val aapsLogger: AAPSLogger,
    val medLinkPumpPlugin: MedLinkPumpDevice,
    val detailedBolusInfo: DetailedBolusInfo
) : BaseStringAggregatorCallback() {

    var commandExecutor: CommandExecutor<String>? = null
    var resend = true
    fun resend(): Boolean = resend

    private val createdAt = System.currentTimeMillis()
    private val baselineTime: Long? = medLinkPumpPlugin.medLinkPumpStatus.lastBolusTime?.time
    private val baselineAmount: Double? = medLinkPumpPlugin.medLinkPumpStatus.lastBolusAmount
    private var recorded = false

    override fun apply(answer: Supplier<Stream<String>>): MedLinkStandardReturn<String> {
        val lines = answer.get().map { "$it\n" }.collect(Collectors.toList())
        val snapshot = MedLinkStatusParser.parseBolusSnapshot(lines, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val outcome = BolusProgressRule.decide(snapshot, detailedBolusInfo.insulin, baselineTime, baselineAmount, createdAt, now)
        aapsLogger.info(
            LTag.PUMPBTCOMM,
            "BOLUS progress requested=${detailedBolusInfo.insulin} baseline=$baselineAmount@$baselineTime outcome=${outcome.type} " +
                "delivered=${outcome.deliveredAmount} ${outcome.reason} raw=${snapshot?.raw}"
        )
        if (snapshot != null) {
            val status = medLinkPumpPlugin.medLinkPumpStatus
            status.lastBolusAmount = snapshot.lastBolusAmount
            status.lastBolusTime = Date(snapshot.lastBolusTime)
            status.bolusDeliveredAmount = snapshot.deliveredAmount ?: 0.0
        }

        when (outcome.type) {
            BolusOutcome.Type.DELIVERING                           ->
                rxBus.send(EventOverviewBolusProgress(resourceHelper, outcome.deliveredAmount))

            BolusOutcome.Type.DELIVERED, BolusOutcome.Type.PARTIAL -> if (!recorded) {
                recorded = true
                val record = detailedBolusInfo.copy().also {
                    it.insulin = outcome.deliveredAmount
                    it.timestamp = outcome.pumpBolusTime ?: it.timestamp
                    it.deliverAtTheLatest = it.timestamp
                }
                medLinkPumpPlugin.handleNewTreatmentData(record, false)
                rxBus.send(EventOverviewBolusProgress(resourceHelper, percent = 100))
                rxBus.send(EventDismissBolusProgressIfRunning(outcome.type == BolusOutcome.Type.DELIVERED, outcome.pumpBolusTime))
                resend = false
            }

            BolusOutcome.Type.NOT_STARTED, BolusOutcome.Type.UNKNOWN ->
                // never recorded here; MedLinkBLE / BolusTracker read M again and decide
                aapsLogger.info(LTag.PUMPBTCOMM, "BOLUS progress: not recording (${outcome.type})")
        }
        return super.apply(Supplier { lines.stream() })
    }
}
