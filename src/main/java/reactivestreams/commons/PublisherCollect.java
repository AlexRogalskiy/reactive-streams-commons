package reactivestreams.commons;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.internal.subscriber.SubscriberScalarDelayed;
import reactivestreams.commons.internal.support.SubscriptionHelper;
import reactivestreams.commons.internal.subscription.EmptySubscription;

/**
 * Collects the values of the source sequence into a container returned by
 * a supplier and a collector action working on the container and the current source
 * value.
 *
 * @param <T> the source value type
 * @param <R> the container value type
 */
public final class PublisherCollect<T, R> implements Publisher<R> {

    final Publisher<? extends T> source;
    
    final Supplier<R> supplier;
    
    final BiConsumer<? super R, ? super T> action;

    public PublisherCollect(Publisher<? extends T> source, Supplier<R> supplier,
            BiConsumer<? super R, ? super T> action) {
        this.source = Objects.requireNonNull(source, "source");
        this.supplier = Objects.requireNonNull(supplier, "supplier");
        this.action = Objects.requireNonNull(action);
    }
    
    @Override
    public void subscribe(Subscriber<? super R> s) {
        R container;
        
        try {
            container = supplier.get();
        } catch (Throwable e) {
            EmptySubscription.error(s, e);
            return;
        }
        
        if (container == null) {
            EmptySubscription.error(s, new NullPointerException("The supplier returned a null container"));
            return;
        }
        
        source.subscribe(new PublisherCollectSubscriber<>(s, action, container));
    }
    
    static final class PublisherCollectSubscriber<T, R>
            extends SubscriberScalarDelayed<T, R> {

        final BiConsumer<? super R, ? super T> action;
    
        final R container;
        
        Subscription s;
        
        boolean done;

        public PublisherCollectSubscriber(Subscriber<? super R> actual, BiConsumer<? super R, ? super T> action,
                R container) {
            super(actual);
            this.action = action;
            this.container = container;
        }

        @Override
        public void cancel() {
            super.cancel();
            s.cancel();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                
                subscriber.onSubscribe(this);
                
                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(T t) {
            if (done) {
                return;
            }
            
            try {
                action.accept(container, t);
            } catch (Throwable e) {
                cancel();
                
                onError(e);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                return;
            }
            done = true;
            subscriber.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            sdsSet(container);
        }

        @Override
        public R sdsGetValue() {
            return container;
        }

        @Override
        public void sdsSetValue(R value) {
            // value is constant
        }
    }
}
