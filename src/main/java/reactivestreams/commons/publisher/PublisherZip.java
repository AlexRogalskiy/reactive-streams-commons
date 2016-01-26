/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactivestreams.commons.publisher;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.graph.PublishableMany;
import reactivestreams.commons.graph.Subscribable;
import reactivestreams.commons.state.Backpressurable;
import reactivestreams.commons.state.Cancellable;
import reactivestreams.commons.state.Completable;
import reactivestreams.commons.state.Failurable;
import reactivestreams.commons.state.Introspectable;
import reactivestreams.commons.state.Prefetchable;
import reactivestreams.commons.state.Requestable;
import reactivestreams.commons.subscriber.SubscriberDeferredScalar;
import reactivestreams.commons.util.BackpressureHelper;
import reactivestreams.commons.util.CancelledSubscription;
import reactivestreams.commons.util.EmptySubscription;
import reactivestreams.commons.util.ExceptionHelper;
import reactivestreams.commons.util.SubscriptionHelper;
import reactivestreams.commons.util.SynchronousSource;
import reactivestreams.commons.util.UnsignalledExceptions;

/**
 * Repeatedly takes one item from all source Publishers and 
 * runs it through a function to produce the output item.
 *
 * @param <T> the common input type
 * @param <R> the output value type
 */
public final class PublisherZip<T, R> extends PublisherBase<R> implements Introspectable, PublishableMany {

    final Publisher<? extends T>[] sources;
    
    final Iterable<? extends Publisher<? extends T>> sourcesIterable;
    
    final Function<? super Object[], ? extends R> zipper;
    
    final Supplier<? extends Queue<T>> queueSupplier;
    
    final int prefetch;

    public PublisherZip(Publisher<? extends T>[] sources,
            Function<? super Object[], ? extends R> zipper, Supplier<? extends Queue<T>> queueSupplier, int prefetch) {
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        this.sources = Objects.requireNonNull(sources, "sources");
        this.sourcesIterable = null;
        this.zipper = Objects.requireNonNull(zipper, "zipper");
        this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
        this.prefetch = prefetch;
    }
    
    public PublisherZip(Iterable<? extends Publisher<? extends T>> sourcesIterable,
            Function<? super Object[], ? extends R> zipper, Supplier<? extends Queue<T>> queueSupplier, int prefetch) {
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        this.sources = null;
        this.sourcesIterable = Objects.requireNonNull(sourcesIterable, "sourcesIterable");
        this.zipper = Objects.requireNonNull(zipper, "zipper");
        this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
        this.prefetch = prefetch;
    }
    
    
    @Override
    public void subscribe(Subscriber<? super R> s) {
        Publisher<? extends T>[] srcs = sources;
        if (srcs != null) {
            handleArrayMode(s, srcs);
        } else {
            handleIterableMode(s, sourcesIterable);
        }
    }
    
    @SuppressWarnings("unchecked")
    void handleIterableMode(Subscriber<? super R> s, Iterable<? extends Publisher<? extends T>> sourcesIterable) {
        Object[] scalars = new Object[8];
        Publisher<? extends T>[] srcs = new Publisher[8];
        
        int n = 0;
        int sc = 0;
        
        for (Publisher<? extends T> p : sourcesIterable) {
            if (p == null) {
                EmptySubscription.error(s, new NullPointerException("The sourcesIterable returned a null Publisher"));
                return;
            }
            
            if (p instanceof Supplier) {
                Supplier<T> supplier = (Supplier<T>) p;
                
                T v = supplier.get();
                
                if (v == null) {
                    EmptySubscription.complete(s);
                    return;
                }
                
                if (n == scalars.length) {
                    Object[] b = new Object[n + (n >> 1)];
                    System.arraycopy(scalars, 0, b, 0, n);
                    
                    Publisher<T>[] c = new Publisher[b.length];
                    System.arraycopy(srcs, 0, c, 0, n);
                    
                    scalars = b;
                    srcs = c;
                }
                
                scalars[n] = v;
                sc++;
            } else {
                if (n == srcs.length) {
                    Object[] b = new Object[n + (n >> 1)];
                    System.arraycopy(scalars, 0, b, 0, n);
                    
                    Publisher<T>[] c = new Publisher[b.length];
                    System.arraycopy(srcs, 0, c, 0, n);
                    
                    scalars = b;
                    srcs = c;
                }
                srcs[n] = p;
            }
        }
        
        if (n == 0) {
            EmptySubscription.complete(s);
            return;
        }
        
        handleBoth(s, srcs, scalars, n, sc);
    }

    void handleArrayMode(Subscriber<? super R> s, Publisher<? extends T>[] srcs) {
        
        int n = srcs.length;

        if (n == 0) {
            EmptySubscription.complete(s);
            return;
        }

        Object[] scalars = null;
        int sc = 0;

        for (int j = 0; j < n; j++) {
            Publisher<? extends T> p = srcs[j];
            
            if (p == null) {
                EmptySubscription.error(s, new NullPointerException("The sources contained a null Publisher"));
                return;
            }
            
            if (p instanceof Supplier) {
                @SuppressWarnings("unchecked")
                Object v = ((Supplier<? extends T>)p).get();
                
                if (v == null) {
                    EmptySubscription.complete(s);
                    return;
                }
                
                if (scalars == null) {
                    scalars = new Object[n];
                }
                
                scalars[j] = v;
                sc++;
            }
        }
        
        handleBoth(s, srcs, scalars, n, sc);
    }

    void handleBoth(Subscriber<? super R> s, Publisher<? extends T>[] srcs, Object[] scalars, int n, int sc) {
        if (scalars != null) {
            if (n != sc) {
                PublisherZipSingleCoordinator<T, R> coordinator = 
                        new PublisherZipSingleCoordinator<>(s, scalars, n, zipper);
                
                s.onSubscribe(coordinator);
                
                coordinator.subscribe(n, sc, srcs);
            } else {
                SubscriberDeferredScalar<R, R> sds = new SubscriberDeferredScalar<>(s);

                s.onSubscribe(sds);
                
                R r;
                
                try {
                    r = zipper.apply(scalars);
                } catch (Throwable e) {
                    ExceptionHelper.throwIfFatal(e);
                    s.onError(e);
                    return;
                }
                
                if (r == null) {
                    s.onError(new NullPointerException("The zipper returned a null value"));
                    return;
                }
                
                sds.complete(r);
            }
            
        } else {
            
            PublisherZipCoordinator<T, R> coordinator = new PublisherZipCoordinator<>(s, zipper, n, queueSupplier, prefetch);
            
            s.onSubscribe(coordinator);
            
            coordinator.subscribe(srcs, n);
        }
    }

    @Override
    public Iterator<?> upstreams() {
        return sources == null ? sourcesIterable.iterator() : Arrays.asList(sources).iterator();
    }

    @Override
    public long upstreamsCount() {
        return sources == null ? -1 : sources.length;
    }

    static final class PublisherZipSingleCoordinator<T, R> extends SubscriberDeferredScalar<R, R>
    implements PublishableMany, Backpressurable {

        final Function<? super Object[], ? extends R> zipper;
        
        final Object[] scalars;
        
        final PublisherZipSingleSubscriber<T>[] subscribers;
        
        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherZipSingleCoordinator> WIP =
                AtomicIntegerFieldUpdater.newUpdater(PublisherZipSingleCoordinator.class, "wip");
        
        @SuppressWarnings("unchecked")
        public PublisherZipSingleCoordinator(Subscriber<? super R> subscriber, Object[] scalars, int n, Function<? super Object[], ? extends R> zipper) {
            super(subscriber);
            this.zipper = zipper;
            this.scalars = scalars;
            PublisherZipSingleSubscriber<T>[] a = new PublisherZipSingleSubscriber[n];
            for (int i = 0; i < n; i++) {
                if (scalars[i] == null) {
                    a[i] = new PublisherZipSingleSubscriber<>(this, i);
                }
            }
            this.subscribers = a;
        }
        
        void subscribe(int n, int sc, Publisher<? extends T>[] sources) {
            WIP.lazySet(this, n - sc);
            PublisherZipSingleSubscriber<T>[] a = subscribers;
            for (int i = 0; i < n; i++) {
                if (wip <= 0 || isCancelled()) {
                    break;
                }
                PublisherZipSingleSubscriber<T> s = a[i];
                if (s != null) {
                    sources[i].subscribe(s);
                }
            }
        }
        
        void next(T value, int index) {
            Object[] a = scalars;
            a[index] = value;
            if (WIP.decrementAndGet(this) == 0) {
                R r;
                
                try {
                    r = zipper.apply(a);
                } catch (Throwable e) {
                    ExceptionHelper.throwIfFatal(e);
                    subscriber.onError(e);
                    return;
                }
                
                if (r == null) {
                    subscriber.onError(new NullPointerException("The zipper returned a null value"));
                } else {
                    complete(r);
                }
            }
        }
        
        void error(Throwable e, int index) {
            if (WIP.getAndSet(this, 0) > 0) {
                cancelAll();
                subscriber.onError(e);
            } else {
               UnsignalledExceptions.onErrorDropped(e);
            }
        }
        
        void complete(int index) {
            if (scalars[index] == null) {
                if (WIP.getAndSet(this, 0) > 0) {
                    cancelAll();
                    subscriber.onComplete();
                }
            }
        }
        
        @Override
        public void cancel() {
            super.cancel();
            cancelAll();
        }

        @Override
        public long getCapacity() {
            return upstreamsCount();
        }

        @Override
        public long getPending() {
            return wip;
        }

        @Override
        public Iterator<?> upstreams() {
            return Arrays.asList(subscribers).iterator();
        }

        @Override
        public long upstreamsCount() {
            return subscribers.length;
        }

        void cancelAll() {
            for (PublisherZipSingleSubscriber<T> s : subscribers) {
                if (s != null) {
                    s.cancel();
                }
            }
        }
    }
    
    static final class PublisherZipSingleSubscriber<T> implements Subscriber<T>, Cancellable, Backpressurable, Completable, Introspectable{
        final PublisherZipSingleCoordinator<T, ?> parent;
        
        final int index;

        volatile Subscription s;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherZipSingleSubscriber, Subscription> S =
                AtomicReferenceFieldUpdater.newUpdater(PublisherZipSingleSubscriber.class, Subscription.class, "s");
        
        boolean done;

        public PublisherZipSingleSubscriber(PublisherZipSingleCoordinator<T, ?> parent, int index) {
            this.parent = parent;
            this.index = index;
        }


        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.setOnce(S, this, s)) {
                this.s = s;
                s.request(Long.MAX_VALUE);
            }
        }


        @Override
        public void onNext(T t) {
            if (done) {
                UnsignalledExceptions.onNextDropped(t);
                return;
            }
            done = true;
            SubscriptionHelper.terminate(S, this);
            parent.next(t, index);
        }


        @Override
        public void onError(Throwable t) {
            if (done) {
                UnsignalledExceptions.onErrorDropped(t);
                return;
            }
            done = true;
            parent.error(t, index);
        }


        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            parent.complete(index);
        }

        @Override
        public long getCapacity() {
            return 1;
        }

        @Override
        public long getPending() {
            return !done ? 1 : -1;
        }

        @Override
        public boolean isCancelled() {
            return s == CancelledSubscription.INSTANCE;
        }

        @Override
        public boolean isStarted() {
            return !done && !isCancelled();
        }

        @Override
        public boolean isTerminated() {
            return done;
        }

        @Override
        public int getMode() {
            return INNER;
        }

        @Override
        public String getName() {
            return "ScalarZipSubscriber";
        }

        @Override
        public Object upstream() {
            return s;
        }

        void cancel() {
            SubscriptionHelper.terminate(S, this);
        }
    }
    
    static final class PublisherZipCoordinator<T, R> implements Subscription, PublishableMany, Cancellable, Backpressurable, Completable, Requestable,
                                                                Failurable {

        final Subscriber<? super R> actual;
        
        final PublisherZipInner<T>[] subscribers;
        
        final Function<? super Object[], ? extends R> zipper;
        
        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherZipCoordinator> WIP =
                AtomicIntegerFieldUpdater.newUpdater(PublisherZipCoordinator.class, "wip");
        
        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherZipCoordinator> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(PublisherZipCoordinator.class, "requested");
        
        volatile Throwable error;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherZipCoordinator, Throwable> ERROR =
                AtomicReferenceFieldUpdater.newUpdater(PublisherZipCoordinator.class, Throwable.class, "error");
        
        volatile boolean done;
        
        volatile boolean cancelled;
        
        public PublisherZipCoordinator(Subscriber<? super R> actual, 
                Function<? super Object[], ? extends R> zipper, int n, 
                Supplier<? extends Queue<T>> queueSupplier, int prefetch) {
            this.actual = actual;
            this.zipper = zipper;
            @SuppressWarnings("unchecked")
            PublisherZipInner<T>[] a = new PublisherZipInner[n];
            for (int i = 0; i < n; i++) {
                a[i] = new PublisherZipInner<>(this, prefetch, i, queueSupplier); 
            }
            this.subscribers = a;
        }
        
        void subscribe(Publisher<? extends T>[] sources, int n) {
            PublisherZipInner<T>[] a = subscribers;
            for (int i = 0; i < n; i++) {
                if (done || cancelled || error != null) {
                    return;
                }
                sources[i].subscribe(a[i]);
            }
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.addAndGet(REQUESTED, this, n);
                drain();
            }
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                
                cancelAll();
            }
        }

        @Override
        public long getCapacity() {
            return upstreamsCount();
        }

        @Override
        public long getPending() {
            int nonEmpties = 0;
            for(int i =0; i < subscribers.length; i++){
                if(subscribers[i].queue != null && !subscribers[i].queue .isEmpty()){
                    nonEmpties++;
                }
            }
            return nonEmpties;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isStarted() {
            return !done;
        }

        @Override
        public boolean isTerminated() {
            return done;
        }

        @Override
        public Throwable getError() {
            return error;
        }

        @Override
        public Object upstream() {
            return null;
        }

        @Override
        public Iterator<?> upstreams() {
            return Arrays.asList(subscribers).iterator();
        }

        @Override
        public long upstreamsCount() {
            return subscribers.length;
        }

        @Override
        public long requestedFromDownstream() {
            return requested;
        }

        void error(Throwable e, int index) {
            if (ExceptionHelper.addThrowable(ERROR, this, e)) {
                drain();
            } else {
                UnsignalledExceptions.onErrorDropped(e);
            }
        }
        
        void cancelAll() {
            for (PublisherZipInner<T> s : subscribers) {
                s.cancel();
            }
        }
        
        void drain() {
            
            if (WIP.getAndIncrement(this) != 0) {
                return;
            }

            final Subscriber<? super R> a = actual;
            final PublisherZipInner<T>[] qs = subscribers;
            final int n = qs.length;
            
            int missed = 1;
            
            for (;;) {
                
                long r = requested;
                long e = 0L;
                
                while (r != e) {
                    
                    if (cancelled) {
                        return;
                    }
                    
                    if (error != null) {
                        cancelAll();

                        Throwable ex = ExceptionHelper.terminate(ERROR, this);
                        
                        a.onError(ex);
                        
                        return;
                    }
                    
                    boolean done = false;
                    boolean empty = false;
                    
                    for (int j = 0; j < n; j++) {
                        PublisherZipInner<T> inner = qs[j];

                        boolean d = inner.done;
                        Queue<T> q = inner.queue;
                        boolean f = q == null || q.isEmpty();

                        if (d && f) {
                            done = true;
                            break;
                        }
                        if (f) {
                            empty = true;
                            break;
                        }
                    }
                    
                    if (done) {
                        cancelAll();
                        
                        a.onComplete();
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }
                    
                    Object[] values = new Object[n];

                    for (int j = 0; j < n; j++) {
                        PublisherZipInner<T> inner = qs[j];
                        values[j] = inner.queue.poll();
                    }
                    
                    R v;
                    
                    try {
                        v = zipper.apply(values);
                    } catch (Throwable ex) {
                        ExceptionHelper.throwIfFatal(ex);
                        
                        cancelAll();
                        
                        ExceptionHelper.addThrowable(ERROR, this, ex);
                        ex = ExceptionHelper.terminate(ERROR, this);
                        
                        a.onError(ex);
                        
                        return;
                    }
                    
                    if (v == null) {
                        cancelAll();

                        Throwable ex = new NullPointerException("The zipper returned a null value");
                        
                        ExceptionHelper.addThrowable(ERROR, this, ex);
                        ex = ExceptionHelper.terminate(ERROR, this);
                        
                        a.onError(ex);
                        
                        return;
                    }
                    
                    a.onNext(v);
                    
                    e++;
                }
                
                if (r == e) {
                    if (cancelled) {
                        return;
                    }
                    
                    if (error != null) {
                        cancelAll();

                        Throwable ex = ExceptionHelper.terminate(ERROR, this);
                        
                        a.onError(ex);
                        
                        return;
                    }
                    
                    boolean done = false;
                    
                    for (int j = 0; j < n; j++) {
                        PublisherZipInner<T> inner = qs[j];

                        boolean d = inner.done;
                        Queue<T> q = inner.queue;
                        boolean f = q == null || q.isEmpty();
                        if (d && f) {
                            done = true;
                            break;
                        }
                    }
                    
                    if (done) {
                        cancelAll();
                        
                        a.onComplete();
                        return;
                    }
                }
                
                if (e != 0) {
                    
                    for (int j = 0; j < n; j++) {
                        PublisherZipInner<T> inner = qs[j];
                        inner.request(e);
                    }
                    
                    if (r != Long.MAX_VALUE) {
                        REQUESTED.addAndGet(this, -e);
                    }
                }
                
                missed = WIP.addAndGet(this, -missed);
                if (missed == 0) {
                    break;
                }
            }
        }
    }
    
    static final class PublisherZipInner<T> implements Subscriber<T>,
                                                       Backpressurable,
                                                       Completable,
                                                       Prefetchable,
                                                       Subscribable {
        
        final PublisherZipCoordinator<T, ?> parent;

        final int prefetch;
        
        final int limit;
        
        final int index;
        
        final Supplier<? extends Queue<T>> queueSupplier;
        
        volatile Queue<T> queue;
        
        volatile Subscription s;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherZipInner, Subscription> S =
                AtomicReferenceFieldUpdater.newUpdater(PublisherZipInner.class, Subscription.class, "s");
        
        long produced;
        
        volatile boolean done;
        
        boolean synchronousMode;
        
        public PublisherZipInner(PublisherZipCoordinator<T, ?> parent, int prefetch, int index, Supplier<? extends Queue<T>> queueSupplier) {
            this.parent = parent;
            this.prefetch = prefetch;
            this.index = index;
            this.queueSupplier = queueSupplier;
            this.limit = prefetch - (prefetch >> 2);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.setOnce(S, this, s)) {
                if (s instanceof SynchronousSource) {
                    synchronousMode = true;
                    
                    queue = (SynchronousSource<T>)s;
                    done = true;
                    parent.drain();
                } else {
                    
                    try {
                        queue = queueSupplier.get();
                    } catch (Throwable e) {
                        ExceptionHelper.throwIfFatal(e);
                        s.cancel();
                        onError(e);
                        return;
                    }
                    
                    s.request(prefetch);
                }
            }
        }

        @Override
        public void onNext(T t) {
            queue.offer(t);
            parent.drain();
        }

        @Override
        public void onError(Throwable t) {
            parent.error(t, index);
        }

        @Override
        public void onComplete() {
            done = true;
            parent.drain();
        }

        @Override
        public long getCapacity() {
            return prefetch;
        }

        @Override
        public long getPending() {
            return queue != null ? queue.size() : -1;
        }

        @Override
        public boolean isStarted() {
            return !done;
        }

        @Override
        public boolean isTerminated() {
            return done && (queue == null || queue.isEmpty());
        }

        @Override
        public long expectedFromUpstream() {
            return produced;
        }

        @Override
        public long limit() {
            return limit;
        }

        @Override
        public Object upstream() {
            return s;
        }

        @Override
        public Object downstream() {
            return null;
        }

        void cancel() {
            SubscriptionHelper.terminate(S, this);
        }
        
        void request(long n) {
            if (!synchronousMode) {
                long p = produced + n;
                if (p >= limit) {
                    produced = 0L;
                    s.request(p);
                } else {
                    produced = p;
                }
            }
        }
    }
}
