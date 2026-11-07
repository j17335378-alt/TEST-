package com.hajala.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * In-app updater. Flow: read update.json from the latest GitHub Release -> compare versionCode ->
 * download the APK -> verify SHA-256, package name, versionCode and signing certificate -> hand it to the
 * system installer. The system installer replaces the app in place, so user data is never touched.
 * Nothing here deletes, resets or replaces user data.
 */
final class AppUpdater {
    /** GitHub "owner/repo" that publishes releases. The repository must be public for the app to read it. */
    static final String REPO = "j17335378-alt/TEST-";
    static final String MANIFEST_URL = "https://github.com/" + REPO + "/releases/latest/download/update.json";
    static final String APK_URL_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    static final String FILE_AUTHORITY = "com.hajala.app.updates";

    private static final String PREFS = "arcanum_update";
    private static final String K_LAST_CHECK = "last_check";
    private static final String K_LAST_MANIFEST = "last_manifest";
    private static final String K_DISMISSED = "dismissed_code";
    private static final String K_SEEN_CODE = "seen_code";
    private static final long AUTO_CHECK_EVERY_MS = 12L * 60 * 60 * 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Info {
        int versionCode;
        String versionName = "";
        String apkUrl = "";
        String sha256 = "";
        long size;
        String notes = "";
    }

    interface Callback<T> {
        /** Always called on the main thread. Exactly one of [value] / [error] is non-null. */
        void done(T value, String error);
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    private AppUpdater() {}

    // ------------------------------------------------------------------ current version

    static int currentCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String currentName(Context c) {
        try {
            String n = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return n == null ? "?" : n;
        } catch (Exception e) {
            return "?";
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ check

    static Info parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Info i = new Info();
        i.versionCode = o.getInt("versionCode");
        i.versionName = o.getString("versionName");
        i.apkUrl = o.getString("apkUrl");
        i.sha256 = o.getString("sha256").toLowerCase(Locale.US);
        i.size = o.optLong("size", 0);
        i.notes = o.optString("notes", "");
        if (i.versionCode <= 0) throw new Exception("bad versionCode");
        if (!i.sha256.matches("[0-9a-f]{64}")) throw new Exception("bad sha256");
        if (!i.apkUrl.startsWith(APK_URL_PREFIX)) throw new Exception("apkUrl is not from the release repository");
        return i;
    }

    /** Fetches update.json. Reports the latest release whether or not it is newer; use [isNewer]. */
    static void check(final Context c, final Callback<Info> cb) {
        final Context app = c.getApplicationContext();
        Ui.IO.execute(() -> {
            Info info = null;
            String err = null;
            try {
                String body = new String(readAll(openHttps(MANIFEST_URL)), StandardCharsets.UTF_8);
                info = parse(body);
                prefs(app).edit().putLong(K_LAST_CHECK, System.currentTimeMillis())
                        .putString(K_LAST_MANIFEST, body).apply();
            } catch (java.io.FileNotFoundException e) {
                err = "لا يوجد إصدار منشور بعد";
            } catch (java.io.IOException e) {
                err = "تعذّر الاتصال بالخادم. تحقق من الإنترنت وحاول مرة أخرى";
            } catch (Exception e) {
                err = "بيانات التحديث غير صالحة";
            }
            final Info fi = info;
            final String fe = err;
            MAIN.post(() -> cb.done(fi, fe));
        });
    }

    static boolean isNewer(Context c, Info i) {
        return i != null && VersionUtil.isNewer(i.versionCode, currentCode(c));
    }

    /** True when the last check is old enough that a quiet startup check is due. */
    static boolean autoCheckDue(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong(K_LAST_CHECK, 0) > AUTO_CHECK_EVERY_MS;
    }

    /** The newest release seen by the last successful check (no network), or null. */
    static Info lastKnown(Context c) {
        try {
            String s = prefs(c).getString(K_LAST_MANIFEST, null);
            return s == null ? null : parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean updateKnown(Context c) {
        return isNewer(c, lastKnown(c));
    }

    static boolean wasDismissed(Context c, Info i) {
        return prefs(c).getInt(K_DISMISSED, 0) == i.versionCode;
    }

    static void dismiss(Context c, Info i) {
        prefs(c).edit().putInt(K_DISMISSED, i.versionCode).apply();
    }

    // ------------------------------------------------------------------ download

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Downloads (resumable only by restart) and verifies the APK. Cancel by setting [cancel]. */
    static void download(final Context c, final Info info, final AtomicBoolean cancel, final Progress progress,
                         final Callback<File> cb) {
        final Context app = c.getApplicationContext();
        Ui.IO.execute(() -> {
            File result = null;
            String err = null;
            File part = null;
            try {
                File d = dir(app);
                File target = new File(d, "ARCANUM-" + info.versionCode + ".apk");
                File[] old = d.listFiles();
                if (old != null) for (File f : old) if (!f.equals(target)) f.delete();
                if (target.exists() && info.sha256.equals(sha256(target))) {
                    result = target;
                } else {
                    part = new File(d, target.getName() + ".part");
                    HttpURLConnection conn = openHttps(info.apkUrl);
                    long total = conn.getContentLengthLong();
                    if (total <= 0) total = info.size;
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    long done = 0;
                    try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        long lastTick = 0;
                        while ((n = in.read(buf)) > 0) {
                            if (cancel != null && cancel.get()) throw new InterruptedException("cancelled");
                            out.write(buf, 0, n);
                            md.update(buf, 0, n);
                            done += n;
                            long now = System.currentTimeMillis();
                            if (progress != null && now - lastTick > 150) {
                                lastTick = now;
                                final long dd = done, tt = total;
                                MAIN.post(() -> progress.onProgress(dd, tt));
                            }
                        }
                        out.flush();
                    } finally {
                        conn.disconnect();
                    }
                    if (info.size > 0 && done != info.size) throw new java.io.IOException("size mismatch");
                    if (!hex(md.digest()).equals(info.sha256)) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        throw new SecurityException("checksum");
                    }
                    if (!part.renameTo(target)) throw new java.io.IOException("rename failed");
                    result = target;
                }
                String bad = verifyApk(app, result);
                if (bad != null) {
                    //noinspection ResultOfMethodCallIgnored
                    result.delete();
                    result = null;
                    err = bad;
                }
            } catch (InterruptedException e) {
                err = "أُلغي التحميل";
                result = null;
            } catch (SecurityException e) {
                err = "فشل التحقق من سلامة الملف (البصمة لا تطابق). لن يتم التثبيت";
                result = null;
            } catch (Exception e) {
                err = "تعذّر تحميل التحديث. تأكد من الاتصال وحاول مرة أخرى";
                result = null;
            } finally {
                if (part != null && part.exists() && result == null) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
            }
            final File fr = result;
            final String fe = err;
            MAIN.post(() -> cb.done(fr, fe));
        });
    }

    // ------------------------------------------------------------------ verify

    /** Returns null when the APK is a genuine, newer build of this app; otherwise a reason in Arabic. */
    static String verifyApk(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo n = pm.getPackageArchiveInfo(apk.getPath(), flags);
            if (n == null) return "ملف التحديث تالف أو غير صالح";
            if (!c.getPackageName().equals(n.packageName)) return "ملف التحديث لتطبيق مختلف";
            long newCode = Build.VERSION.SDK_INT >= 28 ? n.getLongVersionCode() : n.versionCode;
            if (newCode <= currentCode(c)) return "هذا الإصدار ليس أحدث من المثبّت حالياً";
            PackageInfo cur = pm.getPackageInfo(c.getPackageName(), flags);
            Set<String> a = signers(n);
            Set<String> b = signers(cur);
            if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
                return "توقيع التحديث يختلف عن توقيع النسخة المثبّتة، ولن يقبله أندرويد دون حذف التطبيق. "
                        + "لن أحذف بياناتك: صدّر نسخة احتياطية أولاً (انظر دليل التحديثات).";
            }
            return null;
        } catch (Exception e) {
            return "تعذّر فحص ملف التحديث";
        }
    }

    private static Set<String> signers(PackageInfo p) throws Exception {
        Set<String> out = new HashSet<>();
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo si = p.signingInfo;
            if (si != null) sigs = si.getApkContentsSigners();
        } else {
            sigs = p.signatures;
        }
        if (sigs != null) {
            for (Signature s : sigs) out.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        }
        return out;
    }

    // ------------------------------------------------------------------ install

    /** Opens the system installer. Returns a message to show when it could not start, or null when it did. */
    static String install(Activity a, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()));
                a.startActivity(s);
                return "فعّل «السماح من هذا المصدر» لـ ARCANUM ثم ارجع واضغط «تثبيت» مرة أخرى";
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(a, FILE_AUTHORITY, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
            return null;
        } catch (Exception e) {
            return "تعذّر فتح مثبّت النظام";
        }
    }

    // ------------------------------------------------------------------ after an update

    /**
     * Call once at startup. Returns the previous versionCode if the app was just updated (so the UI can say so),
     * otherwise 0. Also removes the downloaded APK, which is no longer needed.
     */
    static int onStartup(Context c) {
        SharedPreferences p = prefs(c);
        int seen = p.getInt(K_SEEN_CODE, 0);
        int now = currentCode(c);
        if (seen == now) return 0;
        p.edit().putInt(K_SEEN_CODE, now).apply();
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) f.delete();
        return seen > 0 && seen < now ? seen : 0;
    }

    // ------------------------------------------------------------------ plumbing

    /** GET with manual redirects; https only. */
    private static HttpURLConnection openHttps(String url) throws java.io.IOException {
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            if (!u.startsWith("https://")) throw new java.io.IOException("https required");
            HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "ARCANUM-Updater");
            conn.setRequestProperty("Accept", "application/octet-stream, application/json");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new java.io.IOException("redirect without location");
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            if (code == 404) {
                conn.disconnect();
                throw new java.io.FileNotFoundException(u);
            }
            if (code != 200) {
                conn.disconnect();
                throw new java.io.IOException("HTTP " + code);
            }
            return conn;
        }
        throw new java.io.IOException("too many redirects");
    }

    private static byte[] readAll(HttpURLConnection conn) throws java.io.IOException {
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 256 * 1024) throw new java.io.IOException("manifest too large");
            }
            return bo.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }
}
