package reactivestreams.commons.flow;

import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A micro API for stream fusion, in particular marks producers that support a {@link FusionSubscription}.
 */
public interface Fuseable {

    /**
     * A subscriber variant that can immediately tell if it consumed
     * the value or not, avoiding the usual request(1) for dropped
     * values.
     *
     * @param <T> the value type
     */
    interface ConditionalSubscriber<T> extends Subscriber<T> {
        /**
         * Try consuming the value and return true if successful.
         * @param t the value to consume
         * @return true if consumed, false if dropped and a new value can be immediately sent
         */
        boolean tryOnNext(T t);
    }

	/**
	 * Contract queue-fusion based optimizations for supporting subscriptions.
	 *
	 * <ul>
	 *  <li>
	 *  Synchronous sources which have fixed size and can
	 *  emit its items in a pull fashion, thus avoiding the request-accounting
	 *  overhead in many cases.
	 *  </li>
	 *  <li>
	 *  Asynchronous sources which can act as a queue and subscription at
	 *  the same time, saving on allocating another queue most of the time.
	 * </li>
	 * </ul>
	 *
	 * <p>
	 *
	 * @param <T> the value type emitted
	 */
	interface FusionSubscription<T> extends Subscription {

		/**
		 * Consumers of an Asynchronous FusionSubscription have to signal it to switch to a fused-mode
		 * so it no longer run its own drain loop but directly signals onNext(null) to
		 * indicate there is an item available in this queue-view.
		 * <p>
		 * Consumers of an Synchronous FusionSubscription will usually consider this method no-op and
		 * return true to signal immediate availability.
		 * <p>
		 * The method has to be called while the parent is in onSubscribe and before any
		 * other interaction with the Subscription.
		 *
		 * @return FALSE if asynchronous or TRUE if immediately ready
		 */
		boolean requestSyncFusion();

		/**
		 * @return the {@link Queue} view of the produced sequence, it's behavior is driven by the type of fusion:
		 * sync or async
		 *
		 * @see #requestSyncFusion()
		 */
		Queue<T> queue();

		/**
		 * Requests the upstream to drop the current value.
		 * <p>
		 * This is allows fused intermediate operators to avoid peek/poll pairs.
		 */
		void drop();
	}

	/**
	 * Base class for synchronous sources which have fixed size and can
	 * emit its items in a pull fashion, thus avoiding the request-accounting
	 * overhead in many cases.
	 *
	 * Implementor note: This can be simplified using Java  8 interface features but this source maintains a
	 * JDK7 comp
	 *
	 * @param <T> the content value type
	 */
	abstract class SynchronousSubscription<T> implements FusionSubscription<T>, Queue<T> {
		@Override
		public final boolean offer(T e) {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final int size() {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final boolean contains(Object o) {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final Iterator<T> iterator() {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final Object[] toArray() {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final <U> U[] toArray(U[] a) {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final boolean remove(Object o) {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final boolean containsAll(Collection<?> c) {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final boolean addAll(Collection<? extends T> c) {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final boolean removeAll(Collection<?> c) {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final boolean retainAll(Collection<?> c) {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public boolean requestSyncFusion() {
			return true;
		}

		@Override
		public final boolean add(T e) {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final T remove() {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final T element() {
			throw new UnsupportedOperationException("Operators should not use this method!");
		}

		@Override
		public final Queue<T> queue() {
			return this;
		}
	}
}
