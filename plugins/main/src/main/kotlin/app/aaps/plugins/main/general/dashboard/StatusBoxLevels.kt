package app.aaps.plugins.main.general.dashboard

import app.aaps.core.ui.compose.StatusLevel

/**
 * Color decision of the dashboard status boxes, shared by every skin (circle-top, Glass) so they cannot drift apart
 * (B5 / T14). Plain functions: unit tested in `StatusBoxLevelsTest`.
 */

/**
 * The worse of two status levels: critical, then warning, then normal. UNSPECIFIED (no data, for example no battery
 * change event) only when neither one is known.
 */
fun worseStatusLevel(a: StatusLevel?, b: StatusLevel?): StatusLevel? = when {
    a == StatusLevel.CRITICAL || b == StatusLevel.CRITICAL -> StatusLevel.CRITICAL
    a == StatusLevel.WARNING || b == StatusLevel.WARNING   -> StatusLevel.WARNING
    a == StatusLevel.NORMAL || b == StatusLevel.NORMAL     -> StatusLevel.NORMAL
    else                                                     -> a ?: b
}

/**
 * Text and color of one status box.
 *
 * @param text the value shown in the box
 * @param level the color of the box (warning / critical as the AAPS status lights)
 */
data class StatusBoxValue(val text: String, val level: StatusLevel)

/**
 * A box that can show an age and a level (pump battery): the level when the pump gives one, else the age.
 * The color is the worse of the two, so an old battery with a good level is still colored.
 *
 * @param age age text ("-" when there is no change event)
 * @param ageStatus status of the age (hours since the last change)
 * @param level level text, null when the pump gives no level (or it is not shown, for example MedLink "battery info" = age)
 * @param levelStatus status of the level
 */
fun levelOrAgeBox(age: String, ageStatus: StatusLevel, level: String?, levelStatus: StatusLevel): StatusBoxValue =
    StatusBoxValue(
        text = level ?: age,
        level = worseStatusLevel(ageStatus, levelStatus) ?: StatusLevel.UNSPECIFIED
    )
