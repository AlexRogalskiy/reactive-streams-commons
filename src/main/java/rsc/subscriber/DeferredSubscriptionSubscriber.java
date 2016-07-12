package rsc.subscriber;

import java.util.Objects;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import rsc.flow.Producer;

/**
 * Arbitrates the requests and cancellation for a Subscription that may be set onSubscribe once only.
 * <p>
 * Note that {@link #request(long)} doesn't validate the amount.
 * 
 * @param <I> the input value type
 * @param <O> the output value type
 */
public class DeferredSubscriptionSubscriber<I, O>
        extends DeferredSubscription
implements Subscription, Subscriber<I>, Producer {

    protected final Subscriber<? super O> subscriber;

    /**
     * Constructs a SingleSubscriptionArbiter with zero initial request.
     * 
     * @param subscriber the actual subscriber
     */
    public DeferredSubscriptionSubscriber(Subscriber<? super O> subscriber) {
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
    }

    /**
     * Constructs a SingleSubscriptionArbiter with the specified initial request amount.
     *
     * @param subscriber the actual subscriber
     * @param initialRequest
     * @throws IllegalArgumentException if initialRequest is negative
     */
    public DeferredSubscriptionSubscriber(Subscriber<? super O> subscriber, long initialRequest) {
        if (initialRequest < 0) {
            throw new IllegalArgumentException("initialRequest >= required but it was " + initialRequest);
        }
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
        setInitialRequest(initialRequest);
    }

    @Override
    public final Subscriber<? super O> downstream() {
        return subscriber;
    }

    @Override
    public void onSubscribe(Subscription s) {
        set(s);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onNext(I t) {
        subscriber.onNext((O) t);
    }

    @Override
    public void onError(Throwable t) {
        subscriber.onError(t);
    }

    @Override
    public void onComplete() {
        subscriber.onComplete();
    }
}
