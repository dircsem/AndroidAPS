package app.aaps.pump.common.hw.medlink.defs

enum class MedLinkPumpRunningState {
    Unknow,
    Running,
    Suspended,

    /** `A` answered "pump is bolusing state": neither running nor suspended, a bolus is being delivered. */
    Bolusing
}