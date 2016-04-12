package rsc.util;

import org.reactivestreams.Subscriber;
import rsc.flow.Fuseable;
import rsc.state.Introspectable;

/**
 * A singleton enumeration that represents a no-op Subscription instance that 
 * can be freely given out to clients.
 * <p>
 * The enum also implements Fuseable.QueueSubscription so operators expecting a
 * QueueSubscription from a Fuseable source don't have to double-check their Subscription
 * received in onSubscribe.
 */
public enum EmptySubscription implements Fuseable.QueueSubscription<Object>, Introspectable {
    INSTANCE;

    @Override
    public void request(long n) {
        // deliberately no op
    }

    @Override
    public void cancel() {
        // deliberately no op
    }

    /**
     * Calls onSubscribe on the target Subscriber with the empty instance followed by a call to onError with the
     * supplied error.
     *
     * @param s
     * @param e
     */
    public static void error(Subscriber<?> s, Throwable e) {
        s.onSubscribe(INSTANCE);
        s.onError(e);
    }

    /**
     * Calls onSubscribe on the target Subscriber with the empty instance followed by a call to onComplete.
     *
     * @param s
     */
    public static void complete(Subscriber<?> s) {
        s.onSubscribe(INSTANCE);
        s.onComplete();
    }

    @Override
    public int getMode() {
        return TRACE_ONLY;
    }

    @Override
    public String getName() {
        return INSTANCE.name();
    }

    @Override
    public Object poll() {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public void clear() {
        // deliberately no op
    }

    @Override
    public int requestFusion(int requestedMode) {
        return Fuseable.NONE; // can't enable fusion due to complete/error possibility
    }
}
