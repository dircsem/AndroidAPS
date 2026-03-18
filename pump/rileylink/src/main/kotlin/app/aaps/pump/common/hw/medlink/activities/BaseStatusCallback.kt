package app.aaps.pump.common.hw.medlink.activities

import java.util.function.Supplier
import java.util.stream.Stream

/**
 * Created by Dirceu on 21/12/20.
 */
abstract class BaseStatusCallback<A> //    private MedLinkStatusParser parser = new MedLinkStatusParser();
//    @Override public MedlinkStandardReturn<MedLinkPumpStatus> apply(Supplier<Stream<String>> s)
    (val pumpStatus: A) : BaseCallback<A, Supplier<Stream<String>>>() {
    //        {
    //
    //
    //            //TODO fix error handling
    //            MedLinkPumpStatus resultStatus = parser.parseStatus(s.get().toArray(String[]::new), pumpStatus);
    //            return new MedlinkStandardReturn<>(s, resultStatus);
    /**            if(parser.fullMatch(stats))
    {
        * /                return PumpResponses.StatusFullProcessed.getAnswer();
        * /
    }else if(parser.partialMatch(stats))
    {
        * /                return PumpResponses.StatusPartialProcessed.getAnswer();
        * /
    }else
    {
        * /                return PumpResponses.StatusProcessFailed.getAnswer()+s;
        * /
    } */
    //
    //        }
}
