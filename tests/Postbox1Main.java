package com.cloudpostoffice.tests;

import com.cloudpostoffice.CloudPostOffice;
import com.cloudpostoffice.Postbox;

/**
 * Sends a direct message to postbox-2.
 * Run {@link Postbox2Main} first so postbox-2 is already listening.
 *
 * <p>Usage:
 * <pre>
 *   gradle runPostbox1
 * </pre>
 */
public class Postbox1Main {

    public static void main(String[] args) {
        String baseUrl        = envOrDefault("CPO_BASE_URL", "http://localhost:3000");
        String postboxId      = requireEnv("CPO_TEST_POSTBOX_1_ID");
        String postboxSecret  = requireEnv("CPO_TEST_POSTBOX_1_SECRET");
        String targetId       = requireEnv("CPO_TEST_POSTBOX_2_ID");

        CloudPostOffice.configure(new CloudPostOffice.Config(baseUrl));

        Postbox p1 = CloudPostOffice.newPostbox(postboxId, postboxSecret);

        try {
            p1.send(targetId, "Hello from Postbox 1!");
            p1.disconnect();
            System.out.println("Message sent.");
        } catch (Exception e) {
            System.err.println("Failed to send: " + e.getMessage());
            System.exit(1);
        }
    }

    static String envOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isEmpty()) {
            System.err.println("Required env var " + key + " is not set");
            System.exit(1);
        }
        return v;
    }
}
