package dev.lukag.timemygalaxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins the KernelSU manager to 3.2.5 before a run. Best effort: failures are
 * logged and the caller continues. /data/adb and modules are untouched.
 */
final class ManagerPin {

    interface Log {
        void line(String text);
    }

    interface Shell {
        String run(String command, long timeoutMs);
    }

    static final class Result {
        final boolean pinned;
        final boolean swapped;
        final String detail;

        Result(boolean pinned, boolean swapped, String detail) {
            this.pinned = pinned;
            this.swapped = swapped;
            this.detail = detail;
        }
    }

    static final String PACKAGE = "me.weishu.kernelsu";
    static final long VERSION_CODE = 32525L;
    static final String VERSION_NAME = "v3.2.5";
    static final String APK_URL =
            "https://github.com/tiann/KernelSU/releases/download/v3.2.5/"
                    + "KernelSU_v3.2.5_32525-release.apk";
    static final String APK_SHA256 =
            "1417081413bf7ab1de8e440ecbcb62685037c8f28f048f0f8b79e305b31ab916";
    static final long APK_BYTES = 9_083_665L;
    static final String APK_NAME = "KernelSU_v3.2.5_32525-release.apk";
    static final String STAGED_APK = "/data/local/tmp/" + APK_NAME;

    private static final long MAX_APK_BYTES = 16L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private static final Pattern DUMP_CODE = Pattern.compile("versionCode=(\\d+)");
    private static final Pattern DUMP_NAME = Pattern.compile("versionName=(\\S+)");
    private static final Pattern LIST_CODE = Pattern.compile("versionCode:(\\d+)");
    private static final Pattern COMPONENT = Pattern.compile("(?m)^(\\S+/\\S+)$");

    private ManagerPin() {
    }

    static Result ensure(File filesDir, Shell shell, Log log) {
        boolean swapped = false;
        try {
            log.line("[*] manager: checking installed KernelSU");
            Installed now = read(shell);
            if (now == null) {
                log.line("[!] manager: cannot read package state; leaving it alone");
                return new Result(false, false, "unknown");
            }
            if (now.present && now.code == VERSION_CODE) {
                log.line("[+] manager: " + PACKAGE + " " + now.name
                        + " (" + now.code + ")");
                return new Result(true, false, "present");
            }
            if (!now.present) {
                log.line("[*] manager: not installed");
            } else if (now.code < 0) {
                log.line("[!] manager: installed version is unreadable; leaving it alone");
                return new Result(false, false, "unknown version");
            } else {
                log.line("[!] manager: installed " + now.name + " (" + now.code
                        + "), need " + VERSION_NAME + " (" + VERSION_CODE + ")");
            }

            File apk;
            try {
                apk = cacheApk(filesDir, log);
            } catch (Throwable t) {
                log.line("[!] manager: no usable " + VERSION_NAME + " apk: "
                        + message(t));
                log.line("[i] manager: continuing with the installed manager");
                return new Result(false, swapped, "apk unavailable");
            }

            if (!stage(apk, shell, log)) {
                log.line("[!] manager: staging failed, continuing");
                return new Result(false, swapped, "staging failed");
            }

            if (now.present) {
                log.line("[*] manager: uninstalling " + PACKAGE);
                String out = shell.run("timeout 60 pm uninstall --user 0 "
                        + PACKAGE + " 2>&1", 70_000L);
                if (out.contains("Success")) {
                    log.line("[+] manager: uninstalled");
                } else {
                    log.line("[!] manager: uninstall said: " + oneLine(out)
                            + " (continuing)");
                }
            }

            log.line("[*] manager: installing " + VERSION_NAME
                    + " (dexopt can take a few seconds)");
            String install = shell.run("timeout 180 pm install "
                    + quote(STAGED_APK) + " 2>&1", 190_000L);
            if (!install.contains("Success")) {
                log.line("[!] manager: install said: " + oneLine(install));
                log.line("[i] manager: /data/adb, modules and the allowlist are untouched");
                return new Result(false, swapped, "install failed");
            }

            Installed after = read(shell);
            if (!isPinned(after)) {
                sleep(1000);
                after = read(shell);
            }
            if (!isPinned(after)) {
                log.line("[!] manager: installed version is not " + VERSION_CODE
                        + ", continuing");
                log.line("[i] manager: /data/adb, modules and the allowlist are untouched");
                return new Result(false, swapped, "verify failed");
            }

            swapped = true;
            log.line("[+] manager: installed " + VERSION_NAME + " ("
                    + VERSION_CODE + ")");
            log.line("[i] manager: /data/adb, modules and the allowlist are untouched");
            launchOnce(shell, log);
            return new Result(true, true, "swapped");
        } catch (Throwable t) {
            log.line("[!] manager: step failed: " + message(t));
            return new Result(false, swapped, "exception");
        }
    }

    /** Null means the package state cannot be read, so never uninstall. */
    private static Installed read(Shell shell) {
        String script = "p=$(pm path " + PACKAGE + " 2>&1); rc=$?; "
                + "if [ -n \"$p\" ] && [ $rc -ne 0 ]; then echo __pm_error; "
                + "elif [ -z \"$p\" ]; then echo __absent; "
                + "else echo __present; "
                + "dumpsys package " + PACKAGE
                + " 2>/dev/null | grep -m2 -E 'versionCode=|versionName='; fi";
        String out = shell.run(script, 40_000L);
        if (isShellError(out) || out.contains("__pm_error")) {
            return null;
        }
        if (out.contains("__absent")) {
            return Installed.absent();
        }
        if (!out.contains("__present")) {
            return null;
        }

        Matcher code = DUMP_CODE.matcher(out);
        Matcher name = DUMP_NAME.matcher(out);
        String label = name.find() ? name.group(1) : "?";
        if (code.find()) {
            try {
                return Installed.present(Long.parseLong(code.group(1)), label);
            } catch (NumberFormatException ignored) {
            }
        }

        String list = shell.run("timeout 20 pm list packages --show-versioncode "
                + PACKAGE + " 2>/dev/null", 25_000L);
        if (!isShellError(list)) {
            Matcher listing = LIST_CODE.matcher(list);
            if (listing.find()) {
                try {
                    return Installed.present(Long.parseLong(listing.group(1)), label);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Installed.present(-1, label);
    }

    private static boolean isPinned(Installed value) {
        return value != null && value.present && value.code == VERSION_CODE;
    }

    private static File cacheApk(File filesDir, Log log) throws Exception {
        File dir = new File(filesDir, "manager");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File apk = new File(dir, "kernelsu-3.2.5.apk");
        if (isVerified(apk)) {
            log.line("[i] manager: using cached " + VERSION_NAME + " apk");
            return apk;
        }
        if (apk.exists() && !apk.delete()) {
            throw new IOException("cannot remove stale " + apk);
        }
        File part = new File(dir, "kernelsu-3.2.5.apk.part");
        if (part.exists() && !part.delete()) {
            throw new IOException("cannot remove stale " + part);
        }

        log.line("[*] manager: downloading " + VERSION_NAME + " apk (9.1 MiB)");
        PayloadChannel.downloadTo(APK_URL, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
                MAX_APK_BYTES, part);
        if (part.length() != APK_BYTES) {
            throw new IOException("size " + part.length() + " != " + APK_BYTES);
        }
        String digest = PayloadStore.digest(part);
        if (!APK_SHA256.equalsIgnoreCase(digest)) {
            throw new IOException("sha256 " + digest + " != " + APK_SHA256);
        }
        if (!part.renameTo(apk)) {
            throw new IOException("cannot rename " + part);
        }
        log.line("[+] manager: apk verified sha256 " + digest);
        return apk;
    }

    private static boolean isVerified(File file) {
        try {
            return file.isFile() && file.length() == APK_BYTES
                    && APK_SHA256.equalsIgnoreCase(PayloadStore.digest(file));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean stage(File apk, Shell shell, Log log) {
        try {
            if (APK_SHA256.equalsIgnoreCase(remoteDigest(shell))) {
                log.line("[i] manager: apk already staged");
                return true;
            }
            log.line("[*] manager: staging " + STAGED_APK);
            try (InputStream in = new FileInputStream(apk)) {
                ShizukuBridge.writeFile(STAGED_APK, "644", in);
            }
            String after = remoteDigest(shell);
            if (!APK_SHA256.equalsIgnoreCase(after)) {
                log.line("[!] manager: staged apk hash "
                        + (after.isEmpty() ? "unreadable" : after) + " does not match");
                return false;
            }
            return true;
        } catch (Throwable t) {
            log.line("[!] manager: staging failed: " + message(t));
            return false;
        }
    }

    private static String remoteDigest(Shell shell) {
        String out = shell.run("timeout 30 sha256sum " + quote(STAGED_APK)
                + " 2>/dev/null", 35_000L);
        for (String token : out.split("\\s+")) {
            if (token.matches("[0-9a-fA-F]{64}")) {
                return token;
            }
        }
        return "";
    }

    private static void launchOnce(Shell shell, Log log) {
        log.line("[*] manager: launching once so the kernel learns its uid");
        String component = resolveComponent(shell);
        boolean started = false;
        if (component != null) {
            String out = shell.run("timeout 25 am start -W -n "
                    + quote(component) + " 2>&1", 30_000L);
            started = out.contains("Status: ok") || out.contains("Complete");
        }
        if (!started) {
            String out = shell.run("timeout 25 monkey -p " + PACKAGE
                    + " -c android.intent.category.LAUNCHER 1 2>&1", 30_000L);
            started = out.contains("Events injected: 1");
        }
        if (!started) {
            log.line("[!] manager: could not open the manager; continuing");
            return;
        }

        sleep(3000);
        log.line("[*] manager: returning to Time My Galaxy");
        if (startSelf(shell)) {
            log.line("[+] manager: returned to Time My Galaxy");
            return;
        }
        shell.run("timeout 10 input keyevent KEYCODE_BACK", 15_000L);
        sleep(500);
        if (startSelf(shell)) {
            log.line("[+] manager: returned to Time My Galaxy");
            return;
        }
        shell.run("timeout 10 input keyevent KEYCODE_HOME", 15_000L);
        log.line("[!] manager: could not foreground the app; continuing");
    }

    private static String resolveComponent(Shell shell) {
        String out = shell.run("timeout 20 cmd package resolve-activity --brief "
                + "-a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER " + PACKAGE + " 2>/dev/null",
                25_000L);
        Matcher matcher = COMPONENT.matcher(out);
        String found = null;
        while (matcher.find()) {
            found = matcher.group(1);
        }
        return found;
    }

    private static boolean startSelf(Shell shell) {
        String out = shell.run("timeout 20 am start -W --activity-single-top "
                + "--activity-reorder-to-front "
                + "-n dev.lukag.timemygalaxy/.MainActivity 2>&1", 25_000L);
        return out.contains("Status: ok") || out.contains("Complete");
    }

    private static String quote(String value) {
        return ShizukuBridge.shellQuote(value);
    }

    private static boolean isShellError(String out) {
        return out == null || out.trim().startsWith("error: ");
    }

    private static String oneLine(String value) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "no output";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static String message(Throwable t) {
        String text = t.getMessage();
        return text == null || text.isEmpty() ? t.getClass().getSimpleName() : text;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Installed {
        final boolean present;
        final long code;
        final String name;

        private Installed(boolean present, long code, String name) {
            this.present = present;
            this.code = code;
            this.name = name;
        }

        static Installed absent() {
            return new Installed(false, -1L, "?");
        }

        static Installed present(long code, String name) {
            return new Installed(true, code, name);
        }
    }
}
