package reactivestreams.commons.publisher;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.error.UnsignalledExceptions;
import reactivestreams.commons.support.SubscriptionHelper;

/**
 * Emits a single item at most from the source.
 *
 * @param <T> the value type
 */
public final class PublisherNext<T> extends PublisherSource<T, T> {

    public PublisherNext(Publisher<? extends T> source) {
        super(source);
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new PublisherNextSubscriber<>(s));
    }

    static final class PublisherNextSubscriber<T>
            implements Subscriber<T>, Subscription, Upstream, ActiveUpstream,
                       Bounded, Downstream {

        final Subscriber<? super T> actual;

        Subscription s;

        boolean done;

        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherNextSubscriber> WIP =
                AtomicIntegerFieldUpdater.newUpdater(PublisherNextSubscriber.class, "wip");

        public PublisherNextSubscriber(Subscriber<? super T> actual) {
            this.actual = actual;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            if (done) {
                UnsignalledExceptions.onNextDropped(t);
                return;
            }

            s.cancel();
            actual.onNext(t);
            onComplete();
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                UnsignalledExceptions.onErrorDropped(t);
                return;
            }
            done = true;
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            actual.onComplete();
        }

        @Override
        public void request(long n) {
            if (WIP.compareAndSet(this, 0, 1)) {
                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void cancel() {
            s.cancel();
        }
        @Override
        public boolean isStarted() {
            return s != null && !done;
        }

        @Override
        public boolean isTerminated() {
            return done;
        }

        @Override
        public long getCapacity() {
            return 1L;
        }

        @Override
        public Object upstream() {
            return s;
        }

        @Override
        public Object downstream() {
            return actual;
        }
    }
}
