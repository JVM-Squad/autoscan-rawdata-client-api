package no.ssb.rawdata.api;

import de.huxhorn.sulky.ulid.ULID;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface RawdataProducer extends AutoCloseable {

    /**
     * @return the topic on which this producer will publish messages.
     */
    String topic();

    /**
     * Constructs a builder that can be used to build the content of a message.
     *
     * @return the builder
     * @throws RawdataClosedException if the producer was closed before this call.
     */
    RawdataMessage.Builder builder() throws RawdataClosedException;

    /**
     * Buffer the content of a message, preparing it for publication to rawdata using one of the publish methods.
     *
     * @param builder a builder used to build the message that will be buffered
     * @return this instance
     * @throws RawdataClosedException if the producer was closed before this call.
     */
    RawdataProducer buffer(RawdataMessage.Builder builder) throws RawdataClosedException;

    /**
     * Publish all buffered content that matches any of the positions here provided, then remove those contents from
     * the buffer. Published content will be assigned a message-id that is available in the returned list of messages.
     *
     * @param positions a list of positions
     * @throws RawdataClosedException      if the producer was closed before or during this call.
     * @throws RawdataNotBufferedException if one or more of the positions provided by the positions param
     *                                     was not buffered before calling publish.
     */
    default void publish(List<String> positions) throws RawdataClosedException, RawdataNotBufferedException {
        publish(positions.toArray(new String[positions.size()]));
    }

    /**
     * Publish all buffered content that matches any of the positions here provided, then remove those contents from
     * the buffer. Published content will be assigned a message-id that is available in the returned list of messages.
     *
     * @param positions a list of positions
     * @throws RawdataClosedException      if the producer was closed before or during this call.
     * @throws RawdataNotBufferedException if one or more of the positions provided by the positions param
     *                                     was not buffered before calling publish.
     */
    void publish(String... positions) throws RawdataClosedException, RawdataNotBufferedException;

    /**
     * Asynchronously publish all buffered content that matches any of the positions here provided, then remove those contents from
     * the buffer. Published content will be assigned a message-id that is available in the returned list of messages.
     *
     * @param positions a list of positions
     * @return a completable futures representing the completeness of the async-function.
     */
    default CompletableFuture<Void> publishAsync(List<String> positions) {
        return publishAsync(positions.toArray(new String[positions.size()]));
    }

    /**
     * Asynchronously publish all buffered content that matches any of the positions here provided, then remove those contents from
     * the buffer. Published content will be assigned a message-id that is available in the returned list of messages.
     *
     * @param positions a list of positions
     * @return a completable futures representing the completeness of the async-function.
     */
    CompletableFuture<Void> publishAsync(String... positions);

    /**
     * Returns whether or not the producer is closed.
     *
     * @return whether the producer is closed.
     */
    boolean isClosed();

    /**
     * Generate a new unique ulid. If the newly generated ulid has a new timestamp than the previous one, then the very
     * least significant bit will be set to 1 (which is higher than beginning-of-time ulid used by consumer).
     *
     * @param generator    the ulid generator
     * @param previousUlid the previous ulid in the sequence
     * @return the generated ulid
     */
    static ULID.Value nextMonotonicUlid(ULID generator, ULID.Value previousUlid) {
        /*
         * Will spin until time ticks if next value overflows.
         * Although theoretically possible, it is extremely unlikely that the loop will ever spin
         */
        ULID.Value value;
        do {
            long timestamp = System.currentTimeMillis();
            if (previousUlid.timestamp() > timestamp) {
                throw new IllegalStateException("Previous timestamp is in the future");
            } else if (previousUlid.timestamp() != timestamp) {
                // start at lsb 1, to avoid inclusive/exclusive semantics when searching
                return new ULID.Value((timestamp << 16) & 0xFFFFFFFFFFFF0000L, 1L);
            }
            // previousUlid.timestamp() == timestamp
            value = generator.nextStrictlyMonotonicValue(previousUlid, timestamp).orElse(null);
        } while (value == null);
        return value;
    }
}
