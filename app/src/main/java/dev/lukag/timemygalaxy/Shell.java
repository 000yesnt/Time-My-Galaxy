package dev.lukag.timemygalaxy;

import java.io.IOException;
import java.io.InputStream;

/**
 * Runs shell commands through Shizuku as uid 2000, or through su. Commands are
 * captured with merged stdout and stderr and always have a timeout.
 */
final class Shell {

    interface Log {
        void line(String text);
    }

    static final class Result {
        final String out;
        final boolean timedOut;

        Result(String out, boolean timedOut) {
            this.out = out;
            this.timedOut = timedOut;
        }

        boolean lost() {
            return out.startsWith("error: shizuku unavailable");
        }
    }

    private Shell() {
    }

    static Result capture(String command, long timeoutMs, String dir, Log log) {
        Process process = null;
        StringBuilder out = new StringBuilder();
        try {
            process = ShizukuBridge.exec(new String[]{"/system/bin/sh", "-c", command},
                    null, dir);
            Thread stdout = drain(process.getInputStream(), out);
            Thread stderr = drain(process.getErrorStream(), out);
            // RemoteProcess.exitValue() is not an IllegalThreadStateException,
            // so Process.waitFor(timeout) cannot be used here.
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (process.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            boolean timedOut = process.isAlive();
            if (timedOut) {
                if (log != null) {
                    log.line("[!] command timed out after " + timeoutMs / 1000 + "s");
                }
                process.destroy();
            }
            stdout.join(1500);
            stderr.join(1500);
            synchronized (out) {
                if (!timedOut && !ShizukuBridge.isRunning()) {
                    return new Result("error: shizuku unavailable", false);
                }
                return new Result(out.toString(), timedOut);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("error: interrupted", false);
        } catch (Throwable t) {
            return new Result("error: " + message(t), false);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    static String run(String command, long timeoutMs, Log log) {
        return capture(command, timeoutMs, "/data/local/tmp", log).out;
    }

    static String root(String command, long timeoutMs, Log log) {
        return capture("su -c " + quote(command), timeoutMs, "/data/local/tmp", log).out;
    }

    static String quote(String value) {
        return ShizukuBridge.shellQuote(value);
    }

    static boolean isError(String out) {
        return out == null || out.trim().startsWith("error: ");
    }

    private static Thread drain(InputStream stream, StringBuilder out) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    synchronized (out) {
                        out.append(new String(buffer, 0, read));
                    }
                }
            } catch (IOException ignored) {
            }
        }, "shell-capture");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String message(Throwable t) {
        String text = t.getMessage();
        return text == null || text.isEmpty() ? t.getClass().getSimpleName() : text;
    }
}
