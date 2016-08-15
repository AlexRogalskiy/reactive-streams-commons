
package rsc.publisher;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import rsc.documentation.BackpressureMode;
import rsc.documentation.BackpressureSupport;
import rsc.flow.Cancellation;
import rsc.publisher.PublisherGroupJoin.JoinSupport;
import rsc.publisher.PublisherGroupJoin.LeftRightEndSubscriber;
import rsc.publisher.PublisherGroupJoin.LeftRightSubscriber;
import rsc.subscriber.SubscriptionHelper;
import rsc.util.BackpressureHelper;
import rsc.util.ExceptionHelper;
import rsc.util.OpenHashSet;
import rsc.util.UnsignalledExceptions;

/**
 * Returns a Publisher that correlates two Publishers when they overlap in time and groups
 * the results.
 * <p>
 * There are no guarantees in what order the items get combined when multiple items from
 * one or both source Publishers overlap.
 *
 * @param <TLeft> the left Publisher to correlate items from the source Publisher with
 * @param <TRight> the other Publisher to correlate items from the source Publisher with
 * @param <TLeftEnd> a function that returns a Publisher whose emissions indicate the
 * duration of the values of the source Publisher
 * @param <TRightEnd> a function that returns a Publisher whose emissions indicate the
 * duration of the values of the {@code right} Publisher
 * @param <R> a function that takes an item emitted by each Publisher and returns the
 * value to be emitted by the resulting Publisher
 */
@BackpressureSupport(input = BackpressureMode.UNBOUNDED, innerOutput = BackpressureMode.BOUNDED, output = BackpressureMode.ERROR)
public final class PublisherJoin<TLeft, TRight, TLeftEnd, TRightEnd, R>
		extends PublisherSource<TLeft, R> {

	final Publisher<? extends TRight> other;

	final Function<? super TLeft, ? extends Publisher<TLeftEnd>> leftEnd;

	final Function<? super TRight, ? extends Publisher<TRightEnd>> rightEnd;

	final BiFunction<? super TLeft, ? super TRight, ? extends R> resultSelector;

	final Supplier<? extends Queue<Object>> queueSupplier;

	public PublisherJoin(Publisher<TLeft> source,
			Publisher<? extends TRight> other,
			Function<? super TLeft, ? extends Publisher<TLeftEnd>> leftEnd,
			Function<? super TRight, ? extends Publisher<TRightEnd>> rightEnd,
			BiFunction<? super TLeft, ? super TRight, ? extends R> resultSelector,
			Supplier<? extends Queue<Object>> queueSupplier) {
		super(source);
		this.other = Objects.requireNonNull(other, "other");
		this.leftEnd = Objects.requireNonNull(leftEnd, "leftEnd");
		this.rightEnd = Objects.requireNonNull(rightEnd, "rightEnd");
		this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
		this.resultSelector = Objects.requireNonNull(resultSelector, "resultSelector");
	}

	@Override
	public void subscribe(Subscriber<? super R> s) {

		GroupJoinSubscription<TLeft, TRight, TLeftEnd, TRightEnd, R> parent =
				new GroupJoinSubscription<>(s,
						leftEnd,
						rightEnd,
						resultSelector,
						queueSupplier.get());

		s.onSubscribe(parent);

		LeftRightSubscriber left = new LeftRightSubscriber(parent, true);
		parent.cancellations.add(left);
		LeftRightSubscriber right = new LeftRightSubscriber(parent, false);
		parent.cancellations.add(right);

		source.subscribe(left);
		other.subscribe(right);
	}

	static final class GroupJoinSubscription<TLeft, TRight, TLeftEnd, TRightEnd, R>
			implements Subscription, JoinSupport {

		final Subscriber<? super R> actual;

		final Queue<Object>               queue;
		final BiPredicate<Object, Object> queueBiOffer;

		final OpenHashSet<Cancellation> cancellations;

		final Map<Integer, TLeft> lefts;

		final Map<Integer, TRight> rights;

		final Function<? super TLeft, ? extends Publisher<TLeftEnd>> leftEnd;

		final Function<? super TRight, ? extends Publisher<TRightEnd>> rightEnd;

		final BiFunction<? super TLeft, ? super TRight, ? extends R> resultSelector;

		volatile int wip;

		@SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<GroupJoinSubscription> WIP =
				AtomicIntegerFieldUpdater.newUpdater(GroupJoinSubscription.class, "wip");

		volatile int active;

		@SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<GroupJoinSubscription> ACTIVE =
				AtomicIntegerFieldUpdater.newUpdater(GroupJoinSubscription.class,
						"active");

		volatile long requested;

		@SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<GroupJoinSubscription> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(GroupJoinSubscription.class,
						"requested");

		volatile Throwable error;

		@SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<GroupJoinSubscription, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(GroupJoinSubscription.class,
						Throwable.class,
						"error");

		volatile boolean cancelled;

		int leftIndex;

		int rightIndex;

		static final Integer LEFT_VALUE = 1;

		static final Integer RIGHT_VALUE = 2;

		static final Integer LEFT_CLOSE = 3;

		static final Integer RIGHT_CLOSE = 4;

		@SuppressWarnings("unchecked")
		public GroupJoinSubscription(Subscriber<? super R> actual,
				Function<? super TLeft, ? extends Publisher<TLeftEnd>> leftEnd,
				Function<? super TRight, ? extends Publisher<TRightEnd>> rightEnd,
				BiFunction<? super TLeft, ? super TRight, ? extends R> resultSelector,
				Queue<Object> queue) {
			this.actual = actual;
			this.cancellations = new OpenHashSet<>();
			this.queue = queue;
			if(!(queue instanceof BiPredicate)){
				throw new IllegalArgumentException("The provided queue must implement " +
						"BiPredicate to expose atomic dual insert");
			}
			this.queueBiOffer = (BiPredicate<Object, Object>)queue;
			this.lefts = new LinkedHashMap<>();
			this.rights = new LinkedHashMap<>();
			this.leftEnd = leftEnd;
			this.rightEnd = rightEnd;
			this.resultSelector = resultSelector;
			ACTIVE.lazySet(this, 2);
		}

		@Override
		public void request(long n) {
			if (SubscriptionHelper.validate(n)) {
				BackpressureHelper.getAndAddCap(REQUESTED, this, n);
			}
		}

		@Override
		public void cancel() {
			if (cancelled) {
				return;
			}
			cancelled = true;
			cancelAll();
			if (WIP.getAndIncrement(this) == 0) {
				queue.clear();
			}
		}

		void cancelAll() {
			Object[] a = cancellations.keys();
			for (Object o : a) {
				if (o != null) {
					((Cancellation)o).dispose();
				}
			}
		}

		void errorAll(Subscriber<?> a) {
			Throwable ex = ExceptionHelper.terminate(ERROR, this);

			lefts.clear();
			rights.clear();

			a.onError(ex);
		}

		void fail(Throwable exc, Subscriber<?> a, Queue<?> q) {
			ExceptionHelper.throwIfFatal(exc);
			ExceptionHelper.addThrowable(ERROR, this, exc);
			q.clear();
			cancelAll();
			errorAll(a);
		}

		void drain() {
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}

			int missed = 1;
			Queue<Object> q = queue;
			Subscriber<? super R> a = actual;

			for (; ; ) {
				for (; ; ) {
					if (cancelled) {
						q.clear();
						return;
					}

					Throwable ex = error;
					if (ex != null) {
						q.clear();
						cancelAll();
						errorAll(a);
						return;
					}

					boolean d = active == 0;

					Integer mode = (Integer) q.poll();

					boolean empty = mode == null;

					if (d && empty) {

						lefts.clear();
						rights.clear();
						Object[] c = cancellations.keys();
						for (Object o : c) {
							if (o != null) {
								((Cancellation)o).dispose();
							}
						}

						a.onComplete();
						return;
					}

					if (empty) {
						break;
					}

					Object val = q.poll();

					if (mode == LEFT_VALUE) {
						@SuppressWarnings("unchecked") TLeft left = (TLeft) val;

						int idx = leftIndex++;
						lefts.put(idx, left);

						Publisher<TLeftEnd> p;

						try {
							p = Objects.requireNonNull(leftEnd.apply(left),
									"The leftEnd returned a null Publisher");
						}
						catch (Throwable exc) {
							fail(exc, a, q);
							return;
						}

						LeftRightEndSubscriber end =
								new LeftRightEndSubscriber(this, true, idx);
						cancellations.add(end);

						p.subscribe(end);

						ex = error;
						if (ex != null) {
							q.clear();
							cancelAll();
							errorAll(a);
							return;
						}

						long r = requested;
						long e = 0L;

						for (TRight right : rights.values()) {

							R w;

							try {
								w = Objects.requireNonNull(resultSelector.apply(left,
										right),
										"The resultSelector returned a null value");
							}
							catch (Throwable exc) {
								fail(exc, a, q);
								return;
							}

							if (e != r) {
								a.onNext(w);

								e++;
							}
							else {
								ExceptionHelper.addThrowable(ERROR,
										this,
										new IllegalStateException("Could not " + "emit value due to lack of requests"));
								q.clear();
								cancelAll();
								errorAll(a);
								return;
							}
						}

						if (e != 0L) {
							long upd;
							for(;;) {
								if (r == Long.MAX_VALUE) {
									break;
								}
								upd = r - e;
								if (upd < 0L){
									fail(new IllegalStateException("More produced than " +
											"requested: " + upd), a, q);
									return;
								}
								if(REQUESTED.compareAndSet(this, r, upd)){
									break;
								}
								r = requested;
							}
						}
					}
					else if (mode == RIGHT_VALUE) {
						@SuppressWarnings("unchecked") TRight right = (TRight) val;

						int idx = rightIndex++;

						rights.put(idx, right);

						Publisher<TRightEnd> p;

						try {
							p = Objects.requireNonNull(rightEnd.apply(right),
									"The rightEnd returned a null Publisher");
						}
						catch (Throwable exc) {
							fail(exc, a, q);
							return;
						}

						LeftRightEndSubscriber end =
								new LeftRightEndSubscriber(this, false, idx);
						cancellations.add(end);

						p.subscribe(end);

						ex = error;
						if (ex != null) {
							q.clear();
							cancelAll();
							errorAll(a);
							return;
						}

						long r = requested;
						long e = 0L;

						for (TLeft left : lefts.values()) {

							R w;

							try {
								w = Objects.requireNonNull(resultSelector.apply(left,
										right),
										"The resultSelector returned a null value");
							}
							catch (Throwable exc) {
								fail(exc, a, q);
								return;
							}

							if (e != r) {
								a.onNext(w);

								e++;
							}
							else {
								ExceptionHelper.addThrowable(ERROR, this,
										new IllegalStateException(("Could not emit " +
												"value due to lack of requests")));
								q.clear();
								cancelAll();
								errorAll(a);
								return;
							}
						}

						if (e != 0L) {
							long upd;
							for(;;) {
								if (r == Long.MAX_VALUE) {
									break;
								}
								upd = r - e;
								if (upd < 0L){
									fail(new IllegalStateException("More produced than " +
											"requested: " + upd), a, q);
									return;
								}
								if(REQUESTED.compareAndSet(this, r, upd)){
									break;
								}
								r = requested;
							}
						}
					}
					else if (mode == LEFT_CLOSE) {
						LeftRightEndSubscriber end = (LeftRightEndSubscriber) val;

						lefts.remove(end.index);
						cancellations.remove(end);
					}
					else if (mode == RIGHT_CLOSE) {
						LeftRightEndSubscriber end = (LeftRightEndSubscriber) val;

						rights.remove(end.index);
						cancellations.remove(end);
					}
				}

				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}

		@Override
		public void innerError(Throwable ex) {
			if (ExceptionHelper.addThrowable(ERROR, this, ex)) {
				ACTIVE.decrementAndGet(this);
				drain();
			}
			else {
				UnsignalledExceptions.onErrorDropped(ex);
			}
		}

		@Override
		public void innerComplete(LeftRightSubscriber sender) {
			cancellations.remove(sender);
			ACTIVE.decrementAndGet(this);
			drain();
		}

		@Override
		public void innerValue(boolean isLeft, Object o) {
			synchronized (this) {
				queueBiOffer.test(isLeft ? LEFT_VALUE : RIGHT_VALUE, o);
			}
			drain();
		}

		@Override
		public void innerClose(boolean isLeft, LeftRightEndSubscriber index) {
			synchronized (this) {
				queueBiOffer.test(isLeft ? LEFT_CLOSE : RIGHT_CLOSE, index);
			}
			drain();
		}

		@Override
		public void innerCloseError(Throwable ex) {
			if (ExceptionHelper.addThrowable(ERROR, this, ex)) {
				drain();
			}
			else {
				UnsignalledExceptions.onErrorDropped(ex);
			}
		}
	}
}
