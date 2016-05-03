package rsc.subscriber;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.*;

import rsc.flow.*;
import rsc.state.*;
import rsc.util.SubscriptionHelper;
import rsc.flow.Fuseable.*;

/**
 * A Subscriber/Subscription barrier that holds a single value at most and properly gates asynchronous behaviors
 * resulting from concurrent request or cancel and onXXX signals.
 *
 * @param <I> The upstream sequence type
 * @param <O> The downstream sequence type
 */
public class DeferredScalarSubscriber<I, O> implements Subscriber<I>, Completable, QueueSubscription<O>, Loopback, Cancellable,
                                                       Receiver, Producer {

    static final int SDS_NO_REQUEST_NO_VALUE   = 0;
    static final int SDS_NO_REQUEST_HAS_VALUE  = 1;
    static final int SDS_HAS_REQUEST_NO_VALUE  = 2;
    static final int SDS_HAS_REQUEST_HAS_VALUE = 3;

    protected final Subscriber<? super O> subscriber;

    protected O value;

    volatile int state;
    @SuppressWarnings("rawtypes")
    static final AtomicIntegerFieldUpdater<DeferredScalarSubscriber> STATE =
      AtomicIntegerFieldUpdater.newUpdater(DeferredScalarSubscriber.class, "state");

    protected boolean outputFused;
    
    public DeferredScalarSubscriber(Subscriber<? super O> subscriber) {
        this.subscriber = subscriber;
    }

    @Override
    public void request(long n) {
        if (SubscriptionHelper.validate(n)) {
            for (; ; ) {
                int s = getState();
                if (s == SDS_HAS_REQUEST_NO_VALUE || s == SDS_HAS_REQUEST_HAS_VALUE) {
                    return;
                }
                if (s == SDS_NO_REQUEST_HAS_VALUE) {
                    if (compareAndSetState(SDS_NO_REQUEST_HAS_VALUE, SDS_HAS_REQUEST_HAS_VALUE)) {
                        Subscriber<? super O> a = downstream();
                        a.onNext(value);
                        a.onComplete();
                    }
                    return;
                }
                if (compareAndSetState(SDS_NO_REQUEST_NO_VALUE, SDS_HAS_REQUEST_NO_VALUE)) {
                    return;
                }
            }
        }
    }

    @Override
    public void cancel() {
        setState(SDS_HAS_REQUEST_HAS_VALUE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onNext(I t) {
        value = (O) t;
    }

    @Override
    public void onError(Throwable t) {
        subscriber.onError(t);
    }

    @Override
    public void onSubscribe(Subscription s) {
        //if upstream
    }

    @Override
    public void onComplete() {
        subscriber.onComplete();
    }

    @Override
    public final boolean isCancelled() {
        return getState() == SDS_HAS_REQUEST_HAS_VALUE;
    }

    public final int getState() {
        return state;
    }

    public final void setState(int updated) {
        state = updated;
    }

    public final boolean compareAndSetState(int expected, int updated) {
        return STATE.compareAndSet(this, expected, updated);
    }

    @Override
    public final Subscriber<? super O> downstream() {
        return subscriber;
    }

    public void setValue(O value) {
        this.value = value;
    }

    /**
     * Tries to emit the value and complete the underlying subscriber or
     * stores the value away until there is a request for it.
     * <p>
     * Make sure this method is called at most once
     * @param value the value to emit
     */
    public final void complete(O value) {
        Objects.requireNonNull(value);
        for (; ; ) {
            int s = getState();
            if (s == SDS_NO_REQUEST_HAS_VALUE || s == SDS_HAS_REQUEST_HAS_VALUE) {
                return;
            }
            if (s == SDS_HAS_REQUEST_NO_VALUE) {
                if (outputFused) {
                    setValue(value); // make sure poll sees it
                }
                Subscriber<? super O> a = downstream();
                a.onNext(value);
                if (getState() != SDS_HAS_REQUEST_HAS_VALUE) {
                    a.onComplete();
                }
                return;
            }
            setValue(value);
            if (compareAndSetState(SDS_NO_REQUEST_NO_VALUE, SDS_NO_REQUEST_HAS_VALUE)) {
                return;
            }
        }
    }

    @Override
    public boolean isStarted() {
        return state != SDS_NO_REQUEST_NO_VALUE;
    }


    @Override
    public Object connectedOutput() {
        return value;
    }

    @Override
    public boolean isTerminated() {
        return isCancelled();
    }

    @Override
    public Object upstream() {
        return value;
    }
    
    @Override
    public int requestFusion(int requestedMode) {
        if ((requestedMode & Fuseable.ASYNC) != 0) {
            outputFused = true;
            return Fuseable.ASYNC;
        }
        return Fuseable.NONE;
    }
    
    @Override
    public O poll() {
        if (value != null) {
            if (outputFused) {
                // consume parent.value only once
                outputFused = false;
                return value;
            }
        }
        return null;
    }
    
    @Override
    public boolean isEmpty() {
        return !outputFused || value == null;
    }
    
    @Override
    public void clear() {
        outputFused = false;
        value = null;
    }
    
    @Override
    public int size() {
        return isEmpty() ? 0 : 1;
    }
}
