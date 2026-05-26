package com.cloudpostoffice.tests;

import com.cloudpostoffice.CloudPostOffice;
import com.cloudpostoffice.Postbox;

/**
 * Listens for direct messages addressed to postbox-2.
 * Start this before {@link Postbox1Main}.
 *
 * <p>Usage:
 * <pre>
 *   gradle runPostbox2
 * </pre>
 */
public class Postbox2Main {

    public static void main(String[] args) throws InterruptedException {
        String baseUrl       = TestEnv.envOrDefault("CPO_BASE_URL", "http://localhost:3000");
        String postboxId     = TestEnv.requireEnv("CPO_TEST_POSTBOX_2_ID");
        String postboxSecret = TestEnv.requireEnv("CPO_TEST_POSTBOX_2_SECRET");

        CloudPostOffice.configure(new CloudPostOffice.Config(baseUrl));

        System.out.println("Connecting as postbox: " + postboxId);
        Postbox p2 = CloudPostOffice.newPostbox(postboxId, postboxSecret);

        try {
            System.out.println("Authenticating...");
            p2.listen(msg -> System.out.println("Received: " + msg));
            System.out.println("Postbox 2 listening for messages...");
        } catch (Exception e) {
            System.err.println("Listen error: " + e.getMessage());
            System.exit(1);
        }

        // Block until interrupted
        Runtime.getRuntime().addShutdownHook(new Thread(p2::disconnect));
        Thread.currentThread().join();
    }
}
