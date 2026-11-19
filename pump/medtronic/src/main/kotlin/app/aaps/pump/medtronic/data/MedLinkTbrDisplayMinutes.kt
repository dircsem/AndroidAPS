package app.aaps.pump.medtronic.data

/**
 * T31: the setting "Main screen TBR: minutes" is used by the basal pill and by the basal graph blocks. Blocks start at
 * local :00, so only values that divide 60, or are whole hours, are allowed. An old saved value (the setting was a free
 * number from 5 to 120) is read as the nearest allowed value below it, so the pill and the graph always agree.
 */
object MedLinkTbrDisplayMinutes {

    val ALLOWED = listOf(5, 10, 15, 30, 60, 120)

    /** 20 -> 15, 45 -> 30, 90 -> 60, 4 -> 5, 200 -> 120. */
    fun snap(saved: Int): Int = ALLOWED.lastOrNull { it <= saved } ?: ALLOWED.first()
}
