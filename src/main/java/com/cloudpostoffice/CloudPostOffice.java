package com.cloudpostoffice;

import java.util.regex.Pattern;

/**
 * Entry point for the CloudPostOffice Java SDK.
 *
 * <p>Quick start:
 * <pre>{@code
 * Postbox p1 = CloudPostOffice.newPostbox("proj-xxx--postbox-1", "your-secret");
 * Postbox p2 = CloudPostOffice.newPostbox("proj-xxx--postbox-2", "your-secret");
 *
 * // Direct messaging
 * p2.listen(msg -> System.out.println(msg));
 * p1.send("proj-xxx--postbox-2", "hello");
 *
 * // Pub / Sub
 * p1.subscribe("news", (topic, msg) -> System.out.println(topic + " " + msg));
 * p2.publish("news", "CloudPostOffice is alive!");
 * }</pre>
 */
public final class CloudPostOffice {

    private static final Pattern LOCALHOST_RE =
            Pattern.compile("^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?");

    private static final Object lock = new Object();
    private static String  sdkBaseUrl     = "https://cloudpostoffice.com";
    private static Postbox defaultPostbox = null;

    private CloudPostOffice() {}

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Holds SDK-level configuration. Call {@link #configure(Config)} before
     * creating any postboxes to override defaults.
     */
    public static final class Config {
        /** Base URL of the CloudPostOffice API. Defaults to {@code https://cloudpostoffice.com}. */
        public final String baseUrl;

        public Config(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /**
     * Overrides SDK-level options. Must be called before creating any postboxes.
     *
     * @param cfg configuration to apply
     * @throws IllegalArgumentException if {@code baseUrl} is not https (unless localhost)
     */
    public static void configure(Config cfg) {
        if (cfg == null || cfg.baseUrl == null || cfg.baseUrl.isEmpty()) {
            return;
        }
        boolean isLocalhost = LOCALHOST_RE.matcher(cfg.baseUrl).find();
        if (!isLocalhost && !cfg.baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "baseUrl must use https:// (http:// is only allowed for localhost)");
        }
        synchronized (lock) {
            sdkBaseUrl = cfg.baseUrl;
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates a postbox handle. Automatically authenticates and connects to the
     * MQTT broker on first use — no manual setup required.
     *
     * <p>The first postbox created becomes the default for the top-level
     * {@link #publish} and {@link #subscribe} methods.
     *
     * @param postboxId     postbox ID from the dashboard
     * @param postboxSecret secret key from the dashboard
     * @return a ready-to-use {@link Postbox}
     */
    public static Postbox newPostbox(String postboxId, String postboxSecret) {
        if (postboxId == null || postboxId.isEmpty() || postboxSecret == null || postboxSecret.isEmpty()) {
            throw new IllegalArgumentException("newPostbox() requires both a postboxId and a postboxSecret");
        }
        String base;
        synchronized (lock) {
            base = sdkBaseUrl;
        }

        Postbox p = new Postbox(postboxId, postboxSecret, base);

        synchronized (lock) {
            if (defaultPostbox == null) {
                defaultPostbox = p;
            }
        }
        return p;
    }

    // ── Convenience (default postbox) ─────────────────────────────────────────

    /**
     * Publishes a message to a named topic using the default postbox.
     * Call {@link #newPostbox} at least once before using this method.
     */
    public static void publish(String topicName, Object message) throws Exception {
        Postbox p = getDefault();
        p.publish(topicName, message);
    }

    /**
     * Subscribes to a named topic using the default postbox.
     * Call {@link #newPostbox} at least once before using this method.
     */
    public static void subscribe(String topicName, TopicHandler callback) throws Exception {
        Postbox p = getDefault();
        p.subscribe(topicName, callback);
    }

    private static Postbox getDefault() {
        Postbox p;
        synchronized (lock) {
            p = defaultPostbox;
        }
        if (p == null) {
            throw new IllegalStateException("Call CloudPostOffice.newPostbox() before publish() or subscribe()");
        }
        return p;
    }
}
