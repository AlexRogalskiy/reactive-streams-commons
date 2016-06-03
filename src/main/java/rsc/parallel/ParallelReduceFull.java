package rsc.parallel;

import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;

import org.reactivestreams.*;

import rsc.flow.Fuseable;
import rsc.publisher.Px;
import rsc.subscriber.DeferredScalarSubscriber;
import rsc.util.*;

/**
 * Reduces all 'rails' into a single value which then gets reduced into a single
 * Publisher sequence.
 *
 * @param <T> the value type
 */
public final class ParallelReduceFull<T> extends Px<T> implements Fuseable {

    final ParallelPublisher<? extends T> source;
    
    final BiFunction<T, T, T> reducer;
    
    public ParallelReduceFull(ParallelPublisher<? extends T> source, BiFunction<T, T, T> reducer) {
        this.source = source;
        this.reducer = reducer;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        ParallelReduceFullMainSubscriber<T> parent = new ParallelReduceFullMainSubscriber<>(s, source.parallelism(), reducer);
        s.onSubscribe(parent);
        
        source.subscribe(parent.subscribers);
    }
    
    static final class ParallelReduceFullMainSubscriber<T> extends DeferredScalarSubscriber<T, T> {

        final ParallelReduceFullInnerSubscriber<T>[] subscribers;
        
        final BiFunction<T, T, T> reducer;
        
        volatile SlotPair<T> current;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<ParallelReduceFullMainSubscriber, SlotPair> CURRENT =
                AtomicReferenceFieldUpdater.newUpdater(ParallelReduceFullMainSubscriber.class, SlotPair.class, "current");
        
        volatile int remaining;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<ParallelReduceFullMainSubscriber> REMAINING =
                AtomicIntegerFieldUpdater.newUpdater(ParallelReduceFullMainSubscriber.class, "remaining");

        volatile int errorOnce;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<ParallelReduceFullMainSubscriber> ERROR_ONCE =
                AtomicIntegerFieldUpdater.newUpdater(ParallelReduceFullMainSubscriber.class, "errorOnce");
        
        
        public ParallelReduceFullMainSubscriber(Subscriber<? super T> subscriber, int n, BiFunction<T, T, T> reducer) {
            super(subscriber);
            @SuppressWarnings("unchecked")
            ParallelReduceFullInnerSubscriber<T>[] a = new ParallelReduceFullInnerSubscriber[n];
            for (int i = 0; i < n; i++) {
                a[i] = new ParallelReduceFullInnerSubscriber<>(this, reducer);
            }
            this.subscribers = a;
            this.reducer = reducer;
            REMAINING.lazySet(this, n);
        }

        SlotPair<T> addValue(T value) {
            for (;;) {
                SlotPair<T> curr = current;
                
                if (curr == null) {
                    curr = new SlotPair<>();
                    if (!CURRENT.compareAndSet(this, null, curr)) {
                        continue;
                    }
                }
                
                int c = curr.tryAcquireSlot();
                if (c < 0) {
                    CURRENT.compareAndSet(this, curr, null);
                    continue;
                }
                if (c == 0) {
                    curr.first = value;
                } else {
                    curr.second = value;
                }
                
                if (curr.releaseSlot()) {
                    CURRENT.compareAndSet(this, curr, null);
                    return curr;
                }
                return null;
            }
        }
        
        @Override
        public void cancel() {
            for (ParallelReduceFullInnerSubscriber<T> inner : subscribers) {
                inner.cancel();
            }
        }
        
        void innerError(Throwable ex) {
            if (ERROR_ONCE.compareAndSet(this, 0, 1)) {
                cancel();
                subscriber.onError(ex);
            } else {
                UnsignalledExceptions.onErrorDropped(ex);
            }
        }
        
        void innerComplete(T value) {
            if (value != null) {
                for (;;) {
                    SlotPair<T> sp = addValue(value);
                    
                    if (sp != null) {
                        
                        try {
                            value = reducer.apply(sp.first, sp.second);
                        } catch (Throwable ex) {
                            ExceptionHelper.throwIfFatal(ex);
                            innerError(ex);
                            return;
                        }
                        
                        if (value == null) {
                            innerError(new NullPointerException("The reducer returned a null value"));
                            return;
                        }
                    } else {
                        break;
                    }
                }
            }
            
            if (REMAINING.decrementAndGet(this) == 0) {
                SlotPair<T> sp = current;
                CURRENT.lazySet(this, null);
                
                if (sp != null) {
                    complete(sp.first);
                } else {
                    subscriber.onComplete();
                }
            }
        }
    }
    
    static final class ParallelReduceFullInnerSubscriber<T> implements Subscriber<T> {
        final ParallelReduceFullMainSubscriber<T> parent;
        
        final BiFunction<T, T, T> reducer;
        
        volatile Subscription s;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<ParallelReduceFullInnerSubscriber, Subscription> S =
                AtomicReferenceFieldUpdater.newUpdater(ParallelReduceFullInnerSubscriber.class, Subscription.class, "s");
        
        T value;
        
        boolean done;

        public ParallelReduceFullInnerSubscriber(ParallelReduceFullMainSubscriber<T> parent, BiFunction<T, T, T> reducer) {
            this.parent = parent;
            this.reducer = reducer;
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.setOnce(S, this, s)) {
                s.request(Long.MAX_VALUE);
            }
        }
        
        @Override
        public void onNext(T t) {
            if (done) {
                return;
            }
            T v = value;
            
            if (v == null) {
                value = t;
            } else {
                
                try {
                    v = reducer.apply(v, t);
                } catch (Throwable ex) {
                    ExceptionHelper.throwIfFatal(ex);
                    s.cancel();
                    onError(ex);
                    return;
                }
                
                if (v == null) {
                    s.cancel();
                    onError(new NullPointerException("The reducer returned a null value"));
                    return;
                }
                
                value = v;
            }
        }
        
        @Override
        public void onError(Throwable t) {
            if (done) {
                UnsignalledExceptions.onErrorDropped(t);
                return;
            }
            done = true;
            parent.innerError(t);
        }
        
        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            parent.innerComplete(value);
        }
        
        void cancel() {
            SubscriptionHelper.terminate(S, this);
        }
    }
    
    static final class SlotPair<T> {
        
        T first;
        
        T second;
        
        volatile int acquireIndex;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<SlotPair> ACQ =
                AtomicIntegerFieldUpdater.newUpdater(SlotPair.class, "acquireIndex");
        
        
        volatile int releaseIndex;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<SlotPair> REL =
                AtomicIntegerFieldUpdater.newUpdater(SlotPair.class, "releaseIndex");
        
        int tryAcquireSlot() {
            for (;;) {
                int acquired = acquireIndex;
                if (acquired >= 2) {
                    return -1;
                }
                
                if (ACQ.compareAndSet(this, acquired, acquired + 1)) {
                    return acquired;
                }
            }
        }
        
        boolean releaseSlot() {
            return REL.incrementAndGet(this) == 2;
        }
    }
}
