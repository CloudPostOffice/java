package com.cloudpostoffice.tests;

import com.cloudpostoffice.CloudPostOffice;
import com.cloudpostoffice.Postbox;

/**
 * Subscribes to topic {@code topic-news} and prints incoming messages.
 * Run this before {@link PubMain}.
 *
 * <p>Usage:
 * <pre>
 *   gradle runSub
 * </pre>
 */
public class SubMain {

    public static void main(String[] args) throws InterruptedException {
        String baseUrl       = Postbox1Main.envOrDefault("CPO_BASE_URL", "http://localhost:3000");
        String postboxId     = Postbox1Main.requireEnv("CPO_TEST_POSTBOX_2_ID");
        String postboxSecret = Postbox1Main.requireEnv("CPO_TEST_POSTBOX_2_SECRET");

        CloudPostOffice.configure(new CloudPostOffice.Config(baseUrl));

        System.out.println("Connecting as postbox: " + postboxId);
        Postbox p2 = CloudPostOffice.newPostbox(postboxId, postboxSecret);

        try {
            System.out.println("Authenticating and subscribing...");
            p2.subscribe("topic-news", (topic, msg) ->
                    System.out.println(topic + " :-> " + msg));
            System.out.println("Subscribed to topic: news — waiting for messages...");
        } catch (Exception e) {
            System.err.println("Subscribe failed: " + e.getMessage());
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(p2::disconnect));
        Thread.currentThread().join();
    }
}
