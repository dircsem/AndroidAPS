package app.aaps.pump.common.hw.medlink.bolus

/**
 * Pump state text in the `A` answer, as captured in the field logs (2024 and 2026):
 * `pump normal state`, `pump suspend state`, `pump is bolusing state`.
 * BLE chunks are at most 20 bytes, so the bolusing answer usually arrives as "pump is bolusing sta" + "te".
 */
object MedLinkPumpStateAnswer {

    /** "pump bolusing state" never appears in a real log; it was only in synthetic fixtures. */
    private const val BOLUSING = "pump is bolusing st"

    fun isBolusing(answer: String): Boolean = answer.contains(BOLUSING)
}
