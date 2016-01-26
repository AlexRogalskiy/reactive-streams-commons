package reactivestreams.commons.subscriber;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.*;

import reactivestreams.commons.trait.*;
import reactivestreams.commons.util.SubscriptionHelper;

/**
 * A Subscriber/Subscription barrier that holds a single value at most and properly gates asynchronous behaviors
 * resulting from concurrent request or cancel and onXXX signals.
 *
 * @param <I> The upstream sequence type
 * @param <O> The downstream sequence type
 */
public class SubscriberDeferredScalar<I, O> implements Subscriber<I>, Completable, Subscription,
                                                       Connectable, Cancellable,
                                                       Subscribable {

    static final int SDS_NO_REQUEST_NO_VALUE   = 0;
    static final int SDS_NO_REQUEST_HAS_VALUE  = 1;
    static final int SDS_HAS_REQUEST_NO_VALUE  = 2;
    static final int SDS_HAS_REQUEST_HAS_VALUE = 3;

    protected final Subscriber<? super O> subscriber;

    protected O value;

    volatile int state;
    @SuppressWarnings("rawtypes")
    static final AtomicIntegerFieldUpdater<SubscriberDeferredScalar> STATE =
      AtomicIntegerFieldUpdater.newUpdater(SubscriberDeferredScalar.class, "state");

    public SubscriberDeferredScalar(Subscriber<? super O> subscriber) {
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
                if (compareAndSetState(SDS_HAS_REQUEST_NO_VALUE, SDS_HAS_REQUEST_HAS_VALUE)) {
                    Subscriber<? super O> a = downstream();
                    a.onNext(value);
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
    public Object connectedInput() {
        return null;
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
}
