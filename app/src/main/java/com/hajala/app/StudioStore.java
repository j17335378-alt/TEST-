package com.hajala.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Permanent storage for the Studio (gallery, personal folders, medical study).
 *
 * Everything lives in the app's private storage, never in WebView/browser storage:
 *   files/studio/media/<id>.<ext>   copied photos, videos and files (streamed, never Base64)
 *   files/studio/thumbs/<id>.jpg    small cached thumbnails
 *   files/studio/notes/<id>.txt     note bodies (unlimited length, one file per note)
 *   files/studio/index.json         metadata only; written atomically (tmp file + rename)
 * All public methods are synchronized, so the UI thread and the import thread can both use the store.
 */
final class StudioStore {
    static final String KIND_IMAGE = "image";
    static final String KIND_VIDEO = "video";
    static final String KIND_FILE = "file";

    static final String SCOPE_GALLERY = "gallery";
    static final String SCOPE_MEDICAL = "medical";
    static final String SCOPE_STUDY = "study";

    static final String BOOK_FREE = "free";
    static final String BOOK_QA = "qa";
    static final String BOOK_STUDY = "study";
    static final String BOOK_PLANNER = "planner";

    // ------------------------------------------------------------------ models

    static final class Item {
        String id = "";
        String name = "";
        String kind = KIND_FILE;
        String mime = "";
        String file = "";          // file name inside media/
        long size;
        long createdAt;
        String scope = SCOPE_GALLERY;
        String folderId = "";      // personal folder ("مجلداتي"), gallery scope only
        String notebookId = "";    // medical scope
        String noteId = "";        // medical image attached to a note
        String caption = "";       // medical image description
        long duration;             // video length in ms when known
        boolean favorite;          // shown in the favourites tab
        long trashedAt;            // 0 = normal, otherwise the time it was moved to the trash
        String hash = "";          // SHA-1 of the content, used to skip duplicate imports
        long lastPos;              // last playback position in ms (videos)
        long lastOpened;           // last time it was opened (recent items)
        int lastPage;              // last opened PDF page (0-based), optional
    }

    static final class Folder {
        String id = "";
        String name = "";
        int color;
        String icon = "";
        long createdAt;
        boolean locked;            // hidden from the gallery until the album is unlocked
    }

    static final class Notebook {
        String id = "";
        String name = "";
        long createdAt;
        String scope = SCOPE_MEDICAL;
        String type = BOOK_FREE;   // BOOK_* constant; old notebooks load as free notebooks
        int cover;                 // index of the cover colour (see BookScreen.COVERS)
    }

    static final class Category {
        String id = "";
        String name = "";
    }

    static final class Note {
        String id = "";
        String title = "";
        String preview = "";
        String notebookId = "";
        String categoryId = "";
        long createdAt;
        long updatedAt;
        String scope = SCOPE_MEDICAL;
    }

    /** Result of importing a batch of picked files. */
    static final class ImportResult {
        final List<Item> added = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    /** Result of merging a backup index into the local one. */
    static final class MergeResult {
        int addedItems, updatedItems, skippedItems;
        int addedNotes, updatedNotes, skippedNotes;
        int addedFolders, updatedFolders, skippedFolders;
        int mediaCopied;
    }

    interface Progress {
        void onProgress(int done, int total, String currentName);
    }

    static final int TRASH_DAYS = 30;

    // ------------------------------------------------------------------ state

    private final Context app;
    private final File root;
    private final File mediaDir;
    private final File thumbDir;
    private final File noteDir;
    private final File bookDir;
    private final File indexFile;

    private final List<Item> items = new ArrayList<>();
    private final List<Folder> folders = new ArrayList<>();
    private final List<Notebook> notebooks = new ArrayList<>();
    private final List<Category> categories = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    private boolean defaultsSeeded;
    private String loadError = "";
    /** Albums unlocked during this session. Not written to disk. */
    private final java.util.Set<String> sessionOpen = new java.util.HashSet<>();

    StudioStore(Context c) {
        app = c.getApplicationContext();
        root = new File(app.getFilesDir(), "studio");
        mediaDir = new File(root, "media");
        thumbDir = new File(root, "thumbs");
        noteDir = new File(root, "notes");
        bookDir = new File(root, "books");
        indexFile = new File(root, "index.json");
        mediaDir.mkdirs();
        thumbDir.mkdirs();
        noteDir.mkdirs();
        bookDir.mkdirs();
        load();
        purgeOldTrash(TRASH_DAYS);
    }

    // ------------------------------------------------------------------ persistence

    private synchronized void load() {
        items.clear();
        folders.clear();
        notebooks.clear();
        categories.clear();
        notes.clear();
        File src = indexFile;
        if (!src.exists()) {
            File bak = new File(root, "index.json.bak");
            if (bak.exists()) src = bak;
            else return;
        }
        try {
            JSONObject o = new JSONObject(new String(readAll(src), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray("items");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Item it = new Item();
                it.id = j.optString("id");
                it.name = j.optString("name");
                it.kind = j.optString("kind", KIND_FILE);
                it.mime = j.optString("mime");
                it.file = j.optString("file");
                it.size = j.optLong("size");
                it.createdAt = j.optLong("createdAt");
                it.scope = j.optString("scope", SCOPE_GALLERY);
                it.folderId = j.optString("folderId");
                it.notebookId = j.optString("notebookId");
                it.noteId = j.optString("noteId");
                it.caption = j.optString("caption");
                it.duration = j.optLong("duration");
                it.favorite = j.optBoolean("favorite", false);
                it.trashedAt = j.optLong("trashedAt", 0);
                it.hash = j.optString("hash", "");
                it.lastPos = j.optLong("lastPos", 0);
                it.lastOpened = j.optLong("lastOpened", 0);
                it.lastPage = j.optInt("lastPage", 0);
                if (!it.id.isEmpty()) items.add(it);
            }
            a = o.optJSONArray("folders");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Folder f = new Folder();
                f.id = j.optString("id");
                f.name = j.optString("name");
                f.color = j.optInt("color", 0xFF75416F);
                f.icon = j.optString("icon", "\uD83D\uDCC1");
                f.createdAt = j.optLong("createdAt");
                f.locked = j.optBoolean("locked", false);
                if (!f.id.isEmpty()) folders.add(f);
            }
            a = o.optJSONArray("notebooks");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Notebook n = new Notebook();
                n.id = j.optString("id");
                n.name = j.optString("name");
                n.createdAt = j.optLong("createdAt");
                n.scope = j.optString("scope", SCOPE_MEDICAL);
                if (n.scope.isEmpty()) n.scope = SCOPE_MEDICAL;
                n.type = normType(j.optString("type", BOOK_FREE));
                n.cover = Math.max(0, j.optInt("cover", 0));
                if (!n.id.isEmpty()) notebooks.add(n);
            }
            a = o.optJSONArray("categories");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Category c = new Category();
                c.id = j.optString("id");
                c.name = j.optString("name");
                if (!c.id.isEmpty()) categories.add(c);
            }
            a = o.optJSONArray("notes");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Note n = new Note();
                n.id = j.optString("id");
                n.title = j.optString("title");
                n.preview = j.optString("preview");
                n.notebookId = j.optString("notebookId");
                n.categoryId = j.optString("categoryId");
                n.createdAt = j.optLong("createdAt");
                n.updatedAt = j.optLong("updatedAt");
                n.scope = j.optString("scope", SCOPE_MEDICAL);
                if (n.scope.isEmpty()) n.scope = SCOPE_MEDICAL;
                if (!n.id.isEmpty()) notes.add(n);
            }
            defaultsSeeded = o.optBoolean("defaultsSeeded", false);
        } catch (Exception e) {
            loadError = "تعذّرت قراءة فهرس الأستوديو";
        }
    }

    /** Writes index.json atomically. Returns false if the write failed. */
    private synchronized boolean save() {
        try {
            writeAtomic(indexFile, indexSnapshot().getBytes(StandardCharsets.UTF_8), true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Current metadata as JSON. Used by save() and by offline backups. */
    synchronized String indexSnapshot() {
        try {
            JSONObject o = new JSONObject();
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject j = new JSONObject();
                j.put("id", it.id);
                j.put("name", it.name);
                j.put("kind", it.kind);
                j.put("mime", it.mime);
                j.put("file", it.file);
                j.put("size", it.size);
                j.put("createdAt", it.createdAt);
                j.put("scope", it.scope);
                j.put("folderId", it.folderId);
                j.put("notebookId", it.notebookId);
                j.put("noteId", it.noteId);
                j.put("caption", it.caption);
                j.put("duration", it.duration);
                j.put("favorite", it.favorite);
                j.put("trashedAt", it.trashedAt);
                j.put("hash", it.hash);
                j.put("lastPos", it.lastPos);
                j.put("lastOpened", it.lastOpened);
                if (it.lastPage > 0) j.put("lastPage", it.lastPage);
                a.put(j);
            }
            o.put("items", a);
            a = new JSONArray();
            for (Folder f : folders) {
                JSONObject j = new JSONObject();
                j.put("id", f.id);
                j.put("name", f.name);
                j.put("color", f.color);
                j.put("icon", f.icon);
                j.put("createdAt", f.createdAt);
                if (f.locked) j.put("locked", true);
                a.put(j);
            }
            o.put("folders", a);
            a = new JSONArray();
            for (Notebook n : notebooks) {
                JSONObject j = new JSONObject();
                j.put("id", n.id);
                j.put("name", n.name);
                j.put("createdAt", n.createdAt);
                if (n.scope != null && !n.scope.isEmpty() && !SCOPE_MEDICAL.equals(n.scope)) j.put("scope", n.scope);
                if (!BOOK_FREE.equals(normType(n.type))) j.put("type", normType(n.type));
                if (n.cover != 0) j.put("cover", n.cover);
                a.put(j);
            }
            o.put("notebooks", a);
            a = new JSONArray();
            for (Category c : categories) {
                JSONObject j = new JSONObject();
                j.put("id", c.id);
                j.put("name", c.name);
                a.put(j);
            }
            o.put("categories", a);
            a = new JSONArray();
            for (Note n : notes) {
                JSONObject j = new JSONObject();
                j.put("id", n.id);
                j.put("title", n.title);
                j.put("preview", n.preview);
                j.put("notebookId", n.notebookId);
                j.put("categoryId", n.categoryId);
                j.put("createdAt", n.createdAt);
                j.put("updatedAt", n.updatedAt);
                if (n.scope != null && !SCOPE_MEDICAL.equals(n.scope) && !n.scope.isEmpty()) j.put("scope", n.scope);
                a.put(j);
            }
            o.put("notes", a);
            o.put("defaultsSeeded", defaultsSeeded);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Writes bytes to a temp file, flushes to disk, then renames over the target. */
    private static void writeAtomic(File target, byte[] data, boolean keepBackup) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(data);
            os.flush();
            os.getFD().sync();
        }
        if (keepBackup && target.exists()) {
            File bak = new File(target.getParentFile(), target.getName() + ".bak");
            //noinspection ResultOfMethodCallIgnored
            bak.delete();
            //noinspection ResultOfMethodCallIgnored
            target.renameTo(bak);
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!tmp.renameTo(target)) throw new IOException("rename failed");
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(32, f.length()));
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    synchronized String loadError() {
        return loadError;
    }

    private static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + Long.toString(System.nanoTime() & 0xFFFFFF, 36)
                + Integer.toString((int) (Math.random() * 1296), 36);
    }

    // ------------------------------------------------------------------ files

    File fileOf(Item it) {
        return new File(mediaDir, it.file);
    }

    File thumbOf(Item it) {
        return new File(thumbDir, it.id + ".jpg");
    }

    /** True when the stored file is really on disk (a missing file is shown as broken, never hidden). */
    boolean exists(Item it) {
        return fileOf(it).exists();
    }

    private static String queryName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Exception ignored) {
            // fall through
        }
        String seg = uri.getLastPathSegment();
        return seg == null ? "file" : seg;
    }

    private static long querySize(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
            // unknown size
        }
        return -1;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String e = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return e.length() > 8 ? "" : e.replaceAll("[^a-z0-9]", "");
    }

    static String kindOf(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.isEmpty() || "application/octet-stream".equals(m)) {
            String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name));
            if (guess != null) m = guess;
        }
        if (m.startsWith("image/")) return KIND_IMAGE;
        if (m.startsWith("video/")) return KIND_VIDEO;
        return KIND_FILE;
    }

    /**
     * Copies picked files (content:// Uris) into private storage. Streams in 64 KB chunks, so a multi-gigabyte
     * video never sits in memory and is never Base64-encoded. Call off the UI thread.
     * [scope]/[folderId]/[notebookId]/[noteId] decide where each imported item belongs.
     * [accept] limits the kind: "image", "video", "media" (image or video) or null for anything.
     */
    ImportResult importUris(List<Uri> uris, String scope, String folderId, String notebookId, String noteId,
                            String accept, Progress progress) {
        ImportResult res = new ImportResult();
        ContentResolver cr = app.getContentResolver();
        int total = uris.size();
        for (int i = 0; i < total; i++) {
            Uri uri = uris.get(i);
            String name = queryName(cr, uri);
            if (progress != null) progress.onProgress(i, total, name);
            File out = null;
            try {
                String mime = cr.getType(uri);
                String kind = kindOf(mime, name);
                if (!accepts(accept, kind)) {
                    res.errors.add(name + ": نوع الملف غير مدعوم هنا");
                    continue;
                }
                long expected = querySize(cr, uri);
                long free = freeSpace();
                if (expected > 0 && free > 0 && expected + 8L * 1024 * 1024 > free) {
                    res.errors.add(name + ": مساحة التخزين لا تكفي");
                    continue;
                }
                String id = newId();
                String ext = extOf(name);
                if (ext.isEmpty() && mime != null) {
                    String e = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                    if (e != null) ext = e;
                }
                String fileName = ext.isEmpty() ? id : id + "." + ext;
                out = new File(mediaDir, fileName);
                long written = 0;
                InputStream in = cr.openInputStream(uri);
                if (in == null) {
                    res.errors.add(name + ": تعذّر فتح الملف");
                    continue;
                }
                MessageDigest md = null;
                try {
                    md = MessageDigest.getInstance("SHA-1");
                } catch (Exception ignored) {
                    // no hash: duplicates simply are not detected
                }
                try (InputStream input = in; OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = input.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        if (md != null) md.update(buf, 0, n);
                        written += n;
                    }
                    os.flush();
                }
                String hash = md == null ? "" : hex(md.digest());
                if (!hash.isEmpty() && duplicateOf(hash, written, scope) != null) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    res.errors.add(name + ": موجود مسبقاً (تم تخطي التكرار)");
                    continue;
                }
                if (written <= 0) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    res.errors.add(name + ": الملف فارغ أو غير قابل للقراءة");
                    continue;
                }
                if (expected > 0 && written != expected) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    res.errors.add(name + ": لم يكتمل النسخ، حاول مرة أخرى");
                    continue;
                }
                Item it = new Item();
                it.id = id;
                it.name = name;
                it.kind = kind;
                it.mime = mime == null ? "" : mime;
                it.file = fileName;
                it.size = written;
                it.hash = hash;
                it.createdAt = System.currentTimeMillis();
                it.scope = scope;
                it.folderId = folderId == null ? "" : folderId;
                it.notebookId = notebookId == null ? "" : notebookId;
                it.noteId = noteId == null ? "" : noteId;
                synchronized (this) {
                    items.add(0, it);
                    if (!save()) {
                        items.remove(it);
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        res.errors.add(name + ": تعذّر حفظ الفهرس");
                        continue;
                    }
                }
                res.added.add(it);
            } catch (Exception | OutOfMemoryError e) {
                if (out != null) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                }
                res.errors.add(name + ": فشل الحفظ");
            }
        }
        if (progress != null) progress.onProgress(total, total, "");
        return res;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    /** An existing (not trashed) item of the same scope with identical content, or null. */
    private synchronized Item duplicateOf(String hash, long size, String scope) {
        for (Item it : items) {
            if (it.trashedAt == 0 && it.size == size && hash.equals(it.hash) && it.scope.equals(scope)) return it;
        }
        return null;
    }

    /** accept: null = anything, "image", "video", or "media" (image or video). */
    private static boolean accepts(String accept, String kind) {
        if (accept == null) return true;
        if ("media".equals(accept)) return KIND_IMAGE.equals(kind) || KIND_VIDEO.equals(kind);
        return accept.equals(kind);
    }

    private long freeSpace() {
        try {
            StatFs fs = new StatFs(root.getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ items

    /** Items of the gallery (no folder) or of one folder, newest first. */
    synchronized List<Item> galleryItems(String folderId) {
        List<Item> out = new ArrayList<>();
        String f = folderId == null ? "" : folderId;
        if (!f.isEmpty() && !folderAccessible(f)) return out;
        for (Item it : items) {
            if (it.trashedAt == 0 && SCOPE_GALLERY.equals(it.scope) && it.folderId.equals(f)) {
                if (f.isEmpty() && concealed(it)) continue;
                out.add(it);
            }
        }
        return out;
    }

    /** Every gallery file regardless of folder (the main gallery shows all of them). */
    synchronized List<Item> allGalleryItems() {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.trashedAt == 0 && SCOPE_GALLERY.equals(it.scope) && !concealed(it)) out.add(it);
        }
        return out;
    }

    synchronized int folderItemCount(String folderId) {
        int n = 0;
        for (Item it : items) if (it.trashedAt == 0 && SCOPE_GALLERY.equals(it.scope) && it.folderId.equals(folderId)) n++;
        return n;
    }

    /** Medical images: all, one notebook, or those attached to one note. */
    synchronized List<Item> medicalImages(String notebookId, String noteId) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.trashedAt != 0) continue;
            if (noteId != null) {
                if (it.noteId.equals(noteId)) out.add(it);
                continue;
            }
            if (!SCOPE_MEDICAL.equals(it.scope)) continue;
            if (notebookId != null && !it.notebookId.equals(notebookId)) continue;
            out.add(it);
        }
        return out;
    }

    synchronized Item item(String id) {
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    synchronized void moveItems(List<String> ids, String folderId) {
        for (Item it : items) {
            if (ids.contains(it.id) && SCOPE_GALLERY.equals(it.scope)) it.folderId = folderId == null ? "" : folderId;
        }
        save();
    }

    synchronized void setCaption(String id, String caption) {
        Item it = item(id);
        if (it != null) {
            it.caption = caption;
            save();
        }
    }

    synchronized void setDuration(String id, long ms) {
        Item it = item(id);
        if (it != null && it.duration != ms) {
            it.duration = ms;
            save();
        }
    }

    synchronized void deleteItems(List<String> ids) {
        List<Item> keep = new ArrayList<>();
        for (Item it : items) {
            if (ids.contains(it.id)) removeItemFiles(it);
            else keep.add(it);
        }
        items.clear();
        items.addAll(keep);
        save();
    }

    // ---- favourites, trash, recents, positions, statistics

    synchronized void setFavorite(List<String> ids, boolean fav) {
        for (Item it : items) if (ids.contains(it.id)) it.favorite = fav;
        save();
    }

    synchronized List<Item> favorites() {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.trashedAt == 0 && it.favorite && SCOPE_GALLERY.equals(it.scope) && !concealed(it)) out.add(it);
        }
        return out;
    }

    /** Moves files to the trash (recoverable for TRASH_DAYS days). Files on disk are kept until purged. */
    synchronized void trashItems(List<String> ids) {
        long now = System.currentTimeMillis();
        for (Item it : items) if (ids.contains(it.id)) it.trashedAt = now;
        save();
    }

    synchronized void restoreItems(List<String> ids) {
        for (Item it : items) {
            if (!ids.contains(it.id)) continue;
            it.trashedAt = 0;
            // a folder deleted meanwhile: the file returns to the main gallery
            if (!it.folderId.isEmpty() && folder(it.folderId) == null) it.folderId = "";
        }
        save();
    }

    synchronized List<Item> trashedItems() {
        List<Item> out = new ArrayList<>();
        for (Item it : items) if (it.trashedAt != 0) out.add(it);
        Collections.sort(out, (a, b) -> Long.compare(b.trashedAt, a.trashedAt));
        return out;
    }

    synchronized void emptyTrash() {
        deleteItems(ids(trashedItems()));
    }

    /** Permanently removes trashed files older than [days]. */
    synchronized void purgeOldTrash(int days) {
        long limit = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
        List<String> old = new ArrayList<>();
        for (Item it : items) if (it.trashedAt != 0 && it.trashedAt < limit) old.add(it.id);
        if (!old.isEmpty()) deleteItems(old);
    }

    static List<String> ids(List<Item> list) {
        List<String> out = new ArrayList<>();
        for (Item it : list) out.add(it.id);
        return out;
    }

    synchronized void markOpened(String id) {
        Item it = item(id);
        if (it != null) {
            it.lastOpened = System.currentTimeMillis();
            save();
        }
    }

    synchronized void setLastPos(String id, long ms) {
        Item it = item(id);
        if (it != null && it.lastPos != ms) {
            it.lastPos = ms;
            save();
        }
    }

    /** Recently opened gallery files, newest first. */
    synchronized List<Item> recent(int max) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.trashedAt == 0 && it.lastOpened > 0 && SCOPE_GALLERY.equals(it.scope) && !concealed(it)) out.add(it);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastOpened, a.lastOpened));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    static final int SORT_DATE = 0;
    static final int SORT_SIZE = 1;
    static final int SORT_NAME = 2;

    static void sort(List<Item> list, int mode) {
        if (mode == SORT_SIZE) Collections.sort(list, (a, b) -> Long.compare(b.size, a.size));
        else if (mode == SORT_NAME) Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        else Collections.sort(list, (a, b) -> Long.compare(b.createdAt, a.createdAt));
    }

    /** Bytes used by files in the app (trash included). */
    synchronized long usedBytes() {
        long n = 0;
        for (Item it : items) n += Math.max(0, it.size);
        return n;
    }

    synchronized long trashBytes() {
        long n = 0;
        for (Item it : items) if (it.trashedAt != 0) n += Math.max(0, it.size);
        return n;
    }

    long freeBytes() {
        return freeSpace();
    }

    long totalBytes() {
        try {
            return new StatFs(root.getAbsolutePath()).getTotalBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    /** The [max] biggest files that are not in the trash. */
    synchronized List<Item> largest(int max) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) if (it.trashedAt == 0 && !concealed(it)) out.add(it);
        sort(out, SORT_SIZE);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private void removeItemFiles(Item it) {
        //noinspection ResultOfMethodCallIgnored
        fileOf(it).delete();
        //noinspection ResultOfMethodCallIgnored
        thumbOf(it).delete();
    }

    // ------------------------------------------------------------------ folders

    synchronized List<Folder> folders() {
        return new ArrayList<>(folders);
    }

    synchronized boolean folderIsLocked(String id) {
        Folder f = folder(id);
        return f != null && f.locked;
    }

    /** A folder with no lock, or one the user unlocked earlier in this session. */
    synchronized boolean folderAccessible(String id) {
        if (id == null || id.isEmpty()) return true;
        Folder f = folder(id);
        if (f == null || !f.locked) return true;
        return sessionOpen.contains(id);
    }

    synchronized void grantFolder(String id) {
        if (id != null && !id.isEmpty()) sessionOpen.add(id);
    }

    synchronized void grantAllLocked() {
        for (Folder f : folders) if (f.locked) sessionOpen.add(f.id);
    }

    /** Called when the app lock engages again: private albums close too. */
    synchronized void revokeFolders() {
        sessionOpen.clear();
    }

    synchronized void setFolderLocked(String id, boolean locked) {
        Folder f = folder(id);
        if (f == null) return;
        f.locked = locked;
        if (!locked) sessionOpen.remove(id);
        save();
    }

    private boolean concealed(Item it) {
        return it.folderId != null && !it.folderId.isEmpty() && !folderAccessible(it.folderId);
    }

    synchronized Folder folder(String id) {
        for (Folder f : folders) if (f.id.equals(id)) return f;
        return null;
    }

    synchronized Folder addFolder(String name, int color, String icon) {
        Folder f = new Folder();
        f.id = newId();
        f.name = clean(name);
        f.color = color;
        f.icon = icon;
        f.createdAt = System.currentTimeMillis();
        folders.add(f);
        save();
        return f;
    }

    synchronized void updateFolder(String id, String name, int color, String icon) {
        Folder f = folder(id);
        if (f == null) return;
        f.name = clean(name);
        f.color = color;
        f.icon = icon;
        save();
    }

    /** Deletes the folder only: its files stay in the main gallery (never deleted with the folder). */
    synchronized void deleteFolder(String id) {
        for (Item it : items) if (SCOPE_GALLERY.equals(it.scope) && it.folderId.equals(id)) it.folderId = "";
        for (int i = 0; i < folders.size(); i++) {
            if (folders.get(i).id.equals(id)) {
                folders.remove(i);
                break;
            }
        }
        save();
    }

    boolean folderNameTaken(String name, String exceptId) {
        String key = clean(name).toLowerCase(Locale.ROOT);
        synchronized (this) {
            for (Folder f : folders) {
                if (!f.id.equals(exceptId) && f.name.toLowerCase(Locale.ROOT).equals(key)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ notebooks / categories

    synchronized List<Notebook> notebooks() {
        return notebooksOf(SCOPE_MEDICAL);
    }

    synchronized List<Notebook> notebooksOf(String scope) {
        List<Notebook> out = new ArrayList<>();
        String want = scope == null || scope.isEmpty() ? SCOPE_MEDICAL : scope;
        for (Notebook n : notebooks) if (want.equals(scopeOf(n))) out.add(n);
        return out;
    }

    private static String scopeOf(Notebook n) {
        return n.scope == null || n.scope.isEmpty() ? SCOPE_MEDICAL : n.scope;
    }

    private static String scopeOf(Note n) {
        return n.scope == null || n.scope.isEmpty() ? SCOPE_MEDICAL : n.scope;
    }

    synchronized Notebook notebook(String id) {
        for (Notebook n : notebooks) if (n.id.equals(id)) return n;
        return null;
    }

    synchronized Notebook addNotebook(String name) {
        return addNotebook(name, SCOPE_MEDICAL);
    }

    synchronized Notebook addNotebook(String name, String scope) {
        return addNotebook(name, scope, BOOK_FREE, 0);
    }

    synchronized Notebook addNotebook(String name, String scope, String type, int cover) {
        Notebook n = new Notebook();
        n.type = normType(type);
        n.cover = Math.max(0, cover);
        n.id = newId();
        n.name = clean(name);
        n.createdAt = System.currentTimeMillis();
        n.scope = scope == null || scope.isEmpty() ? SCOPE_MEDICAL : scope;
        notebooks.add(n);
        save();
        return n;
    }

    static String normType(String t) {
        if (BOOK_QA.equals(t) || BOOK_STUDY.equals(t) || BOOK_PLANNER.equals(t)) return t;
        return BOOK_FREE;
    }

    synchronized void setNotebookCover(String id, int cover) {
        Notebook n = notebook(id);
        if (n != null) {
            n.cover = Math.max(0, cover);
            save();
        }
    }

    // ---- pages of a notebook: one JSON file per notebook (books/<id>.json), written atomically

    File bookFile(String id) {
        return new File(bookDir, id + ".json");
    }

    /** Raw page data of a notebook, or "" when it has none yet. */
    String bookJson(String id) {
        File f = bookFile(id);
        if (!f.exists()) {
            File bak = new File(bookDir, id + ".json.bak");
            if (!bak.exists()) return "";
            f = bak;
        }
        try {
            return new String(readAll(f), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Returns false when the write failed (the caller keeps the data and retries). */
    synchronized boolean saveBookJson(String id, String json) {
        try {
            writeAtomic(bookFile(id), json.getBytes(StandardCharsets.UTF_8), true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    synchronized void renameNotebook(String id, String name) {
        Notebook n = notebook(id);
        if (n != null) {
            n.name = clean(name);
            save();
        }
    }

    /** Deletes a notebook with its notes and images. */
    synchronized void deleteNotebook(String id) {
        List<String> noteIds = new ArrayList<>();
        for (Note n : notes) if (n.notebookId.equals(id)) noteIds.add(n.id);
        for (String nid : noteIds) deleteNote(nid);
        List<String> imgs = new ArrayList<>();
        for (Item it : items) if (it.notebookId.equals(id)) imgs.add(it.id);
        deleteItems(imgs);
        for (int i = 0; i < notebooks.size(); i++) {
            if (notebooks.get(i).id.equals(id)) {
                notebooks.remove(i);
                break;
            }
        }
        //noinspection ResultOfMethodCallIgnored
        bookFile(id).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(bookDir, id + ".json.bak").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(bookDir, id + ".json.tmp").delete();
        save();
    }

    synchronized int noteCount(String notebookId) {
        int n = 0;
        for (Note x : notes) if (x.notebookId.equals(notebookId)) n++;
        return n;
    }

    synchronized List<Category> categories() {
        if (!defaultsSeeded) {
            defaultsSeeded = true;
            for (String s : new String[] {"التشريح", "علم الأدوية", "علم الأمراض", "وظائف الأعضاء"}) {
                Category c = new Category();
                c.id = newId();
                c.name = s;
                categories.add(c);
            }
            save();
        }
        return new ArrayList<>(categories);
    }

    synchronized Category category(String id) {
        for (Category c : categories) if (c.id.equals(id)) return c;
        return null;
    }

    /** Adds a category; returns null when the name is empty or already used. */
    synchronized Category addCategory(String name) {
        String n = clean(name);
        if (n.isEmpty()) return null;
        for (Category c : categories) if (c.name.equalsIgnoreCase(n)) return null;
        Category c = new Category();
        c.id = newId();
        c.name = n;
        categories.add(c);
        save();
        return c;
    }

    synchronized boolean categoryNameTaken(String name, String exceptId) {
        String n = clean(name);
        for (Category c : categories) if (!c.id.equals(exceptId) && c.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    synchronized boolean renameCategory(String id, String name) {
        String n = clean(name);
        if (n.isEmpty()) return false;
        for (Category c : categories) if (!c.id.equals(id) && c.name.equalsIgnoreCase(n)) return false;
        Category c = category(id);
        if (c == null) return false;
        c.name = n;
        save();
        return true;
    }

    /** Deletes a category; its notes stay and become uncategorised. */
    synchronized void deleteCategory(String id) {
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id.equals(id)) {
                categories.remove(i);
                break;
            }
        }
        for (Note n : notes) if (n.categoryId.equals(id)) n.categoryId = "";
        save();
    }

    // ------------------------------------------------------------------ notes

    /** Notes filtered by notebook and/or category (null = no filter), most recently edited first. */
    synchronized List<Note> notes(String notebookId, String categoryId) {
        List<Note> out = new ArrayList<>();
        for (Note n : notes) {
            if (notebookId == null && !SCOPE_MEDICAL.equals(scopeOf(n))) continue;
            if (notebookId != null && !n.notebookId.equals(notebookId)) continue;
            if (categoryId != null && !n.categoryId.equals(categoryId)) continue;
            out.add(n);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    synchronized Note note(String id) {
        for (Note n : notes) if (n.id.equals(id)) return n;
        return null;
    }

    synchronized Note newNote(String notebookId, String categoryId) {
        return newNote(notebookId, categoryId, SCOPE_MEDICAL);
    }

    synchronized Note newNote(String notebookId, String categoryId, String scope) {
        Note n = new Note();
        n.id = newId();
        n.notebookId = notebookId == null ? "" : notebookId;
        n.categoryId = categoryId == null ? "" : categoryId;
        n.scope = scope == null || scope.isEmpty() ? SCOPE_MEDICAL : scope;
        n.createdAt = System.currentTimeMillis();
        n.updatedAt = n.createdAt;
        notes.add(n);
        save();
        return n;
    }

    /** Reads the full body of a note (no truncation). */
    String noteBody(String id) {
        File f = new File(noteDir, id + ".txt");
        if (!f.exists()) return "";
        try {
            return new String(readAll(f), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Saves a note: the body is written atomically to its own file (so long text is never cut), and only a short
     * preview goes into the index. Returns false when the write failed.
     */
    synchronized boolean saveNote(String id, String title, String body, String notebookId, String categoryId) {
        Note n = note(id);
        if (n == null) return false;
        try {
            writeAtomic(new File(noteDir, id + ".txt"), body.getBytes(StandardCharsets.UTF_8), false);
        } catch (Exception e) {
            return false;
        }
        n.title = title.trim();
        n.notebookId = notebookId == null ? "" : notebookId;
        n.categoryId = categoryId == null ? "" : categoryId;
        n.preview = previewOf(body);
        n.updatedAt = System.currentTimeMillis();
        boolean ok = save();
        if (ok) {
            for (Item it : items) {
                if (it.noteId.equals(id)) it.notebookId = n.notebookId;
            }
            save();
        }
        return ok;
    }

    synchronized void moveNote(String id, String notebookId, String categoryId) {
        Note n = note(id);
        if (n == null) return;
        if (notebookId != null) {
            n.notebookId = notebookId;
            for (Item it : items) {
                if (it.noteId.equals(id)) it.notebookId = notebookId;
            }
        }
        if (categoryId != null) n.categoryId = categoryId;
        save();
    }

    /** Deletes a note, its body file and the images attached to it. */
    synchronized void deleteNote(String id) {
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).id.equals(id)) {
                notes.remove(i);
                break;
            }
        }
        //noinspection ResultOfMethodCallIgnored
        new File(noteDir, id + ".txt").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(noteDir, id + ".txt.tmp").delete();
        List<String> imgs = new ArrayList<>();
        for (Item it : items) if (it.noteId.equals(id)) imgs.add(it.id);
        deleteItems(imgs);
        save();
    }

    /** Notes whose title or full content contains [query] (case-insensitive). Reads files: call off the UI thread. */
    List<Note> search(String query, String notebookId) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Note> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        List<Note> all;
        synchronized (this) {
            all = notes(notebookId, null);
        }
        for (Note n : all) {
            if (n.title.toLowerCase(Locale.ROOT).contains(q)
                    || noteBody(n.id).toLowerCase(Locale.ROOT).contains(q)) out.add(n);
        }
        return out;
    }

    /** Notes of one scope (study or medical), optionally inside a single notebook. */
    synchronized List<Note> notesInScope(String scope, String notebookId) {
        List<Note> out = new ArrayList<>();
        String want = scope == null || scope.isEmpty() ? SCOPE_MEDICAL : scope;
        for (Note n : notes) {
            if (!want.equals(scopeOf(n))) continue;
            if (notebookId != null && !notebookId.isEmpty() && !n.notebookId.equals(notebookId)) continue;
            out.add(n);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    /** Same as [search], but limited to one scope. Reads note files: call off the UI thread. */
    List<Note> searchScoped(String query, String scope, String notebookId) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Note> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        List<Note> all;
        synchronized (this) {
            all = notesInScope(scope, notebookId);
        }
        for (Note n : all) {
            if (n.title.toLowerCase(Locale.ROOT).contains(q)
                    || noteBody(n.id).toLowerCase(Locale.ROOT).contains(q)) out.add(n);
        }
        return out;
    }

    synchronized List<Item> scopeItems(String scope, String notebookId) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (it.trashedAt != 0 || !scope.equals(it.scope)) continue;
            if (notebookId != null && !notebookId.isEmpty() && !it.notebookId.equals(notebookId)) continue;
            out.add(it);
        }
        return out;
    }

    /** File-name (and caption) search. Skips albums that are still locked. */
    synchronized List<Item> searchItems(String query, String scope, String notebookId) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (Item it : items) {
            if (it.trashedAt != 0) continue;
            if (scope != null && !scope.isEmpty() && !scope.equals(it.scope)) continue;
            if (notebookId != null && !notebookId.isEmpty() && !it.notebookId.equals(notebookId)) continue;
            if (concealed(it)) continue;
            String blob = (it.name + " " + it.caption).toLowerCase(Locale.ROOT);
            if (blob.contains(q)) out.add(it);
        }
        return out;
    }

    synchronized List<Item> largerThan(long min) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) if (it.trashedAt == 0 && it.size >= min && !concealed(it)) out.add(it);
        sort(out, SORT_SIZE);
        return out;
    }

    synchronized int concealedTrashCount() {
        int n = 0;
        for (Item it : items) if (it.trashedAt != 0 && concealed(it)) n++;
        return n;
    }

    synchronized List<Item> visibleTrash() {
        List<Item> out = new ArrayList<>();
        for (Item it : trashedItems()) if (!concealed(it)) out.add(it);
        return out;
    }

    synchronized boolean renameItem(String id, String name) {
        Item it = item(id);
        String n = clean(name);
        if (it == null || n.isEmpty()) return false;
        it.name = n;
        return save();
    }

    synchronized void setLastPage(String id, int page) {
        Item it = item(id);
        if (it != null && it.lastPage != page) {
            it.lastPage = Math.max(0, page);
            save();
        }
    }

    File noteFile(String id) {
        return new File(noteDir, id + ".txt");
    }

    File mediaDir() {
        return mediaDir;
    }

    synchronized List<Item> allItems() {
        return new ArrayList<>(items);
    }

    synchronized List<Note> allNotes() {
        return new ArrayList<>(notes);
    }

    synchronized List<Notebook> allNotebooks() {
        return new ArrayList<>(notebooks);
    }

    /**
     * Registers a file that is already on disk (an edited copy, for example). The bytes are copied into
     * media/ — the source file is left in place. Returns null when the copy fails or the file is a duplicate.
     */
    Item importLocalFile(File src, String name, String mime, String scope, String folderId, String notebookId,
                         String noteId) {
        if (src == null || !src.exists()) return null;
        File out = null;
        try {
            String safeName = name == null || name.isEmpty() ? src.getName() : name;
            String kind = kindOf(mime, safeName);
            String id = newId();
            String ext = extOf(safeName);
            if (ext.isEmpty() && mime != null) {
                String e = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (e != null) ext = e;
            }
            String fileName = ext.isEmpty() ? id : id + "." + ext;
            out = new File(mediaDir, fileName);
            MessageDigest md = null;
            try {
                md = MessageDigest.getInstance("SHA-1");
            } catch (Exception ignored) {
                // duplicates will not be detected
            }
            long written = 0;
            try (InputStream input = new FileInputStream(src); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = input.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    if (md != null) md.update(buf, 0, n);
                    written += n;
                }
                os.flush();
            }
            if (written <= 0) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                return null;
            }
            String hash = md == null ? "" : hex(md.digest());
            String sc = scope == null ? SCOPE_GALLERY : scope;
            if (!hash.isEmpty() && duplicateOf(hash, written, sc) != null) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                return null;
            }
            Item it = new Item();
            it.id = id;
            it.name = safeName;
            it.kind = kind;
            it.mime = mime == null ? "" : mime;
            it.file = fileName;
            it.size = written;
            it.hash = hash;
            it.createdAt = System.currentTimeMillis();
            it.scope = sc;
            it.folderId = folderId == null ? "" : folderId;
            it.notebookId = notebookId == null ? "" : notebookId;
            it.noteId = noteId == null ? "" : noteId;
            synchronized (this) {
                items.add(0, it);
                if (!save()) {
                    items.remove(it);
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    return null;
                }
            }
            return it;
        } catch (Exception | OutOfMemoryError e) {
            if (out != null) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            return null;
        }
    }

    /** Replaces the bytes of an existing item and drops its cached thumbnail. */
    synchronized boolean replaceItemFile(String id, File src) {
        Item it = item(id);
        if (it == null || src == null || !src.exists()) return false;
        File dest = fileOf(it);
        File tmp = new File(mediaDir, dest.getName() + ".replacing");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            long written = 0;
            try (InputStream input = new FileInputStream(src); FileOutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = input.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    md.update(buf, 0, n);
                    written += n;
                }
                os.flush();
                os.getFD().sync();
            }
            if (written <= 0) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            if (dest.exists() && !dest.delete()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(dest)) return false;
            it.size = written;
            it.hash = hex(md.digest());
            //noinspection ResultOfMethodCallIgnored
            thumbOf(it).delete();
            return save();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    synchronized int countConflicts(JSONObject in) {
        int n = 0;
        JSONArray a = in.optJSONArray("items");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Item ex = item(j.optString("id"));
            if (ex != null && !sameItem(ex, j)) n++;
        }
        a = in.optJSONArray("notes");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Note ex = note(j.optString("id"));
            if (ex != null && (ex.updatedAt != j.optLong("updatedAt") || !ex.title.equals(j.optString("title")))) n++;
        }
        a = in.optJSONArray("folders");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Folder ex = folder(j.optString("id"));
            if (ex != null && !ex.name.equals(j.optString("name"))) n++;
        }
        return n;
    }

    private static boolean sameItem(Item ex, JSONObject j) {
        return ex.name.equals(j.optString("name"))
                && ex.size == j.optLong("size")
                && ex.hash.equals(j.optString("hash", ""))
                && ex.folderId.equals(j.optString("folderId"));
    }

    /** Merges metadata. Records that already exist are skipped unless [replace] is true. */
    synchronized MergeResult mergeBackup(JSONObject in, boolean replace) {
        MergeResult r = new MergeResult();
        JSONArray a = in.optJSONArray("folders");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null || j.optString("id").isEmpty()) continue;
            Folder ex = folder(j.optString("id"));
            if (ex == null) {
                Folder f = new Folder();
                f.id = j.optString("id");
                f.name = j.optString("name");
                f.color = j.optInt("color", 0xFF75416F);
                f.icon = j.optString("icon", "");
                f.createdAt = j.optLong("createdAt");
                f.locked = j.optBoolean("locked", false);
                folders.add(f);
                r.addedFolders++;
            } else if (replace) {
                ex.name = j.optString("name", ex.name);
                ex.color = j.optInt("color", ex.color);
                ex.icon = j.optString("icon", ex.icon);
                ex.locked = j.optBoolean("locked", ex.locked);
                r.updatedFolders++;
            } else r.skippedFolders++;
        }
        a = in.optJSONArray("notebooks");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null || j.optString("id").isEmpty()) continue;
            if (notebook(j.optString("id")) == null) {
                Notebook n = new Notebook();
                n.id = j.optString("id");
                n.name = j.optString("name");
                n.createdAt = j.optLong("createdAt");
                n.scope = j.optString("scope", SCOPE_MEDICAL);
                n.type = normType(j.optString("type", BOOK_FREE));
                n.cover = Math.max(0, j.optInt("cover", 0));
                notebooks.add(n);
            } else if (replace) {
                Notebook n = notebook(j.optString("id"));
                n.name = j.optString("name", n.name);
                if (j.has("type")) n.type = normType(j.optString("type", n.type));
                if (j.has("cover")) n.cover = Math.max(0, j.optInt("cover", n.cover));
                if (j.has("scope")) n.scope = j.optString("scope", n.scope);
            }
        }
        a = in.optJSONArray("categories");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null || j.optString("id").isEmpty()) continue;
            if (category(j.optString("id")) == null) {
                Category c = new Category();
                c.id = j.optString("id");
                c.name = j.optString("name");
                categories.add(c);
            }
        }
        a = in.optJSONArray("notes");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null || j.optString("id").isEmpty()) continue;
            Note ex = note(j.optString("id"));
            if (ex == null) {
                Note n = new Note();
                fillNote(n, j);
                notes.add(n);
                r.addedNotes++;
            } else if (replace) {
                fillNote(ex, j);
                r.updatedNotes++;
            } else r.skippedNotes++;
        }
        a = in.optJSONArray("items");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null || j.optString("id").isEmpty()) continue;
            Item ex = item(j.optString("id"));
            if (ex == null) {
                Item it = new Item();
                fillItem(it, j);
                items.add(it);
                r.addedItems++;
            } else if (replace) {
                String file = ex.file;
                long size = ex.size;
                String hash = ex.hash;
                fillItem(ex, j);
                File f = fileOf(ex);
                if (f.exists() && f.length() != ex.size) {
                    ex.file = file;
                    ex.size = size;
                    ex.hash = hash;
                }
                r.updatedItems++;
            } else r.skippedItems++;
        }
        if (in.has("defaultsSeeded")) defaultsSeeded = defaultsSeeded || in.optBoolean("defaultsSeeded");
        save();
        return r;
    }

    private static void fillNote(Note n, JSONObject j) {
        n.id = j.optString("id", n.id);
        n.title = j.optString("title");
        n.preview = j.optString("preview");
        n.notebookId = j.optString("notebookId");
        n.categoryId = j.optString("categoryId");
        n.createdAt = j.optLong("createdAt");
        n.updatedAt = j.optLong("updatedAt");
        n.scope = j.optString("scope", n.scope == null || n.scope.isEmpty() ? SCOPE_MEDICAL : n.scope);
    }

    private static void fillItem(Item it, JSONObject j) {
        it.id = j.optString("id", it.id);
        it.name = j.optString("name");
        it.kind = j.optString("kind", KIND_FILE);
        it.mime = j.optString("mime");
        it.file = j.optString("file");
        it.size = j.optLong("size");
        it.createdAt = j.optLong("createdAt");
        it.scope = j.optString("scope", SCOPE_GALLERY);
        it.folderId = j.optString("folderId");
        it.notebookId = j.optString("notebookId");
        it.noteId = j.optString("noteId");
        it.caption = j.optString("caption");
        it.duration = j.optLong("duration");
        it.favorite = j.optBoolean("favorite", false);
        it.trashedAt = j.optLong("trashedAt", 0);
        it.hash = j.optString("hash", "");
        it.lastPos = j.optLong("lastPos", 0);
        it.lastOpened = j.optLong("lastOpened", 0);
        it.lastPage = j.optInt("lastPage", 0);
    }

    private static String previewOf(String body) {
        String s = body.length() > 400 ? body.substring(0, 400) : body;
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 140 ? s.substring(0, 140) : s;
    }

    static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
