package com.cloudpostoffice.tests;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration test runner for the CloudPostOffice Java SDK.
 *
 * <p>Mirrors the Node.js {@code test/run-all.js}, Python {@code tests/run_all.py},
 * and Go {@code tests/run_all/main.go} structure: spawns child JVM processes and
 * checks their output for expected signals.
 *
 * <p>Setup:
 * <ol>
 *   <li>Export the required env vars in your shell (or fill in {@code .env.test} as a
 *       fallback — env vars already in the environment always take precedence).</li>
 *   <li>Run from the project root: {@code gradle runAll}</li>
 * </ol>
 *
 * <p>Required env vars:
 * <ul>
 *   <li>CPO_TEST_POSTBOX_1_ID</li>
 *   <li>CPO_TEST_POSTBOX_1_SECRET</li>
 *   <li>CPO_TEST_POSTBOX_2_ID</li>
 *   <li>CPO_TEST_POSTBOX_2_SECRET</li>
 * </ul>
 */
public class RunAll {

    private static final AtomicInteger passed = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);

    /**
     * Effective environment: system env vars merged with .env.test (system takes precedence).
     * Used for all lookups within RunAll AND passed verbatim to child processes.
     * No reflection, no mutation of System.getenv().
     */
    private static final Map<String, String> ENV = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        // 1. Start with the real OS environment
        ENV.putAll(System.getenv());

        // 2. Load .env.test as fallback (only keys not already present)
        Path root = findProjectRoot();
        loadEnvFile(root.resolve(".env.test"), ENV);

        // 3. Apply default for CPO_BASE_URL if still missing
        ENV.putIfAbsent("CPO_BASE_URL", "http://localhost:3000");

        // 4. Validate required keys
        String[] required = {
            "CPO_TEST_POSTBOX_1_ID",
            "CPO_TEST_POSTBOX_1_SECRET",
            "CPO_TEST_POSTBOX_2_ID",
            "CPO_TEST_POSTBOX_2_SECRET",
        };
        List<String> missing = new ArrayList<>();
        for (String k : required) {
            if (isEmpty(ENV.get(k))) missing.add(k);
        }
        if (!missing.isEmpty()) {
            System.err.println("Missing env vars: " + String.join(", ", missing));
            System.exit(1);
        }

        System.out.println("CloudPostOffice Java SDK — Integration Tests");
        System.out.println("Base URL  : " + ENV.get("CPO_BASE_URL"));
        System.out.println("Postbox 1 : " + ENV.get("CPO_TEST_POSTBOX_1_ID"));
        System.out.println("Postbox 2 : " + ENV.get("CPO_TEST_POSTBOX_2_ID"));

        testPubSub();
        testPostboxSendReceive();
        testUnauthPublish();

        System.out.printf("%nPassed: %d   Failed: %d%n%n", passed.get(), failed.get());
        System.exit(failed.get() > 0 ? 1 : 0);
    }

    // ── Test suites ───────────────────────────────────────────────────────────

    private static void testPubSub() throws Exception {
        section("Pub / Sub (topic bus)");

        ProcessResult subProc = spawnAndWaitFor(
                "com.cloudpostoffice.tests.SubMain",
                "Subscribed to topic",
                60_000);
        if (!subProc.signalSeen) {
            failTest("sub — subscribe", subProc.output().trim());
            subProc.kill();
            return;
        }

        ProcessResult pubResult = spawnAndWaitExit("com.cloudpostoffice.tests.PubMain", 60_000);
        if (pubResult.exitCode != 0) {
            failTest("pub — publish", pubResult.output().trim());
            subProc.kill();
            return;
        }
        passTest("pub — published successfully");

        boolean received = pollUntil(subProc, 15_000, "news", "topic-news");
        if (received) {
            passTest("sub — received message on correct topic");
        } else {
            failTest("sub — message not received", subProc.output().trim());
        }
        subProc.kill();
    }

    private static void testPostboxSendReceive() throws Exception {
        section("Postbox send / listen");

        ProcessResult p2Proc = spawnAndWaitFor(
                "com.cloudpostoffice.tests.Postbox2Main",
                "Postbox 2 listening",
                20_000);
        if (!p2Proc.signalSeen) {
            failTest("postbox2 — listen", p2Proc.output().trim());
            p2Proc.kill();
            return;
        }

        ProcessResult p1Result = spawnAndWaitExit("com.cloudpostoffice.tests.Postbox1Main", 20_000);
        if (p1Result.exitCode == 0 && p1Result.output().contains("Message sent")) {
            passTest("postbox1 — message sent");
        } else {
            failTest("postbox1 — send failed", p1Result.output().trim());
        }

        boolean received = pollUntil(p2Proc, 15_000, "Received", "Hello");
        if (received) {
            passTest("postbox2 — message received");
        } else {
            failTest("postbox2 — message not received", p2Proc.output().trim());
        }
        p2Proc.kill();
    }

    private static void testUnauthPublish() throws Exception {
        section("Security — unauthorized cross-project publish");

        ProcessResult result = spawnAndWaitExit("com.cloudpostoffice.tests.UnauthMain", 25_000);
        if (result.exitCode == 0 && result.output().contains("PASS")) {
            passTest("unauth — broker rejected unauthorized publish");
        } else {
            failTest("unauth — unauthorized publish was NOT rejected", result.output().trim());
        }
    }

    // ── Process helpers ───────────────────────────────────────────────────────

    /**
     * Spawns a child JVM process and blocks until {@code readySignal} appears in
     * its combined output, or {@code timeoutMs} elapses.
     * Always returns a {@link ProcessResult}; check {@code result.signalSeen}.
     */
    private static ProcessResult spawnAndWaitFor(
            String mainClass, String readySignal, long timeoutMs) throws Exception {

        Process proc = buildProcess(mainClass).start();
        ProcessResult result = new ProcessResult(proc);

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (result.output().contains(readySignal)) {
                result.signalSeen = true;
                return result;
            }
            if (!proc.isAlive()) break;
            Thread.sleep(200);
        }
        // Drain remaining output before returning
        Thread.sleep(200);
        result.signalSeen = result.output().contains(readySignal);
        if (!result.signalSeen) proc.destroyForcibly();
        return result;
    }

    /** Spawns a child JVM process and waits for it to exit. */
    private static ProcessResult spawnAndWaitExit(String mainClass, long timeoutMs) throws Exception {
        Process proc = buildProcess(mainClass).start();
        ProcessResult result = new ProcessResult(proc);

        boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            proc.destroyForcibly();
            result.exitCode = -1;
        } else {
            result.exitCode = proc.exitValue();
        }
        Thread.sleep(100); // let the reader thread drain
        return result;
    }

    private static ProcessBuilder buildProcess(String mainClass) {
        String java = ProcessHandle.current().info().command()
                .orElse(isWindows() ? "java.exe" : "java");
        String classpath = System.getProperty("integration.classpath",
                System.getProperty("java.class.path"));

        ProcessBuilder pb = new ProcessBuilder(java, "-cp", classpath, mainClass);
        // Replace child env entirely with our merged ENV map
        pb.environment().clear();
        pb.environment().putAll(ENV);
        pb.redirectErrorStream(true);
        return pb;
    }

    /** Polls {@code proc}'s accumulated output every 200 ms until a signal appears. */
    private static boolean pollUntil(ProcessResult proc, long timeoutMs, String... signals)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String out = proc.output();
            for (String sig : signals) {
                if (out.contains(sig)) return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    // ── Reporting helpers ─────────────────────────────────────────────────────

    private static void section(String title) {
        System.out.println();
        System.out.println(title);
        System.out.println("-".repeat(title.length()));
    }

    private static void passTest(String label) {
        System.out.println("  PASS  " + label);
        passed.incrementAndGet();
    }

    private static void failTest(String label, String reason) {
        System.out.println("  FAIL  " + label);
        if (reason != null && !reason.isEmpty()) {
            for (String line : reason.split("\n")) {
                System.out.println("        " + line);
            }
        }
        failed.incrementAndGet();
    }

    // ── Environment helpers ───────────────────────────────────────────────────

    private static Path findProjectRoot() {
        Path dir = Path.of(System.getProperty("user.dir"));
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle")) ||
                    Files.exists(dir.resolve("build.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of(".");
    }

    /**
     * Reads KEY=VALUE lines from {@code path} and adds entries to {@code target}
     * only when the key is not already present (env vars take precedence).
     */
    private static void loadEnvFile(Path path, Map<String, String> target) {
        if (!Files.exists(path)) return;
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf('=');
                if (idx < 0) continue;
                String key = line.substring(0, idx).strip();
                String val = line.substring(idx + 1).strip();
                if (!key.isEmpty() && !val.isEmpty()) {
                    target.putIfAbsent(key, val);
                }
            }
        } catch (IOException e) {
            System.err.println("[runAll] could not read .env.test: " + e.getMessage());
        }
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ── ProcessResult ─────────────────────────────────────────────────────────

    private static class ProcessResult {
        final Process      process;
        final StringBuffer buf = new StringBuffer();
        final Thread       reader;
        volatile int       exitCode   = -1;
        volatile boolean   signalSeen = false;

        ProcessResult(Process process) {
            this.process = process;
            this.reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        buf.append(line).append('\n');
                    }
                } catch (IOException ignored) {}
            }, "cpo-proc-reader");
            reader.setDaemon(true);
            reader.start();
        }

        String output() { return buf.toString(); }
        void   kill()   { process.destroyForcibly(); }
    }
}
