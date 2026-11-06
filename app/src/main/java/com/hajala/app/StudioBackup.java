package com.hajala.app;

import java.io.FileOutputStream;
import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/**
 * Offline backup of studio metadata (index.json + note bodies) and, optionally, the original media.
 * Bytes are streamed in 64 KB chunks; files are never loaded whole into memory. Nothing is uploaded.
 */
final class StudioBackup {
    static final int FORMAT = 1;

    interface Progress {
        void onProgress(int done, int total, String name);
    }

    static final class Summary {
        int items;
        int notes;
        int mediaFiles;
        boolean hasMedia;
        int conflicts;
        JSONObject index;
    }

    private final Context app;
    final StudioStore store;

    StudioBackup(Context c, StudioStore store) {
        this.app = c.getApplicationContext();
        this.store = store;
    }

    /** Writes a zip to [out]. Closes nothing it did not open; the caller owns [out]. */
    void exportTo(OutputStream out, boolean includeMedia, Progress progress) throws Exception {
        byte[] index = store.indexSnapshot().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = sha1(index);
        JSONObject man = new JSONObject();
        man.put("format", FORMAT);
        man.put("indexSha1", sha);
        man.put("hasMedia", includeMedia);
        man.put("createdAt", System.currentTimeMillis());
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
        putBytes(zip, "manifest.json", man.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        putBytes(zip, "index.json", index);
        int notes = 0;
        for (StudioStore.Note n : store.allNotes()) {
            File f = store.noteFile(n.id);
            if (!f.exists()) continue;
            putFile(zip, "notes/" + n.id + ".txt", f);
            notes++;
        }
        for (StudioStore.Notebook nb : store.allNotebooks()) {
            File bf = store.bookFile(nb.id);
            if (bf.exists()) putFile(zip, "books/" + nb.id + ".json", bf);
        }
        int media = 0;
        if (includeMedia) {
            java.util.List<StudioStore.Item> items = store.allItems();
            int total = items.size();
            for (int i = 0; i < items.size(); i++) {
                StudioStore.Item it = items.get(i);
                File f = store.fileOf(it);
                if (progress != null) progress.onProgress(i, total, it.name);
                if (!f.exists() || it.file == null || it.file.isEmpty() || it.file.contains("..")) continue;
                putFile(zip, "media/" + it.file, f);
                media++;
            }
            if (progress != null) progress.onProgress(total, total, "");
        }
        man.put("notes", notes);
        man.put("media", media);
        zip.finish();
    }

    /** Reads manifest + index and counts conflicts. Does not change local data. */
    Summary inspect(Uri uri) throws Exception {
        byte[] manifest = null;
        byte[] index = null;
        boolean hasMedia = false;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("تعذّر فتح ملف النسخة");
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = safeName(e.getName());
                if (name == null) {
                    zip.closeEntry();
                    continue;
                }
                if ("manifest.json".equals(name)) manifest = readLimited(zip, 256 * 1024);
                else if ("index.json".equals(name)) index = readLimited(zip, 32 * 1024 * 1024);
                else if (name.startsWith("media/")) hasMedia = true;
                zip.closeEntry();
                if (manifest != null && index != null && hasMedia) break;
            }
        }
        if (manifest == null || index == null) throw new IllegalStateException("الملف ليس نسخة أركينوم صالحة");
        JSONObject man = new JSONObject(new String(manifest, java.nio.charset.StandardCharsets.UTF_8));
        if (man.optInt("format", 0) != FORMAT) throw new IllegalStateException("إصدار النسخة غير مدعوم");
        String want = man.optString("indexSha1", "");
        String got = sha1(index);
        if (want.isEmpty() || !want.equalsIgnoreCase(got)) {
            throw new IllegalStateException("النسخة تالفة: بصمة الفهرس لا تطابق المحتوى");
        }
        JSONObject idx = new JSONObject(new String(index, java.nio.charset.StandardCharsets.UTF_8));
        if (!idx.has("items") || !idx.has("notes")) throw new IllegalStateException("الفهرس داخل النسخة غير مكتمل");
        Summary s = new Summary();
        s.index = idx;
        s.hasMedia = hasMedia || man.optBoolean("hasMedia", false);
        s.items = idx.optJSONArray("items") == null ? 0 : idx.optJSONArray("items").length();
        s.notes = idx.optJSONArray("notes") == null ? 0 : idx.optJSONArray("notes").length();
        s.conflicts = store.countConflicts(idx);
        return s;
    }

    /**
     * Copies note bodies and (when present) media, then merges metadata.
     * Existing records are left untouched unless [replace] is true.
     */
    StudioStore.MergeResult restore(Uri uri, JSONObject index, boolean replace, Progress progress) throws Exception {
        int copied = 0;
        int totalHint = 1;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("تعذّر فتح ملف النسخة");
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zip.getNextEntry()) != null) {
                String name = safeName(e.getName());
                if (name == null) {
                    zip.closeEntry();
                    continue;
                }
                if (name.startsWith("notes/") && name.endsWith(".txt")) {
                    String id = name.substring("notes/".length(), name.length() - 4);
                    if (id.isEmpty() || id.contains("/") || id.contains("..")) {
                        zip.closeEntry();
                        continue;
                    }
                    boolean exists = store.noteFile(id).exists() && store.note(id) != null;
                    if (!exists || replace) writeStream(store.noteFile(id), zip, buf);
                } else if (name.startsWith("books/") && name.endsWith(".json")) {
                    String id = name.substring("books/".length(), name.length() - 5);
                    if (id.isEmpty() || id.contains("/") || id.contains("..")) {
                        zip.closeEntry();
                        continue;
                    }
                    File bf = store.bookFile(id);
                    if (!bf.exists() || replace) writeStream(bf, zip, buf);
                } else if (name.startsWith("media/")) {
                    String file = name.substring("media/".length());
                    if (file.isEmpty() || file.contains("/") || file.contains("..")) {
                        zip.closeEntry();
                        continue;
                    }
                    File dest = new File(store.mediaDir(), file);
                    boolean allow = replace || !dest.exists();
                    if (allow) {
                        writeStream(dest, zip, buf);
                        copied++;
                        if (progress != null && copied % 3 == 0) progress.onProgress(copied, totalHint, file);
                    }
                }
                zip.closeEntry();
            }
        }
        StudioStore.MergeResult r = store.mergeBackup(index, replace);
        r.mediaCopied = copied;
        if (progress != null) progress.onProgress(1, 1, "");
        return r;
    }

    private static void writeStream(File dest, InputStream in, byte[] buf) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(parent, dest.getName() + ".tmp");
        try (FileOutputStream os = new java.io.FileOutputStream(tmp)) {
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.getFD().sync();
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            if (!tmp.renameTo(dest)) throw new java.io.IOException("تعذّر كتابة " + dest.getName());
        }
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, File f) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        byte[] buf = new byte[64 * 1024];
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        }
        zip.closeEntry();
    }

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IllegalStateException("ملف داخل النسخة أكبر من المتوقع");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    /** Rejects path traversal. Returns null when the entry must be ignored. */
    private static String safeName(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.contains("..")) return null;
        return n;
    }

    static String sha1(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
