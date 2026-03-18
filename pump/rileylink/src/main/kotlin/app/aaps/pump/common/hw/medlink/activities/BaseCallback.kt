package app.aaps.pump.common.hw.medlink.activities

import java.util.function.Function

/**
 * Created by Dirceu on 26/11/20.
 * For Medlink implementation
 */
abstract class BaseCallback<B, A> : Function<A, MedLinkStandardReturn<B>> {
    abstract override fun apply(answer: A): MedLinkStandardReturn<B>
}
