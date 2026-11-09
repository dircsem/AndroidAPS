package app.aaps.core.interfaces.pump

/**
 * Who asked to suspend the pump.
 * USER stops are sent immediately and may abort a running bolus on purpose.
 * AUTOMATIC stops (temp basal suspension, scheduled suspension, status repair) must never interrupt a bolus.
 */
enum class MedLinkStopOrigin { USER, AUTOMATIC }
