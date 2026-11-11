package app.aaps.pump.medtronic.data

import org.joda.time.LocalDateTime

data class StartStopDateTime(val startOperationTime: LocalDateTime, val endOperationTime: LocalDateTime)


/**
 * used by medlink
 */
/** @param firstOffsetMinutes 4x: the plan starts active for this long before the first suspension (0 = suspended now). */
class NextStartStop(private val firstOffsetMinutes: Int = 0) {

    lateinit var startStopDateTime: StartStopDateTime
    fun getNextStartStop(duration: Int, interval: Int): StartStopDateTime {
        startStopDateTime = if (!::startStopDateTime.isInitialized) {
            val startTime = LocalDateTime.now().plusMinutes(firstOffsetMinutes)
            StartStopDateTime(startTime, startTime.plusMinutes(duration))
        } else {
            var start = startStopDateTime.endOperationTime.plusMinutes(interval)
            StartStopDateTime(start, start.plusMinutes(duration))

        }
        return startStopDateTime
    }
}
