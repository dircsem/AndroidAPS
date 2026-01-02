package info.nightscout.androidaps.plugins.pump.common.hw.medlink.activities;

import java.util.function.Function;

/**
 * Created by Dirceu on 26/11/20.
 * For Medlink implementation
 */
public abstract class BaseCallback<B,A> implements Function<A, MedLinkStandardReturn<B>> {

    public BaseCallback(){
    }

    @Override public MedLinkStandardReturn<B> apply(A streamSupplier) {

        return null;
    }
}
