package com.cloudpostoffice.tests;

/** Shared environment-variable helpers for all standalone test mains. */
class TestEnv {

    private TestEnv() {}

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
