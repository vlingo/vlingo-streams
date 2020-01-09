// Copyright © 2012-2018 Vaughn Vernon. All rights reserved.
//
// This Source Code Form is subject to the terms of the
// Mozilla Public License, v. 2.0. If a copy of the MPL
// was not distributed with this file, You can obtain
// one at https://mozilla.org/MPL/2.0/.

package io.vlingo.reactivestreams;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.vlingo.actors.Actor;
import io.vlingo.actors.Stoppable;
import io.vlingo.common.Completes;
import io.vlingo.common.Scheduled;
import io.vlingo.reactivestreams.sink.ConsumerSink;

/**
 * A {@code Processor<T,R>} implementation, where as a {@code Subscriber<T>} I consumes from an
 * upstream {@code Publisher<T>}, transform those signals using {@code Transformer<T,R>},
 * and emit new signals via my own {@code Publisher<R>}.
 * <p>
 * My instances reuse {@code StreamSubscriberDelegate<T>} and {@code StreamPublisherDelegate<R>}.
 *
 * @param <T> the type that the Subscriber side consumes
 * @param <R> the type that the Publisher side emits
 */
public class StreamProcessor<T,R> extends Actor implements Processor<T,R>, ControlledSubscription<R>, Scheduled<Void>, Stoppable {
  private final StreamPublisherDelegate<R> publisherDelegate;
  private final PublisherSource publisherSource;
  private final long requestThreshold;
  private final StreamSubscriberDelegate<T> subscriberDelegate;

  /**
   * Construct my default state with {@code transformer}, {@code requestThreshold}, and {@code configuration}.
   * @param transformer the {@code Transformer<T,R>} that transforms an instance of T to an instance of R
   * @param requestThreshold the long number of signals accepted by my subscription
   * @param configuration the PublisherConfiguration used by my publisher
   */
  @SuppressWarnings("unchecked")
  public StreamProcessor(
          final Transformer<T,R> transformer,
          final long requestThreshold,
          final PublisherConfiguration configuration) {
    this.requestThreshold = requestThreshold;
    this.subscriberDelegate = new StreamSubscriberDelegate<>(new ConsumerSink<>(new ConsumerTransformer(transformer)), requestThreshold, logger());
    this.publisherSource = new PublisherSource();
    this.publisherDelegate = new StreamPublisherDelegate<>(publisherSource, configuration, selfAs(ControlledSubscription.class), scheduler(), selfAs(Scheduled.class), selfAs(Stoppable.class));
  }

  //===================================
  // Subscriber
  //===================================

  @Override
  public void onSubscribe(final Subscription subscription) {
    // System.out.println("PROCESSOR-ONSUBSCRIBE: " + subscription);

    subscriberDelegate.onSubscribe(subscription);
  }

  @Override
  public void onNext(final T value) {
    // System.out.println("PROCESSOR-ONNEXT: " + value);

    subscriberDelegate.onNext(value);
  }

  @Override
  public void onComplete() {
    // System.out.println("PROCESSOR-ONCOMPLETE");

    this.subscriberDelegate.onComplete();

    publisherSource.termiante();
  }

  @Override
  public void onError(final Throwable cause) {
    // System.out.println("PROCESSOR-ONERROR: " + cause.getMessage());

    publisherDelegate.publish(cause);

    subscriberDelegate.onError(cause);

    publisherSource.termiante();
  }

  //===================================
  // Publisher
  //===================================

  @Override
  public void subscribe(final Subscriber<? super R> subscriber) {
    // System.out.println("PROCESSOR-ONSUBSCRIBE: " + subscriber);

    publisherDelegate.subscribe(subscriber);
  }

  //===================================
  // Scheduled
  //===================================

  @Override
  public void intervalSignal(Scheduled<Void> scheduled, Void data) {
    publisherDelegate.processNext();
  }

  //===================================
  // ControlledSubscription
  //===================================

  @Override
  public void cancel(final SubscriptionController<R> controller) {
    publisherDelegate.cancel(controller);
  }

  @Override
  public void request(final SubscriptionController<R> controller, final long maximum) {
    publisherDelegate.request(controller, maximum);
  }

  //===================================
  // Stoppable
  //===================================

  @Override
  public void stop() {
    publisherSource.termiante();

    super.stop();
  }

  //===================================
  // ConsumerTransformer
  //===================================

  private class ConsumerTransformer implements Consumer<T> {
    private final Transformer<T,R> transformer;

    ConsumerTransformer(final Transformer<T,R> transformer) {
      this.transformer = transformer;
    }

    @Override
    public void accept(final T value) {
      try {
        transformer.transform(value).andFinallyConsume(transformed -> publisherSource.enqueue(transformed));
      } catch (Exception e) {
        publisherDelegate.publish(e);
      }
    }
  }

  //===================================
  // PublisherSource
  //===================================

  private class PublisherSource implements Source<R> {
    private boolean terminated;
    private final Queue<R> values;

    PublisherSource() {
      this.values = new ArrayDeque<>();
      this.terminated = false;
    }

    @Override
    public Completes<Elements<R>> next() {
      if (values.isEmpty()) {
        if (subscriberDelegate.isFinalized() || terminated) {
          return Completes.withSuccess(Elements.terminated());
        }
        return Completes.withSuccess(Elements.empty());
      }

      return Completes.withSuccess(Elements.of(nextValues()));
    }

    @Override
    public Completes<Elements<R>> next(final long index) {
      return next();
    }

    @Override
    public Completes<Boolean> isSlow() {
      return Completes.withSuccess(false);
    }

    void enqueue(final R value) {
      values.add(value);
    }

    void termiante() {
      terminated = true;

      values.clear();
    }

    private R[] nextValues() {
      final long elements = Math.min(values.size(), requestThreshold);
      @SuppressWarnings("unchecked")
      final R[] nextValues = (R[]) new Object[(int) elements];
      for (int idx = 0; idx < nextValues.length; ++idx) {
        nextValues[idx] = values.poll();
      }
      return nextValues;
    }
  }
}