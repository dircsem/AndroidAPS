package app.aaps.pump.common.hw.medlink.bolus

/** What a bolus waits for before the pump starts delivering it: shown by the progress dialog of a manual bolus. */
enum class BolusPhase {

    /** Connecting to the MedLink. */
    CONNECTING,

    /** Reading `M` before the bolus (baseline). */
    READING_STATUS,

    /** Writing the bolus command (one try). */
    SENDING,

    /** The MedLink confirmed the command; the pump has not started yet. */
    CONFIRMED,

    /** The pump is delivering another bolus; this one waits for it to end. */
    WAITING_OTHER_BOLUS
}
