package dev.lukag.timemygalaxy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Checks the latest GitHub release, cached for a day and silent on failure. */
final class UpdateCheck {

    interface Log {
        void line(String text);
    }

    static final class Latest {
        final String tag;
        final String url;
        final long checkedAt;
        final String error;

        Latest(String tag, String url, long checkedAt, String error) {
            this.tag = tag;
            this.url = url;
            this.checkedAt = checkedAt;
            this.error = error;
        }
    }

    private static final String API =
            "https://api.github.com/repos/MocLG/Time-My-Galaxy/releases/latest";
    private static final String PREFS = "update";
    private static final long CACHE_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_BYTES = 256 * 1024;

    private UpdateCheck() {
    }

    static Latest cached(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tag = prefs.getString("tag", null);
        String url = prefs.getString("url", null);
        long at = prefs.getLong("at", 0);
        if (tag == null) {
            return null;
        }
        return new Latest(tag, url, at, null);
    }

    static Latest check(Context context, boolean force, Log log) {
        Latest cached = cached(context);
        if (!force && cached != null && System.currentTimeMillis() - cached.checkedAt < CACHE_MS) {
            return cached;
        }
        try {
            JSONObject json = new JSONObject(fetch());
            String tag = json.optString("tag_name", "");
            String url = json.optString("html_url", BuildConfig.REPO_URL);
            if (tag.isEmpty()) {
                throw new java.io.IOException("release has no tag");
            }
            long now = System.currentTimeMillis();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("tag", tag)
                    .putString("url", url)
                    .putLong("at", now)
                    .apply();
            if (log != null) {
                log.line("[+] latest release " + tag);
            }
            return new Latest(tag, url, now, null);
        } catch (Throwable t) {
            if (log != null && force) {
                log.line("[i] update check unavailable: " + message(t));
            }
            return cached;
        }
    }

    static boolean isNewer(String tag) {
        String[] latest = numbers(tag);
        String[] current = numbers(BuildConfig.VERSION_NAME);
        for (int i = 0; i < Math.max(latest.length, current.length); i++) {
            int a = i < latest.length ? Integer.parseInt(latest[i]) : 0;
            int b = i < current.length ? Integer.parseInt(current[i]) : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static String[] numbers(String version) {
        String text = version == null ? "" : version.trim();
        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1);
        }
        List<String> parts = new ArrayList<>();
        for (String part : text.split("[^0-9]+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.toArray(new String[0]);
    }

    private static String fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "Time-My-Galaxy");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new java.io.IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (out.size() + read > MAX_BYTES) {
                        throw new java.io.IOException("response is too large");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String message(Throwable t) {
        String text = t.getMessage();
        return text == null || text.isEmpty() ? t.getClass().getSimpleName() : text;
    }
}
