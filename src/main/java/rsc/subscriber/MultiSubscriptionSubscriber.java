package rsc.subscriber;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import rsc.flow.Producer;
import rsc.flow.Receiver;
import rsc.flow.Trackable;
import rsc.util.BackpressureHelper;

/**
 * A subscription implementation that arbitrates request amounts between subsequent Subscriptions, including the
 * duration until the first Subscription is set.
 * <p>
 * The class is thread safe but switching Subscriptions should happen only when the source associated with the current
 * Subscription has finished emitting values. Otherwise, two sources may emit for one request.
 * <p>
 * You should call {@link #produced(long)} or {@link #producedOne()} after each element has been delivered to properly
 * account the outstanding request amount in case a Subscription switch happens.
 * 
 * @param <I> the input value type
 * @param <O> the output value type
 */
public abstract class MultiSubscriptionSubscriber<I, O> implements Subscription, Subscriber<I>, Producer,
                                                                   Trackable,
                                                                   Receiver {

    protected final Subscriber<? super O> subscriber;

    /**
     * The current subscription which may null if no Subscriptions have been set.
     */
    Subscription actual;

    /**
     * The current outstanding request amount.
     */
    long requested;

    volatile Subscription missedSubscription;
    @SuppressWarnings("rawtypes")
    static final AtomicReferenceFieldUpdater<MultiSubscriptionSubscriber, Subscription> MISSED_SUBSCRIPTION =
      AtomicReferenceFieldUpdater.newUpdater(MultiSubscriptionSubscriber.class,
        Subscription.class,
        "missedSubscription");

    volatile long missedRequested;
    @SuppressWarnings("rawtypes")
    static final AtomicLongFieldUpdater<MultiSubscriptionSubscriber> MISSED_REQUESTED =
      AtomicLongFieldUpdater.newUpdater(MultiSubscriptionSubscriber.class, "missedRequested");

    volatile long missedProduced;
    @SuppressWarnings("rawtypes")
    static final AtomicLongFieldUpdater<MultiSubscriptionSubscriber> MISSED_PRODUCED =
      AtomicLongFieldUpdater.newUpdater(MultiSubscriptionSubscriber.class, "missedProduced");

    volatile int wip;
    @SuppressWarnings("rawtypes")
    static final AtomicIntegerFieldUpdater<MultiSubscriptionSubscriber> WIP =
      AtomicIntegerFieldUpdater.newUpdater(MultiSubscriptionSubscriber.class, "wip");

    volatile boolean cancelled;

    protected boolean unbounded;
    
    public MultiSubscriptionSubscriber(Subscriber<? super O> subscriber) {
        this.subscriber = subscriber;
    }

    @Override
    public void onSubscribe(Subscription s) {
        set(s);
    }

    /**
     * When setting a new subscription via set(), should
     * the previous subscription be cancelled?
     * @return true if cancellation is needed
     */
    protected boolean shouldCancelCurrent() {
        return false;
    }
    
    @Override
    public void onError(Throwable t) {
        subscriber.onError(t);
    }

    @Override
    public void onComplete() {
        subscriber.onComplete();
    }

    public final void set(Subscription s) {
        if (cancelled) {
            s.cancel();
            return;
        }

        Objects.requireNonNull(s);
        
        if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
            Subscription a = actual;
            
            if (a != null && shouldCancelCurrent()) {
                a.cancel();
            }
            
            actual = s;
            
            long r = requested;
            if (r != 0L) {
                s.request(r);
            }
            
            if (WIP.decrementAndGet(this) == 0) {
                return;
            }

            drainLoop();

            return;
        }

        Subscription a = MISSED_SUBSCRIPTION.getAndSet(this, s);
        if (a != null && shouldCancelCurrent()) {
            a.cancel();
        }
        drain();
    }

    @Override
    public final void request(long n) {
        if (SubscriptionHelper.validate(n)) {
            if (unbounded) {
                return;
            }
            if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
                long r = requested;

                if (r != Long.MAX_VALUE) {
                    r = BackpressureHelper.addCap(r, n);
                    requested = r;
                    if (r == Long.MAX_VALUE) {
                        unbounded = true;
                    }
                }
                Subscription a = actual;
                if (a != null) {
                    a.request(n);
                }

                if (WIP.decrementAndGet(this) == 0) {
                    return;
                }

                drainLoop();

                return;
            }

            BackpressureHelper.getAndAddCap(MISSED_REQUESTED, this, n);

            drain();
        }
    }

    public final void producedOne() {
        if (unbounded) {
            return;
        }
        if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
            long r = requested;

            if (r != Long.MAX_VALUE) {
                r--;
                if (r < 0L) {
                    SubscriptionHelper.reportMoreProduced();
                    r = 0;
                }
                requested = r;
            } else {
                unbounded = true;
            }

            if (WIP.decrementAndGet(this) == 0) {
                return;
            }

            drainLoop();

            return;
        }

        BackpressureHelper.getAndAddCap(MISSED_PRODUCED, this, 1L);

        drain();
    }

    public final void produced(long n) {
        if (unbounded) {
            return;
        }
        if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
            long r = requested;

            if (r != Long.MAX_VALUE) {
                long u = r - n;
                if (u < 0L) {
                    SubscriptionHelper.reportMoreProduced();
                    u = 0;
                }
                requested = u;
            } else {
                unbounded = true;
            }

            if (WIP.decrementAndGet(this) == 0) {
                return;
            }

            drainLoop();

            return;
        }

        BackpressureHelper.getAndAddCap(MISSED_PRODUCED, this, n);

        drain();
    }

    @Override
    public void cancel() {
        if (!cancelled) {
            cancelled = true;

            drain();
        }
    }

    @Override
    public final boolean isCancelled() {
        return cancelled;
    }

    final void drain() {
        if (WIP.getAndIncrement(this) != 0) {
            return;
        }
        drainLoop();
    }

    final void drainLoop() {
        int missed = 1;

        for (; ; ) {

            Subscription ms = missedSubscription;

            if (ms != null) {
                ms = MISSED_SUBSCRIPTION.getAndSet(this, null);
            }

            long mr = missedRequested;
            if (mr != 0L) {
                mr = MISSED_REQUESTED.getAndSet(this, 0L);
            }

            long mp = missedProduced;
            if (mp != 0L) {
                mp = MISSED_PRODUCED.getAndSet(this, 0L);
            }

            Subscription a = actual;

            if (cancelled) {
                if (a != null) {
                    a.cancel();
                    actual = null;
                }
                if (ms != null) {
                    ms.cancel();
                }
            } else {
                long r = requested;
                if (r != Long.MAX_VALUE) {
                    long u = BackpressureHelper.addCap(r, mr);

                    if (u != Long.MAX_VALUE) {
                        long v = u - mp;
                        if (v < 0L) {
                            SubscriptionHelper.reportMoreProduced();
                            v = 0;
                        }
                        r = v;
                    } else {
                        r = u;
                    }
                    requested = r;
                }

                if (ms != null) {
                    if (a != null && shouldCancelCurrent()) {
                        a.cancel();
                    }
                    actual = ms;
                    if (r != 0L) {
                        ms.request(r);
                    }
                } else if (mr != 0L && a != null) {
                    a.request(mr);
                }
            }

            missed = WIP.addAndGet(this, -missed);
            if (missed == 0) {
                return;
            }
        }
    }

    @Override
    public final Subscriber<? super O> downstream() {
        return subscriber;
    }

    @Override
    public final Subscription upstream() {
        return actual != null ? actual : missedSubscription;
    }

    @Override
    public final long requestedFromDownstream() {
        return requested + missedRequested;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean isStarted() {
        return upstream() != null;
    }
    
    public final boolean isUnbounded() {
        return unbounded;
    }
}
