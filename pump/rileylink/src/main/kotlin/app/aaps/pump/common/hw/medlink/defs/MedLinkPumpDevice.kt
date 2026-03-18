package app.aaps.pump.common.hw.medlink.defs

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.pump.common.hw.medlink.service.MedLinkService
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkPumpInfo
import org.json.JSONObject
import java.util.stream.Stream

/**
 * Created by Dirceu on 30/09/20.
 */
interface MedLinkPumpDevice  {
    val medLinkPumpStatus: MedLinkPumpStatus
    fun handleNewTreatmentData(bolusInfo: DetailedBolusInfo, history: Boolean = false)
    fun handleNewTreatmentData(bolusInfo: Stream<JSONObject>, history: Boolean = false)
    fun getPumpInfo(): RileyLinkPumpInfo?
    val medLinkService: MedLinkService?

    fun getBatteryInfoConfig(): String?
    fun setBusy(busy: Boolean)
    fun triggerPumpConfigurationChangedEvent()

    // var medLinkService: MedLinkService?
    val lastConnectionTimeMillis: Long
    fun setLastCommunicationToNow()
    fun extendBasalTreatment(duration: Int, callback: Function1<PumpEnactResult, *>): PumpEnactResult
    fun nextScheduledCommand(): String? //    void deliverTreatment(DetailedBolusInfo detailedBolusInfo,
    //                          @NotNull Function<PumpEnactResult, Unit> func);





}