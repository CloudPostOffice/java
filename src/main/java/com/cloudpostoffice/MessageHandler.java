package com.cloudpostoffice;

import java.util.Map;

/**
 * Callback for direct postbox messages received via {@link Postbox#listen}.
 *
 * <p>The map contains:
 * <ul>
 *   <li>{@code "from"} — sender postbox ID (String)</li>
 *   <li>{@code "msg"}  — message payload (any JSON-deserialisable value)</li>
 *   <li>{@code "ts"}   — server timestamp in milliseconds (Long)</li>
 * </ul>
 */
@FunctionalInterface
public interface MessageHandler {
    void onMessage(Map<String, Object> message);
}
