package app.aaps.pump.common.hw.medlink.activities

import java.util.function.Supplier
import java.util.stream.Stream

/**
 * Created by Dirceu on 05/12/20.
 */
open class BaseStringAggregatorCallback : BaseCallback<String, Supplier<Stream<String>>>() {
    //    @Override public String apply(String a) {
    //        super.aapsLogger.info(LTag.PUMPBTCOMM,"Applying BaseStringAggregator");
    //        return functions.stream().reduce(a, (f, element) -> element.apply(f), (left, right) -> left + "\n" + right);
    //
    //    }
    override fun apply(answer: Supplier<Stream<String>>): MedLinkStandardReturn<String> {
        return MedLinkStandardReturn<String>(answer, answer.toString())

        //                reduce(a, (f, element) -> element.apply(f), (left, right) -> left +"\n"+ right);
    }
}
