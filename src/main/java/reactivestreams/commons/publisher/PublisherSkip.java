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

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.flow.Producer;
import reactivestreams.commons.flow.Receiver;
import reactivestreams.commons.state.Backpressurable;
import reactivestreams.commons.state.Completable;
import reactivestreams.commons.state.Prefetchable;
import reactivestreams.commons.util.SubscriptionHelper;

/**
 * Skips the first N elements from a reactive stream.
 *
 * @param <T> the value type
 */
public final class PublisherSkip<T> extends PublisherSource<T, T> {

    final Publisher<? extends T> source;

    final long n;

    public PublisherSkip(Publisher<? extends T> source, long n) {
        super(source);
        if (n < 0) {
            throw new IllegalArgumentException("n >= 0 required but it was " + n);
        }
        this.source = Objects.requireNonNull(source, "source");
        this.n = n;
    }

    public long n() {
        return n;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        if (n == 0) {
            source.subscribe(s);
        } else {
            source.subscribe(new PublisherSkipSubscriber<>(s, n));
        }
    }

    static final class PublisherSkipSubscriber<T> implements Subscriber<T>, Receiver, Producer, Prefetchable,
                                                             Backpressurable, Completable, Subscription {

        final Subscriber<? super T> actual;

        final long n;

        long remaining;
        
        Subscription s;

        public PublisherSkipSubscriber(Subscriber<? super T> actual, long n) {
            this.actual = actual;
            this.n = n;
            this.remaining = n;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                
                actual.onSubscribe(this);
    
                s.request(n);
            }
        }

        @Override
        public void onNext(T t) {
            long r = remaining;
            if (r == 0L) {
                actual.onNext(t);
            } else {
                remaining = r - 1;
            }
        }

        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            actual.onComplete();
        }

        @Override
        public boolean isStarted() {
            return remaining != n;
        }

        @Override
        public boolean isTerminated() {
            return remaining == 0;
        }

        @Override
        public long getCapacity() {
            return n;
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public long expectedFromUpstream() {
            return remaining;
        }

        @Override
        public long getPending() {
            return -1L;
        }

        @Override
        public Object upstream() {
            return s;
        }

        @Override
        public long limit() {
            return 0;
        }
        
        @Override
        public void request(long n) {
            s.request(n);
        }
        
        @Override
        public void cancel() {
            s.cancel();
        }
    }
}
