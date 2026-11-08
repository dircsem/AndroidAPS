package app.aaps.pump.common.hw.medlink.bolus

import java.text.SimpleDateFormat
import java.util.Locale

object Fixtures {

    fun lines(name: String): List<String> =
        requireNotNull(javaClass.classLoader!!.getResource("medlink/$name")) { "missing fixture $name" }
            .readText()
            .split("\n").map { "$it\n" }

    /** Pump time as the parser builds it (device default time zone, minute resolution). */
    fun pumpTime(text: String): Long =
        SimpleDateFormat("dd-MM-yy HH:mm", Locale.getDefault()).parse(text)!!.time

    fun snapshot(
        amount: Double,
        time: Long,
        square: Double? = 0.0,
        delivered: Double? = 0.0,
        bolusing: Boolean = false,
        readAt: Long = time + 60_000
    ) = BolusStatusSnapshot(
        lastBolusAmount = amount,
        lastBolusTime = time,
        squareBolusAmount = square,
        deliveredAmount = delivered,
        pumpBolusing = bolusing,
        readAt = readAt,
        raw = "test"
    )
}
