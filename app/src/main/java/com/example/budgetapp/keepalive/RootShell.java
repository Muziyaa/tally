package com.example.budgetapp.keepalive;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Runs a shell command with root privileges through {@code su}.
 *
 * <p><b>Always call this from a background thread.</b> The first request makes the
 * root manager show a permission prompt, and this call blocks until the user answers
 * it or the timeout expires.
 */
public final class RootShell {

    /** Generous: the first call has to wait for the user to tap "allow" in Magisk. */
    public static final long DEFAULT_TIMEOUT_MS = 60_000L;

    /** Short timeout for follow-up probes, which cannot prompt once access is granted. */
    public static final long PROBE_TIMEOUT_MS = 15_000L;

    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
            "/data/adb/ap/bin/su"
    };

    private RootShell() {
    }

    /**
     * Whether a {@code su} binary is visible at a well-known path.
     *
     * <p>This never triggers a prompt, so it is safe to call anywhere. A {@code false}
     * result does <b>not</b> prove the device is unrooted: root hiding (DenyList,
     * Shamiko, ...) can make the binary invisible to us. Use {@link RootDiagnostics}
     * for a verdict.
     */
    public static boolean suBinaryPresent() {
        for (String path : SU_PATHS) {
            try {
                if (new File(path).exists()) {
                    return true;
                }
            } catch (Exception ignored) {
                // Treat an unreadable path as absent.
            }
        }
        return false;
    }

    public static Result run(String command) {
        return run(command, DEFAULT_TIMEOUT_MS);
    }

    public static Result run(String command, long timeoutMs) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("su", "-c", command);
            builder.redirectErrorStream(false);
            process = builder.start();

            // Drain both pipes concurrently. Waiting first would deadlock as soon as the
            // child fills a pipe buffer (a large `dumpsys` easily does).
            StreamDrainer out = new StreamDrainer(process.getInputStream());
            StreamDrainer err = new StreamDrainer(process.getErrorStream());
            out.start();
            err.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                out.join(1000);
                err.join(1000);
                return new Result(out.text(), err.text(), -1, true);
            }

            out.join(2000);
            err.join(2000);
            return new Result(out.text(), err.text(), process.exitValue(), false);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return new Result("", message, -1, false);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** Immutable outcome of one {@code su} invocation. */
    public static final class Result {
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final boolean timedOut;

        Result(String stdout, String stderr, int exitCode, boolean timedOut) {
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return !timedOut && exitCode == 0;
        }

        public String output() {
            return stdout.trim();
        }

        /** stdout plus stderr, for diagnostic reports. */
        public String combined() {
            StringBuilder sb = new StringBuilder(stdout.trim());
            String err = stderr.trim();
            if (!err.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(err);
            }
            return sb.toString();
        }
    }

    private static final class StreamDrainer extends Thread {
        private final InputStream stream;
        private final StringBuilder buffer = new StringBuilder();

        StreamDrainer(InputStream stream) {
            this.stream = stream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // A destroyed process closes its pipes; nothing useful to report.
            }
        }

        String text() {
            return buffer.toString();
        }
    }
}
