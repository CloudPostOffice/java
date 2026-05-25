package com.cloudpostoffice;

import com.cloudpostoffice.exceptions.AuthenticationException;
import com.cloudpostoffice.exceptions.CloudPostOfficeException;
import com.cloudpostoffice.exceptions.ConnectionTimeoutException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A handle for a single CloudPostOffice postbox.
 *
 * <p>Authenticates automatically on first use and maintains a persistent MQTT
 * connection (MQTT v3.1.1, QoS 1, clean-session off) with exponential-backoff
 * reconnection and automatic token refresh.
 *
 * <pre>{@code
 * Postbox p1 = CloudPostOffice.newPostbox("proj-xxx--postbox-1", "your-secret");
 * Postbox p2 = CloudPostOffice.newPostbox("proj-xxx--postbox-2", "your-secret");
 *
 * p2.listen(msg -> System.out.println(msg));
 * p1.send("proj-xxx--postbox-2", "hello");
 * }</pre>
 */
public class Postbox {

    private static final int    BROKER_PORT        = 8883;
    private static final long   CONNECT_TIMEOUT_MS = 15_000;
    private static final long   BACKOFF_INIT_MS    = 1_000;
    private static final long   BACKOFF_MAX_MS     = 60_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient   HTTP   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final String id;
    private final String secret;
    private final String baseUrl;

    // ── Connection state ──────────────────────────────────────────────────────

    private final Object         mqttLock  = new Object();
    private volatile MqttClient  mqttClient;
    private volatile String      accountRef;
    private volatile String      projectId;
    private volatile AuthResponse currentAuth;

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    private final AtomicBoolean intentionalDisconnect = new AtomicBoolean(false);
    private final AtomicBoolean connStarted           = new AtomicBoolean(false);
    private final CountDownLatch connReady            = new CountDownLatch(1);
    private volatile Exception   connError;

    // Wakes the connection loop when MQTT reports a lost connection
    private final Object  disconnectMonitor = new Object();
    private volatile boolean connectionLost = false;

    Postbox(String id, String secret, String baseUrl) {
        this.id      = id;
        this.secret  = secret;
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends a direct message to another postbox on the same account/project.
     *
     * @param to  target postbox ID
     * @param msg message payload (any JSON-serialisable value)
     */
    public void send(String to, Object msg) throws Exception {
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("send() requires a non-empty target postbox ID");
        }
        ensureConnected();
        String topic = buildTopic("postboxes/" + to);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("from", id);
        envelope.put("msg",  msg);
        envelope.put("ts",   System.currentTimeMillis());
        mqttPublish(topic, MAPPER.writeValueAsString(envelope));
    }

    /**
     * Registers a callback for messages addressed to this postbox.
     * May be called multiple times to add multiple handlers.
     *
     * @param callback receives a map with {@code "from"}, {@code "msg"}, and {@code "ts"}
     */
    public void listen(MessageHandler callback) throws Exception {
        ensureConnected();
        String topic = buildTopic("postboxes/" + id);
        mqttSubscribe(topic, raw -> {
            if (raw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) raw;
                callback.onMessage(m);
            }
        });
    }

    /**
     * Publishes a message to a named topic.
     * Topic names must not contain {@code /}, {@code +}, {@code #}, or {@code --}.
     *
     * @param topicName user-facing topic name
     * @param message   any JSON-serialisable value
     */
    public void publish(String topicName, Object message) throws Exception {
        validateTopicName(topicName);
        ensureConnected();
        String topic   = buildTopic("topic/" + topicName);
        String payload = (message instanceof String) ? (String) message
                                                      : MAPPER.writeValueAsString(message);
        mqttPublish(topic, payload);
    }

    /**
     * Subscribes to a named topic.
     * The callback receives {@code (topicName, message)}.
     * May be called multiple times to add multiple handlers.
     *
     * @param topicName user-facing topic name
     * @param callback  {@link TopicHandler}
     */
    public void subscribe(String topicName, TopicHandler callback) throws Exception {
        validateTopicName(topicName);
        ensureConnected();
        String topic = buildTopic("topic/" + topicName);
        mqttSubscribe(topic, msg -> callback.onMessage(topicName, msg));
    }

    /**
     * Gracefully closes the MQTT connection.
     */
    public void disconnect() {
        intentionalDisconnect.set(true);
        synchronized (disconnectMonitor) {
            connectionLost = true;
            disconnectMonitor.notifyAll();
        }
        synchronized (mqttLock) {
            if (mqttClient != null) {
                try { mqttClient.disconnect(1_000); } catch (MqttException ignored) {}
                mqttClient = null;
            }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    void ensureConnected() throws Exception {
        if (connStarted.compareAndSet(false, true)) {
            Thread t = new Thread(this::connectionLoop, "cpo-conn-" + id);
            t.setDaemon(true);
            t.start();
        }

        if (!connReady.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new ConnectionTimeoutException(id);
        }
        if (connError != null) {
            throw connError;
        }
    }

    private void connectionLoop() {
        long    delay        = BACKOFF_INIT_MS;
        boolean firstConnect = true;

        while (!intentionalDisconnect.get()) {
            synchronized (disconnectMonitor) {
                connectionLost = false;
            }

            try {
                AuthResponse auth = doAuthenticate();
                this.currentAuth = auth;
                String[] parts = auth.clientId.split(":", 3);
                this.accountRef = parts[0];
                this.projectId  = parts[1];

                MqttClient client = createAndConnectMqtt(auth);
                synchronized (mqttLock) {
                    this.mqttClient = client;
                }

                resubscribeAll(client);

                if (firstConnect) {
                    firstConnect = false;
                    connReady.countDown();
                    delay = BACKOFF_INIT_MS;
                }

                // Block this thread until the broker signals a disconnect
                synchronized (disconnectMonitor) {
                    while (!connectionLost && !intentionalDisconnect.get()) {
                        disconnectMonitor.wait();
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (firstConnect) {
                    connError = e;
                    connReady.countDown();
                    return;
                }
                if (!intentionalDisconnect.get()) {
                    System.err.printf("[cloudpostoffice] connection error (%s). Reconnecting in %dms...%n",
                            e.getMessage(), delay);
                }
            }

            if (!intentionalDisconnect.get()) {
                try {
                    Thread.sleep(delay);
                    delay = Math.min(delay * 2, BACKOFF_MAX_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private MqttClient createAndConnectMqtt(AuthResponse auth) throws Exception {
        String    brokerUri = "ssl://" + auth.broker + ":" + BROKER_PORT;
        MqttClient client   = new MqttClient(brokerUri, auth.clientId, new MemoryPersistence());

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                if (!intentionalDisconnect.get()) {
                    System.err.printf("[cloudpostoffice] connection lost: %s; reconnecting...%n",
                            cause != null ? cause.getMessage() : "unknown");
                }
                synchronized (disconnectMonitor) {
                    connectionLost = true;
                    disconnectMonitor.notifyAll();
                }
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                dispatch(topic, message.getPayload());
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        SSLContext ssl = SSLContext.getDefault();

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setUserName(id);
        opts.setPassword(auth.token.toCharArray());
        opts.setCleanSession(false);
        opts.setAutomaticReconnect(false);
        opts.setConnectionTimeout(15);
        opts.setKeepAliveInterval(30);
        opts.setSocketFactory(ssl.getSocketFactory());

        client.connect(opts);
        return client;
    }

    private AuthResponse doAuthenticate() throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("postboxId",     id);
        body.put("postboxSecret", secret);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/authenticate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new CloudPostOfficeException("Network error during authentication: " + e.getMessage(), e);
        }

        Map<String, Object> data = MAPPER.readValue(resp.body(), new TypeReference<>() {});

        if (resp.statusCode() >= 400) {
            String msg = (String) data.getOrDefault("error", "Authentication failed");
            throw new AuthenticationException(msg, resp.statusCode());
        }

        AuthResponse auth = new AuthResponse();
        auth.token    = (String) data.get("token");
        auth.broker   = (String) data.get("broker");
        auth.clientId = (String) data.get("clientId");
        return auth;
    }

    private void resubscribeAll(MqttClient client) throws MqttException {
        for (Subscription sub : subscriptions) {
            client.subscribe(sub.topicFilter, 1);
        }
    }

    private void dispatch(String incomingTopic, byte[] payloadBytes) {
        String raw = new String(payloadBytes, StandardCharsets.UTF_8);
        Object payload;
        try {
            payload = MAPPER.readValue(raw, Object.class);
        } catch (Exception e) {
            payload = raw;
        }

        for (Subscription sub : subscriptions) {
            if (sub.topicFilter.equals(incomingTopic)) {
                try {
                    sub.callback.accept(payload);
                } catch (Exception e) {
                    System.err.printf("[cloudpostoffice] handler error for topic %s: %s%n",
                            incomingTopic, e.getMessage());
                }
            }
        }
    }

    private String buildTopic(String subtopic) {
        return accountRef + "/" + projectId + "/" + subtopic;
    }

    private void mqttPublish(String topic, String payload) throws Exception {
        MqttClient client;
        synchronized (mqttLock) {
            client = mqttClient;
        }
        if (client == null || !client.isConnected()) {
            throw new CloudPostOfficeException("Not connected to MQTT broker");
        }
        MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        msg.setQos(1);
        client.publish(topic, msg);
    }

    private void mqttSubscribe(String topicFilter, Consumer<Object> callback) throws Exception {
        subscriptions.add(new Subscription(topicFilter, callback));
        MqttClient client;
        synchronized (mqttLock) {
            client = mqttClient;
        }
        if (client != null && client.isConnected()) {
            client.subscribe(topicFilter, 1);
        }
    }

    private static void validateTopicName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Topic name must be a non-empty string");
        }
        for (String ch : new String[]{"/", "+", "#"}) {
            if (name.contains(ch)) {
                throw new IllegalArgumentException(
                        "Topic name must not contain \"" + ch + "\": \"" + name + "\"");
            }
        }
        if (name.contains("--")) {
            throw new IllegalArgumentException(
                    "Topic name must not contain \"--\": \"" + name + "\"");
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static final class AuthResponse {
        String token;
        String broker;
        String clientId;
    }

    private static final class Subscription {
        final String           topicFilter;
        final Consumer<Object> callback;

        Subscription(String topicFilter, Consumer<Object> callback) {
            this.topicFilter = topicFilter;
            this.callback    = callback;
        }
    }
}
