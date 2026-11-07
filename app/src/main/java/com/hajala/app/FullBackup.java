package com.hajala.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One zip that holds everything the user owns: every SharedPreferences file (MangaVerse works and chapters,
 * reader settings, ...), the Studio index and notes, and optionally all media (Studio files + MangaVerse images).
 *
 * Restore is merge-safe: it never deletes files that are not in the backup, it takes an automatic safety copy of
 * the current data first, and it validates everything (format, package, zip paths, index JSON) before changing a byte.
 */
final class FullBackup {
    static final int FORMAT = 2;
    private static final String[] SKIP_PREFS = {"arcanum_meta", "arcanum_update", "WebView", "com.google", "androidx"};

    interface Progress {
        void onProgress(int done, int total);
    }

    private FullBackup() {}

    // ------------------------------------------------------------------ locations

    static File autoDir(Context c) {
        File d = new File(c.getFilesDir(), "backups/auto");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Newest first. */
    static List<File> autoBackups(Context c) {
        List<File> out = new ArrayList<>();
        File[] fs = autoDir(c).listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && f.getName().endsWith(".zip")) out.add(f);
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    static String suggestedName() {
        return "ARCANUM-backup-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".zip";
    }

    private static boolean wanted(String prefsName) {
        for (String s : SKIP_PREFS) if (prefsName.startsWith(s)) return false;
        return true;
    }

    private static List<String> prefsNames(Context c) {
        List<String> names = new ArrayList<>();
        File[] fs = new File(c.getApplicationInfo().dataDir, "shared_prefs").listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName();
                if (f.isFile() && n.endsWith(".xml")) {
                    n = n.substring(0, n.length() - 4);
                    if (wanted(n)) names.add(n);
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    // ------------------------------------------------------------------ export

    /** Writes the zip to [out] (the caller owns and closes [out]). Call off the UI thread. */
    static void export(Context c, OutputStream out, boolean includeMedia, Progress progress) throws Exception {
        Context app = c.getApplicationContext();
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED);

        JSONObject man = new JSONObject();
        man.put("format", FORMAT);
        man.put("app", app.getPackageName());
        man.put("versionName", AppUpdater.currentName(app));
        man.put("versionCode", AppUpdater.currentCode(app));
        man.put("schema", AppMigrations.storedSchema(app));
        man.put("createdAt", System.currentTimeMillis());
        man.put("hasMedia", includeMedia);
        putBytes(zip, "manifest.json", man.toString().getBytes(StandardCharsets.UTF_8));

        for (String name : prefsNames(app)) {
            putBytes(zip, "prefs/" + name + ".json", prefsToJson(app, name).toString().getBytes(StandardCharsets.UTF_8));
        }

        File studio = new File(app.getFilesDir(), "studio");
        File idx = new File(studio, "index.json");
        if (idx.exists()) putFile(zip, "studio/index.json", idx);
        File[] notes = new File(studio, "notes").listFiles();
        if (notes != null) for (File f : notes) if (f.isFile()) putFile(zip, "studio/notes/" + f.getName(), f);
        // notebook pages: files/studio/books/<id>.json (+ .bak)
        File[] books = new File(studio, "books").listFiles();
        if (books != null) for (File f : books) if (f.isFile()) putFile(zip, "studio/books/" + f.getName(), f);

        if (includeMedia) {
            List<File> files = new ArrayList<>();
            collect(new File(studio, "media"), files);
            File[] mv = new File(app.getFilesDir(), "media").listFiles();
            int studioCount = files.size();
            if (mv != null) for (File f : mv) if (f.isFile()) files.add(f);
            int total = files.size();
            for (int i = 0; i < total; i++) {
                File f = files.get(i);
                String entry = i < studioCount
                        ? "studio/media/" + relative(new File(studio, "media"), f)
                        : "media/" + f.getName();
                putFile(zip, entry, f);
                if (progress != null) progress.onProgress(i + 1, total);
            }
        }
        zip.finish();
        zip.flush();
    }

    /** Small automatic backup (no media) kept inside the app. Returns the file, or throws. Keeps the newest 5. */
    static File autoSnapshot(Context c, String label) throws Exception {
        String safe = label.replaceAll("[^A-Za-z0-9._-]", "_");
        File f = new File(autoDir(c), new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "-" + safe + ".zip");
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            export(c, os, false, null);
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
        List<File> all = autoBackups(c);
        for (int i = 5; i < all.size(); i++) all.get(i).delete();
        return f;
    }

    private static JSONObject prefsToJson(Context c, String name) throws Exception {
        SharedPreferences sp = c.getSharedPreferences(name, Context.MODE_PRIVATE);
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject j = new JSONObject();
            if (v instanceof String) { j.put("t", "s"); j.put("v", v); }
            else if (v instanceof Integer) { j.put("t", "i"); j.put("v", v); }
            else if (v instanceof Long) { j.put("t", "l"); j.put("v", v); }
            else if (v instanceof Float) { j.put("t", "f"); j.put("v", ((Float) v).doubleValue()); }
            else if (v instanceof Boolean) { j.put("t", "b"); j.put("v", v); }
            else if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object s : (Set<?>) v) a.put(String.valueOf(s));
                j.put("t", "S");
                j.put("v", a);
            } else continue;
            o.put(e.getKey(), j);
        }
        return o;
    }

    // ------------------------------------------------------------------ restore

    /** Reads manifest only; throws if this is not an ARCANUM backup this version understands. */
    static JSONObject inspect(Context c, File zipFile) throws Exception {
        try (ZipFile z = new ZipFile(zipFile)) {
            ZipEntry m = z.getEntry("manifest.json");
            if (m == null) throw new Exception("هذا الملف ليس نسخة احتياطية من ARCANUM");
            JSONObject man = new JSONObject(new String(readAll(z.getInputStream(m)), StandardCharsets.UTF_8));
            if (!c.getPackageName().equals(man.optString("app"))) throw new Exception("النسخة تخص تطبيقاً آخر");
            if (man.optInt("format", 0) > FORMAT) throw new Exception("النسخة أحدث من هذا الإصدار. حدّث التطبيق أولاً");
            if (man.optInt("schema", 1) > AppMigrations.CURRENT_SCHEMA) {
                throw new Exception("بيانات النسخة أحدث من هذا الإصدار. حدّث التطبيق أولاً");
            }
            return man;
        }
    }

    /** Copies a picked Uri into the cache so the zip can be validated with random access. Call off the UI thread. */
    static File stage(Context c, Uri uri) throws Exception {
        File f = new File(c.getCacheDir(), "restore-input.zip");
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new Exception("تعذّر فتح الملف");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return f;
    }

    /** Merge-restore from [zipFile]. The caller should recreate the activity afterwards. Call off the UI thread. */
    static void restore(Context c, File source) throws Exception {
        Context app = c.getApplicationContext();
        File zipFile = source;
        if (autoDir(app).equals(source.getParentFile())) {
            // work on a copy: the safety snapshot below may prune old auto backups, including this one
            zipFile = new File(app.getCacheDir(), "restore-input.zip");
            AppMigrations.copy(source, zipFile);
        }
        inspect(app, zipFile);
        // 1. safety copy of whatever is there now
        autoSnapshot(app, "before-restore");

        File staging = new File(app.getFilesDir(), "restore-staging");
        AppMigrations.deleteTree(staging);
        if (!staging.mkdirs()) throw new java.io.IOException("cannot create staging");
        try (ZipFile z = new ZipFile(zipFile)) {
            // 2. extract + validate into staging (nothing real is modified yet)
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.equals("manifest.json")) continue;
                File dest = new File(staging, name);
                if (!dest.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator)) {
                    throw new Exception("ملف النسخة يحتوي مساراً غير آمن");
                }
                File parent = dest.getParentFile();
                if (parent != null) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                try (InputStream in = z.getInputStream(e); OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            File idx = new File(staging, "studio/index.json");
            if (idx.exists()) new JSONObject(new String(readAll(new FileInputStream(idx)), StandardCharsets.UTF_8));
            File[] prefs = new File(staging, "prefs").listFiles();
            List<JSONObject> prefsJson = new ArrayList<>();
            List<String> prefsNamesList = new ArrayList<>();
            if (prefs != null) {
                for (File f : prefs) {
                    String n = f.getName();
                    if (!n.endsWith(".json")) continue;
                    String base = n.substring(0, n.length() - 5);
                    if (!wanted(base)) continue;
                    prefsJson.add(new JSONObject(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8)));
                    prefsNamesList.add(base);
                }
            }

            // 3. apply (merge: nothing the user has is deleted)
            File studio = new File(app.getFilesDir(), "studio");
            if (idx.exists()) {
                File cur = new File(studio, "index.json");
                if (cur.exists()) AppMigrations.copy(cur, new File(studio, "index.json.before-restore"));
                AppMigrations.copy(idx, cur);
            }
            copyTree(new File(staging, "studio/notes"), new File(studio, "notes"));
            copyTree(new File(staging, "studio/books"), new File(studio, "books"));
            copyTree(new File(staging, "studio/media"), new File(studio, "media"));
            copyTree(new File(staging, "media"), new File(app.getFilesDir(), "media"));
            String mediaPrefix = Uri.fromFile(new File(app.getFilesDir(), "media")).toString() + "/";
            for (int i = 0; i < prefsJson.size(); i++) {
                applyPrefs(app, prefsNamesList.get(i), prefsJson.get(i), mediaPrefix);
            }
        } finally {
            AppMigrations.deleteTree(staging);
            // only the temporary copy made by stage() is removed; in-app auto backups are kept
            if (app.getCacheDir().equals(zipFile.getParentFile())) {
                //noinspection ResultOfMethodCallIgnored
                zipFile.delete();
            }
        }
    }

    private static void applyPrefs(Context c, String name, JSONObject o, String mediaPrefix) throws Exception {
        SharedPreferences.Editor ed = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
        ed.clear();
        java.util.Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject j = o.getJSONObject(k);
            switch (j.getString("t")) {
                case "s": ed.putString(k, remapMedia(j.getString("v"), mediaPrefix)); break;
                case "i": ed.putInt(k, j.getInt("v")); break;
                case "l": ed.putLong(k, j.getLong("v")); break;
                case "f": ed.putFloat(k, (float) j.getDouble("v")); break;
                case "b": ed.putBoolean(k, j.getBoolean("v")); break;
                case "S": {
                    JSONArray a = j.getJSONArray("v");
                    Set<String> s = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                    ed.putStringSet(k, s);
                    break;
                }
                default: break;
            }
        }
        if (!ed.commit()) throw new java.io.IOException("could not write settings: " + name);
    }

    /** Backups store absolute file:// paths of MangaVerse images; point them at this install's media folder. */
    static String remapMedia(String s, String mediaPrefix) {
        return PathRemap.remap(s, mediaPrefix);
    }

    // ------------------------------------------------------------------ helpers

    private static void copyTree(File from, File to) throws Exception {
        File[] fs = from.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            File dest = new File(to, f.getName());
            if (f.isDirectory()) copyTree(f, dest);
            else AppMigrations.copy(f, dest);
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }

    private static String relative(File base, File f) {
        String b = base.getPath();
        String p = f.getPath();
        return p.startsWith(b) ? p.substring(b.length()).replaceFirst("^/", "") : f.getName();
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, File f) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        }
        zip.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
