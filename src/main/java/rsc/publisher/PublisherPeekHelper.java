package rsc.publisher;

import java.util.function.*;

import org.reactivestreams.Subscription;

/**
 * Set of methods that return various publisher state peeking callbacks.
 *
 * @param <T> the value type of the sequence
 */
interface PublisherPeekHelper<T> {
    
    Consumer<? super Subscription> onSubscribeCall();

    Consumer<? super T> onNextCall();

    Consumer<? super T> onAfterNextCall();

    Consumer<? super Throwable> onErrorCall();

    Runnable onCompleteCall();

    Runnable onAfterTerminateCall();

    LongConsumer onRequestCall();

    Runnable onCancelCall();
}