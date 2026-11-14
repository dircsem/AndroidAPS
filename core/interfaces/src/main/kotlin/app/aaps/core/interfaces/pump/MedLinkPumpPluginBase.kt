package app.aaps.core.interfaces.pump

import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences

interface MedLinkPumpPluginBase {

    val preferences: Preferences
    val medLinkPumpStatus: MedLinkPumpStatus
//    abstract val lastBolusTime: Date
    abstract val lastConnection: Long
//    abstract val pumpStatusData: MedLinkPumpStatus
    abstract val serviceClass: Class<*>?
    val rh: ResourceHelper
    abstract val uiInteraction: UiInteraction
    val pumpSync: PumpSync

    // override fun onStart() {
    //     super.onStart()
    //     if (getType() == PluginType.PUMP) {
    //         Thread {
    //             SystemClock.sleep(3000)
    //             commandQueue.readStatus(rh.gs(R.string.pump_driver_changed), null)
    //         }.start()
    //     }
    // }

  fun setTempBasalPercent(percent: Int,
                          durationInMinutes: Int,
                          profile: Profile,
                          enforceNew: Boolean,
                          func: Function1<PumpEnactResult, *>): PumpEnactResult

  fun setTempBasalAbsolute(
      absoluteRate: Double,
      durationInMinutes: Int,
      profile: Profile,
      enforceNew: Boolean,
      callback: Function1<PumpEnactResult, *>
    )
 fun cancelTempBasal(isStartCommand:Boolean, enforceNew: Boolean, callback: Callback?): PumpEnactResult

  fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo, func: (PumpEnactResult) -> Unit)
    fun calibrate(calibrationInfo: Double)
    abstract fun setBatteryLevel(intExtra: Int)
    abstract fun getBatteryType(): String
    fun setLastCommunicationToNow()
    abstract fun handleBolusDelivered(lastBolusInfo: DetailedBolusInfo?)
    abstract fun isInitialized(): Boolean
    abstract fun setMedtronicPumpModel(model: String)
    fun postInit()
    fun reDeliverBolus(detailedBolusInfo: DetailedBolusInfo)
    fun setSuspended()

    /** T0: suspend requested by the user. Sent immediately, aborts a running bolus on purpose. */
    fun userSuspend(callback: Callback?)

    /**
     * The running MedLink temp basal (plan of microboluses and suspensions), for the TBR chip on the main screen.
     * A MedLink temp basal above 100 % is not stored as a temp basal in the database, so the chip asks here.
     * Null when no plan is running.
     */
    fun runningTempBasal(now: Long): MedLinkRunningTempBasal? = null

    /**
     * MedLink setting "Main screen TBR: minutes": the main screen shows as TBR the basal delivered in this many
     * minutes, calculated from the database (temp basals / suspensions + TBR microboluses).
     */
    fun tbrDisplayMinutes(): Int = 30
}

/**
 * @param percent what the plan delivers from now to its end, in % of the pump base basal (100 = no change)
 * @param start start of the temp basal (ms)
 * @param duration length of the temp basal (ms)
 * @param rate the same as [percent], in U/h (pump base basal * percent / 100)
 */
data class MedLinkRunningTempBasal(val percent: Int, val start: Long, val duration: Long, val rate: Double)