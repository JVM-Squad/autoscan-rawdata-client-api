package no.ssb.rawdata.api.persistence;

import no.ssb.rawdata.api.state.CompletedPosition;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class CompletedPositionPublisher implements Publisher<CompletedPosition> {
    final Deque<CompletedPosition> resultSet;

    public CompletedPositionPublisher(Deque<CompletedPosition> resultSet) {
        this.resultSet = resultSet;
    }

    @Override
    public void subscribe(Subscriber<? super CompletedPosition> subscriber) {
        subscriber.onSubscribe(new ResultSetSubscription(subscriber));
        if (resultSet == null) {
            subscriber.onError(new NullPointerException("resultSet"));
        }
    }

    class ResultSetSubscription implements Subscription {
        final AtomicLong requested = new AtomicLong();
        final AtomicLong published = new AtomicLong();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<Subscriber<? super CompletedPosition>> subscriber = new AtomicReference<>();
        final AtomicBoolean publicationPending = new AtomicBoolean();

        ResultSetSubscription(Subscriber<? super CompletedPosition> subscriber) {
            this.subscriber.set(subscriber);
        }

        @Override
        public void request(long n) {
            try {
                if (n <= 0) {
                    throw new IllegalArgumentException("requested amount must be > 0");
                }
                if (cancelled.get()) {
                    return;
                }
                long r, newRequestedValue;
                do {
                    r = requested.get();
                    if (Long.MAX_VALUE - r < n) {
                        // overflow
                        newRequestedValue = Long.MAX_VALUE;
                    } else {
                        newRequestedValue = r + n;
                    }
                } while (!requested.compareAndSet(r, newRequestedValue));
                queuePublicationRequest(() -> iterate());
            } catch (Throwable t) {
                Subscriber<? super CompletedPosition> subscriber = this.subscriber.get();
                if (subscriber == null) {
                    // asynchronously cancelled
                    return;
                }
                subscriber.onError(t);
            }
        }

        private void iterate() {
            Subscriber<? super CompletedPosition> subscriber = this.subscriber.get();
            if (subscriber == null) {
                // asynchronously cancelled
                return;
            }
            CompletedPosition next;
            try {
                next = resultSet.pollFirst();
            } catch (Throwable t) {
                subscriber.onError(t);
                return;
            }
            if (next != null) {
                try {
                    subscriber.onNext(next);
                    published.incrementAndGet();
                } catch (Throwable t) {
                    subscriber.onError(t);
                } finally {
                    publicationPending.set(false);
                }
                queuePublicationRequest(() -> iterate());
            } else {
                subscriber.onComplete();
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            subscriber.set(null);
        }

        public void queuePublicationRequest(Runnable runnable) {
            if (cancelled.get()) {
                return;
            }
            // loop until stable. Instability is caused by concurrency
            for (; ; ) {
                if (!publicationPending.compareAndSet(false, true)) {
                    return; // a publication is already pending
                }
                long p = published.get();
                long r = requested.get();
                if (r > p) {
                    // available budget
                    runnable.run(); // runnable should eventually cause onNext publication
                    return;
                } else {
                    publicationPending.set(false);
                    // re-check whether requested count has increased while publicationPending flag was set
                    if (r == requested.get()) {
                        // requested count has not changed
                        return;
                    }
                }
            }
        }
    }
}
