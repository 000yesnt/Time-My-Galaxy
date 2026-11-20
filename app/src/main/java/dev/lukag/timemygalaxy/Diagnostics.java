package dev.lukag.timemygalaxy;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes one plain-text diagnostics file. Log files are pulled whole, with no
 * tail and no total cap. Shell reads first, with su only when root is live.
 */
final class Diagnostics {

    interface Log {
        void line(String text);
    }

    private static final String[] FILES = {
            "run.log", "wr_load.log", "wr_exec.log", "wr_load.sh",
    };

    private Diagnostics() {
    }

    static File export(Context context, String header, String session, boolean root, Log log)
            throws Exception {
        StringBuilder out = new StringBuilder();
        out.append(header).append("\n\n");

        out.append("== /data/local/tmp/wr ==\n");
        out.append(read("ls -l /data/local/tmp/wr 2>&1", root)).append('\n');
        out.append(read("wc -c /data/local/tmp/wr/* 2>&1", root)).append('\n');

        for (String name : FILES) {
            out.append("\n== ").append(PayloadStore.REMOTE_DIR).append('/')
                    .append(name).append(" ==\n");
            out.append(read("cat " + PayloadStore.REMOTE_DIR + "/" + name + " 2>&1", root));
            out.append('\n');
        }

        out.append("\n== /data/local/tmp/ksu_stage.sh ==\n");
        out.append(read("cat /data/local/tmp/ksu_stage.sh 2>&1", root)).append('\n');

        out.append("\n== app session log ==\n");
        out.append(session == null ? "" : session).append('\n');

        File dir = new File(context.getFilesDir(), "exports");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new java.io.IOException("cannot create " + dir);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String build = DeviceGate.buildId();
        File file = new File(dir, "tmg-" + (build == null ? "unknown" : build)
                + "-" + stamp + ".txt");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(out.toString().getBytes("UTF-8"));
            stream.getFD().sync();
        }
        if (log != null) {
            log.line("[+] diagnostics saved " + file.getAbsolutePath()
                    + " (" + file.length() + " bytes)");
        }
        return file;
    }

    private static String read(String command, boolean root) {
        String out = Shell.capture(command, 60_000L, "/data/local/tmp", null).out;
        if (root && Shell.isError(out)) {
            out = Shell.root(command, 90_000L, null);
        }
        return out;
    }
}
