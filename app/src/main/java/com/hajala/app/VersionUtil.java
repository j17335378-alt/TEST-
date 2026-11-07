package com.hajala.app;

import java.util.Locale;

/** Pure version helpers (no Android dependencies, unit-testable on a plain JVM). */
final class VersionUtil {
    private VersionUtil() {}

    /** "v1.7.0-beta+3" -> [1,7,0]. Non-numeric parts count as 0. */
    static int[] parse(String v) {
        if (v == null) return new int[] {0};
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '-' || ch == '+' || ch == ' ') { cut = i; break; }
        }
        s = s.substring(0, cut);
        if (s.isEmpty()) return new int[] {0};
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Math.max(0, Integer.parseInt(parts[i]));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    /** versionCode is what Android enforces, so it alone decides whether an update is "newer". */
    static boolean isNewer(int remoteCode, int localCode) {
        return remoteCode > localCode;
    }

    static String formatBytes(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }
}
