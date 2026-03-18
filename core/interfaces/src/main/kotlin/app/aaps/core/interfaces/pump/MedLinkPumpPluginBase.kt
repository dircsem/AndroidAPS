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
    fun calibrate(bg: Double)
    abstract fun setBatteryLevel(intExtra: Int)
    abstract fun getBatteryType(): String
    fun setLastCommunicationToNow()
    abstract fun handleBolusDelivered(lastBolusInfo: DetailedBolusInfo?)
    abstract fun isInitialized(): Boolean
    abstract fun setMedtronicPumpModel(model: String)
    fun postInit()
    fun reDeliverBolus(detailedBolusInfo: DetailedBolusInfo)
    fun setSuspended()

}