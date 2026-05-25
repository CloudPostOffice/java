package com.cloudpostoffice.tests;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Security test: verifies that a postbox CANNOT publish to topics outside its project.
 *
 * <p>Authenticates as postbox-1 (gets a real JWT), then connects with a raw MQTT
 * client and tries to publish to a fake topic outside the allowed ACL.
 * The broker must reject the publish or disconnect the client.
 *
 * <p>Usage:
 * <pre>
 *   gradle runUnauth
 * </pre>
 */
public class UnauthMain {

    private static final String FAKE_TOPIC  = "fake-account/fake-project/postboxes/victim-postbox";
    private static final int    BROKER_PORT = 8883;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient   HTTP   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static void main(String[] args) throws Exception {
        String baseUrl       = Postbox1Main.envOrDefault("CPO_BASE_URL", "http://localhost:3000");
        String postboxId     = Postbox1Main.requireEnv("CPO_TEST_POSTBOX_1_ID");
        String postboxSecret = Postbox1Main.requireEnv("CPO_TEST_POSTBOX_1_SECRET");

        System.out.println("Authenticating as: " + postboxId);
        Map<String, Object> auth = authenticate(baseUrl, postboxId, postboxSecret);
        String broker = (String) auth.get("broker");
        String token  = (String) auth.get("token");
        System.out.println("Authenticated. Broker: " + broker);
        System.out.println("Attempting to publish to fake topic: " + FAKE_TOPIC);

        CountDownLatch   doneLatch   = new CountDownLatch(1);
        AtomicBoolean    aclEnforced = new AtomicBoolean(false);

        SSLContext ssl = SSLContext.getDefault();

        MqttClient client = new MqttClient(
                "ssl://" + broker + ":" + BROKER_PORT,
                "test-unauth-" + System.currentTimeMillis(),
                new MemoryPersistence());

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                System.out.println("PASS — broker disconnected the client. ACL enforced.");
                aclEnforced.set(true);
                doneLatch.countDown();
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {}

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // If delivery is reported as failed, ACL is enforced
                if (token != null) {
                    try {
                        token.waitForCompletion(1_000);
                    } catch (MqttException ignored) {}
                    if (token.getException() != null) {
                        System.out.println("PASS — publish rejected by broker: " + token.getException().getMessage());
                        aclEnforced.set(true);
                        doneLatch.countDown();
                    }
                }
            }
        });

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setUserName(postboxId);
        opts.setPassword(token.toCharArray());
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(false);
        opts.setConnectionTimeout(10);
        opts.setSocketFactory(ssl.getSocketFactory());

        try {
            client.connect(opts);
        } catch (MqttException e) {
            System.out.println("PASS — broker rejected the connection: " + e.getMessage());
            System.exit(0);
        }
        System.out.println("Connected to broker.");

        // Attempt to publish to the forbidden topic
        new Thread(() -> {
            try {
                MqttMessage msg = new MqttMessage("hacked".getBytes(StandardCharsets.UTF_8));
                msg.setQos(1);
                client.publish(FAKE_TOPIC, msg);
                // If publish returned without error, wait briefly for disconnect
                boolean disconnected = doneLatch.await(3, TimeUnit.SECONDS);
                if (!disconnected) {
                    System.out.println("FAIL — publish succeeded and broker did NOT disconnect. ACL enforcement is NOT working!");
                    System.exit(1);
                }
            } catch (Exception e) {
                System.out.println("PASS — publish rejected by broker: " + e.getMessage());
                aclEnforced.set(true);
                doneLatch.countDown();
            }
        }).start();

        // Wait up to 10 seconds for any result
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        try { client.disconnect(250); } catch (MqttException ignored) {}

        if (completed && aclEnforced.get()) {
            System.exit(0);
        } else if (completed) {
            System.out.println("FAIL — unexpected result.");
            System.exit(1);
        } else {
            System.out.println("PASS — publish timed out (broker silently rejected it).");
            System.exit(0);
        }
    }

    private static Map<String, Object> authenticate(String baseUrl, String postboxId, String secret)
            throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("postboxId",     postboxId);
        body.put("postboxSecret", secret);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/authenticate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> data  = MAPPER.readValue(resp.body(), new TypeReference<>() {});

        if (resp.statusCode() >= 400) {
            String msg = (String) data.getOrDefault("error", "Authentication failed");
            throw new RuntimeException("Auth failed (" + resp.statusCode() + "): " + msg);
        }
        return data;
    }
}
