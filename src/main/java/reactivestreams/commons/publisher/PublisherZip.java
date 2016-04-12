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

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import org.reactivestreams.*;

import reactivestreams.commons.flow.*;
import reactivestreams.commons.state.*;
import reactivestreams.commons.subscriber.DeferredScalarSubscriber;
import reactivestreams.commons.util.*;

/**
 * Repeatedly takes one item from all source Publishers and 
 * runs it through a function to produce the output item.
 *
 * @param <T> the common input type
 * @param <R> the output value type
 */
public final class PublisherZip<T, R> extends Px<R> implements Introspectable, Backpressurable,
                                                                          MultiReceiver {

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
                
                T v;
                
                try {
                    v = supplier.get();
                } catch (Throwable e) {
                    EmptySubscription.error(s, ExceptionHelper.unwrap(e));
                    return;
                }
                
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
            n++;
        }
        
        if (n == 0) {
            EmptySubscription.complete(s);
            return;
        }
        
        handleBoth(s, srcs, scalars, n, sc);
    }

    @SuppressWarnings("unchecked")
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
                Object v;
                
                try {
                    v = ((Supplier<? extends T>)p).get();
                } catch (Throwable e) {
                    EmptySubscription.error(s, ExceptionHelper.unwrap(e));
                    return;
                }
                
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
        if (sc != 0) {
            if (n != sc) {
                PublisherZipSingleCoordinator<T, R> coordinator = 
                        new PublisherZipSingleCoordinator<>(s, scalars, n, zipper);
                
                s.onSubscribe(coordinator);
                
                coordinator.subscribe(n, sc, srcs);
            } else {
                DeferredScalarSubscriber<R, R> sds = new DeferredScalarSubscriber<>(s);

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
    public long getCapacity() {
        return prefetch;
    }

    @Override
    public long upstreamCount() {
        return sources == null ? -1 : sources.length;
    }

    static final class PublisherZipSingleCoordinator<T, R> extends DeferredScalarSubscriber<R, R>
            implements MultiReceiver, Backpressurable {

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
            return upstreamCount();
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
        public long upstreamCount() {
            return subscribers.length;
        }

        void cancelAll() {
            for (PublisherZipSingleSubscriber<T> s : subscribers) {
                if (s != null) {
                    s.dispose();
                }
            }
        }
    }
    
    static final class PublisherZipSingleSubscriber<T> implements Subscriber<T>, Cancellable, Cancellation, Backpressurable,
                                                                  Completable, Introspectable, Receiver {
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

        @Override
        public void dispose() {
            SubscriptionHelper.terminate(S, this);
        }
    }
    
    static final class PublisherZipCoordinator<T, R> implements Subscription, MultiReceiver, Cancellable, Backpressurable, Completable, Requestable,
                                                                Introspectable {

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
                BackpressureHelper.getAndAddCap(REQUESTED, this, n);
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
            return upstreamCount();
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
        public Iterator<?> upstreams() {
            return Arrays.asList(subscribers).iterator();
        }

        @Override
        public long upstreamCount() {
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
                        boolean f;
                        
                        if (q != null) {
                            try {
                                f = q.isEmpty();
                            } catch (Throwable ex) {
                                ExceptionHelper.throwIfFatal(ex);
                                
                                cancelAll();
                                
                                ExceptionHelper.addThrowable(ERROR, this, ex);
                                ex = ExceptionHelper.terminate(ERROR, this);
                                
                                a.onError(ex);
                                
                                return;
                            }
                        } else {
                            f = true;
                        }

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
                        try {
                            values[j] = inner.queue.poll();
                        } catch (Throwable ex) {
                            ExceptionHelper.throwIfFatal(ex);
                            
                            cancelAll();
                            
                            ExceptionHelper.addThrowable(ERROR, this, ex);
                            ex = ExceptionHelper.terminate(ERROR, this);
                            
                            a.onError(ex);
                            
                            return;
                        }
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
                        boolean f;
                        
                        if (q != null) {
                            try {
                                f = q.isEmpty();
                            } catch (Throwable ex) {
                                ExceptionHelper.throwIfFatal(ex);
                                
                                cancelAll();
                                
                                ExceptionHelper.addThrowable(ERROR, this, ex);
                                ex = ExceptionHelper.terminate(ERROR, this);
                                
                                a.onError(ex);
                                
                                return;
                            }
                        } else {
                            f = true;
                        }
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
                                                       Prefetchable, Receiver, Producer {
        
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
        
        int sourceMode;
        
        /** Running with regular, arbitrary source. */
        static final int NORMAL = 0;
        /** Running with a source that implements SynchronousSource. */
        static final int SYNC = 1;
        /** Running with a source that implements AsynchronousSource. */
        static final int ASYNC = 2;
        
        volatile int once;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherZipInner> ONCE =
                AtomicIntegerFieldUpdater.newUpdater(PublisherZipInner.class, "once");
        
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
                if (s instanceof Fuseable.QueueSubscription) {
                    Fuseable.QueueSubscription<T> f = (Fuseable.QueueSubscription<T>) s;

                    int m = f.requestFusion(Fuseable.ANY);
                    
                    if (m == Fuseable.SYNC) {
                        sourceMode = SYNC;
                        queue = f;
                        done = true;
                        parent.drain();
                        return;
                    } else 
                    if (m == Fuseable.ASYNC) {
                        sourceMode = ASYNC;
                        queue = f;
                    } else {
                        try {
                            queue = queueSupplier.get();
                        } catch (Throwable e) {
                            ExceptionHelper.throwIfFatal(e);
                            s.cancel();
                            onError(e);
                            return;
                        }
                    }
                } else {
                    
                    try {
                        queue = queueSupplier.get();
                    } catch (Throwable e) {
                        ExceptionHelper.throwIfFatal(e);
                        s.cancel();
                        onError(e);
                        return;
                    }
                    
                }
                s.request(prefetch);
            }
        }

        @Override
        public void onNext(T t) {
            if (sourceMode != ASYNC) {
                queue.offer(t);
            }
            parent.drain();
        }

        @Override
        public void onError(Throwable t) {
            if (sourceMode != ASYNC || ONCE.compareAndSet(this, 0, 1)) {
                parent.error(t, index);
            }
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
            if (sourceMode != SYNC) {
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
