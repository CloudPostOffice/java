package com.cloudpostoffice.tests;

import com.cloudpostoffice.CloudPostOffice;
import com.cloudpostoffice.Postbox;

/**
 * Publishes a message to topic {@code topic-news}.
 * Run {@link SubMain} first so a subscriber is already listening.
 *
 * <p>Usage:
 * <pre>
 *   gradle runPub
 * </pre>
 */
public class PubMain {

    public static void main(String[] args) {
        String baseUrl       = Postbox1Main.envOrDefault("CPO_BASE_URL", "http://localhost:3000");
        String postboxId     = Postbox1Main.requireEnv("CPO_TEST_POSTBOX_1_ID");
        String postboxSecret = Postbox1Main.requireEnv("CPO_TEST_POSTBOX_1_SECRET");

        CloudPostOffice.configure(new CloudPostOffice.Config(baseUrl));

        Postbox p1 = CloudPostOffice.newPostbox(postboxId, postboxSecret);

        try {
            p1.publish("topic-news", "Hello from Java SDK!");
            p1.disconnect();
            System.out.println("Published.");
        } catch (Exception e) {
            System.err.println("Publish failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
