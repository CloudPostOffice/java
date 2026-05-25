package com.cloudpostoffice;

/**
 * Callback for pub/sub topic messages received via {@link Postbox#subscribe}.
 *
 * @param topicName the user-facing topic name (without internal path prefix)
 * @param message   the deserialized message payload
 */
@FunctionalInterface
public interface TopicHandler {
    void onMessage(String topicName, Object message);
}
