package dev.lukag.timemygalaxy;

import android.os.Build;

/** Display metadata for the running device and the resolved payload. */
final class BuildInfo {

    final String build;
    final String model;
    final String soc;
    final String android;
    final String kernel;
    final boolean verified;
    final int rev;

    private BuildInfo(String build, String model, String soc, String android, String kernel,
            boolean verified, int rev) {
        this.build = build;
        this.model = model;
        this.soc = soc;
        this.android = android;
        this.kernel = kernel;
        this.verified = verified;
        this.rev = rev;
    }

    static BuildInfo from(Targets.Target target) {
        return new BuildInfo(
                DeviceGate.buildId(),
                safe(Build.MODEL),
                soc(),
                safe(Build.VERSION.RELEASE),
                System.getProperty("os.version", "unknown"),
                target != null && target.verified,
                target == null ? 0 : target.rev);
    }

    String describe() {
        StringBuilder out = new StringBuilder();
        out.append("Build ").append(build == null ? "unknown" : build).append('\n');
        out.append("Model ").append(model).append(" · Android ").append(android).append('\n');
        out.append("SoC ").append(soc).append('\n');
        out.append("Kernel ").append(kernel).append('\n');
        if (rev > 0) {
            out.append("Payload rev ").append(rev).append(verified ? " · verified" : " · untested");
        } else {
            out.append("No payload resolved yet");
        }
        return out.toString();
    }

    private static String soc() {
        String model = Build.SOC_MODEL;
        if (model != null && !model.isEmpty() && !"unknown".equalsIgnoreCase(model)) {
            return model;
        }
        return Build.HARDWARE;
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }
}
