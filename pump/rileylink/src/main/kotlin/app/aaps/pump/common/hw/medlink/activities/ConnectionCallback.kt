package app.aaps.pump.common.hw.medlink.activities

import app.aaps.pump.common.hw.medlink.PumpResponses
import java.util.Locale
import java.util.function.Supplier
import java.util.stream.Stream

/**
 * Created by Dirceu on 22/12/20.
 */
class ConnectionCallback : BaseStringAggregatorCallback() {
    override fun apply(answers: Supplier<Stream<String>>): MedLinkStandardReturn<String> {
        if (answers.get().anyMatch { f: String? ->
                f!!.lowercase(Locale.getDefault()).contains("confirmed pump wakeup")
            }) {
            val filtered = answers.get()
                .filter { f: String? -> f!!.lowercase(Locale.getDefault()).contains("medtronic") }
                .findFirst()
            if (filtered.isPresent) {
                return MedLinkStandardReturn(answers, filtered.get())
            }
        }

        return MedLinkStandardReturn<String>(
            answers,
            PumpResponses.UnknownAnswer.answer,
            MedLinkStandardReturn.ParsingError.ConnectionParsingError
        )
    }
}

