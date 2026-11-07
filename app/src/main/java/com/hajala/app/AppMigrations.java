package com.hajala.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-schema versioning for ARCANUM's local data (SharedPreferences + files/studio + files/media).
 *
 * HOW TO CHANGE THE DATA FORMAT IN A FUTURE VERSION
 *  1. Raise CURRENT_SCHEMA by one.
 *  2. Add ONE step to steps() that upgrades the old format to the new one in place.
 *     A step must be additive: read the old data, write the new form, never delete user files.
 *  3. Leave the readers able to cope with the old format as well (they already default missing fields).
 * Before every step the runner snapshots prefs + studio index + notes, and restores them if the step fails
 * or the app dies halfway. Media files are never touched by the runner.
 *
 * Schema 1 is the baseline: the format shipped in 1.6.x. Existing installs and fresh installs are both "1".
 */
final class AppMigrations {
    static final int CURRENT_SCHEMA = 1;

    private static final String META = "arcanum_meta";
    private static final String K_SCHEMA = "schema";
    private static final String K_PENDING = "in_progress";
    private static final String K_ERROR = "last_error";

    private AppMigrations() {}

    /** steps().get(i) upgrades schema (i + 1) to (i + 2). Empty while CURRENT_SCHEMA == 1. */
    static List<MigrationRunner.Step<Context>> steps() {
        List<MigrationRunner.Step<Context>> s = new ArrayList<>();
        // Example for the day schema 2 exists:
        // s.add(c -> { /* upgrade schema 1 -> 2 here */ });
        return s;
    }

    /** Runs pending migrations. Call once, first thing in the launcher activity, before any store is created. */
    static MigrationRunner.Result run(Context c) {
        final Context app = c.getApplicationContext();
        final SharedPreferences sp = app.getSharedPreferences(META, Context.MODE_PRIVATE);
        MigrationRunner.Meta meta = new MigrationRunner.Meta() {
            public int schema() { return sp.getInt(K_SCHEMA, 1); }
            public void setSchema(int v) { sp.edit().putInt(K_SCHEMA, v).commit(); }
            public String inProgress() { return sp.getString(K_PENDING, ""); }
            public void setInProgress(String t) { sp.edit().putString(K_PENDING, t).commit(); }
            public void setLastError(String e) { sp.edit().putString(K_ERROR, e).commit(); }
        };
        MigrationRunner.Guard guard = new MigrationRunner.Guard() {
            public void snapshot(String tag) throws Exception { AppMigrations.snapshot(app, tag); }
            public void restore(String tag) throws Exception { AppMigrations.restore(app, tag); }
        };
        try {
            return MigrationRunner.run(app, meta, guard, steps(), CURRENT_SCHEMA);
        } catch (Throwable t) {
            return new MigrationRunner.Result(1, 1, false, String.valueOf(t));
        }
    }

    static int storedSchema(Context c) {
        return c.getApplicationContext().getSharedPreferences(META, Context.MODE_PRIVATE).getInt(K_SCHEMA, 1);
    }

    static String lastError(Context c) {
        return c.getApplicationContext().getSharedPreferences(META, Context.MODE_PRIVATE).getString(K_ERROR, "");
    }

    // ------------------------------------------------------------------ snapshots (metadata only, never media)

    private static File snapRoot(Context c) {
        return new File(c.getFilesDir(), "backups/migrations");
    }

    private static File prefsDir(Context c) {
        return new File(c.getApplicationInfo().dataDir, "shared_prefs");
    }

    private static boolean skipPrefs(String name) {
        return name.startsWith(META) || name.startsWith("arcanum_update");
    }

    static void snapshot(Context c, String tag) throws Exception {
        File dest = new File(snapRoot(c), tag);
        deleteTree(dest);
        if (!dest.mkdirs()) throw new java.io.IOException("cannot create " + dest);
        File pd = prefsDir(c);
        File[] prefs = pd.listFiles();
        if (prefs != null) {
            for (File f : prefs) {
                if (f.isFile() && f.getName().endsWith(".xml") && !skipPrefs(f.getName())) {
                    copy(f, new File(dest, "prefs/" + f.getName()));
                }
            }
        }
        File studio = new File(c.getFilesDir(), "studio");
        File idx = new File(studio, "index.json");
        if (idx.exists()) copy(idx, new File(dest, "studio/index.json"));
        File bak = new File(studio, "index.json.bak");
        if (bak.exists()) copy(bak, new File(dest, "studio/index.json.bak"));
        File notes = new File(studio, "notes");
        File[] ns = notes.listFiles();
        if (ns != null) for (File f : ns) if (f.isFile()) copy(f, new File(dest, "studio/notes/" + f.getName()));
        // notebook pages (one json + .bak per notebook)
        File[] bs = new File(studio, "books").listFiles();
        if (bs != null) for (File f : bs) if (f.isFile()) copy(f, new File(dest, "studio/books/" + f.getName()));
        // keep only the three newest snapshots
        File[] all = snapRoot(c).listFiles();
        if (all != null && all.length > 3) {
            java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 3; i < all.length; i++) if (!all[i].getName().equals(tag)) deleteTree(all[i]);
        }
    }

    static void restore(Context c, String tag) throws Exception {
        File src = new File(snapRoot(c), tag);
        if (!src.isDirectory()) throw new java.io.IOException("snapshot missing: " + tag);
        File[] prefs = new File(src, "prefs").listFiles();
        if (prefs != null) for (File f : prefs) copy(f, new File(prefsDir(c), f.getName()));
        File studio = new File(c.getFilesDir(), "studio");
        File idx = new File(src, "studio/index.json");
        if (idx.exists()) copy(idx, new File(studio, "index.json"));
        File bak = new File(src, "studio/index.json.bak");
        if (bak.exists()) copy(bak, new File(studio, "index.json.bak"));
        File[] ns = new File(src, "studio/notes").listFiles();
        if (ns != null) for (File f : ns) copy(f, new File(studio, "notes/" + f.getName()));
        File[] bs = new File(src, "studio/books").listFiles();
        if (bs != null) for (File f : bs) copy(f, new File(studio, "books/" + f.getName()));
    }

    static void copy(File from, File to) throws Exception {
        File parent = to.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new java.io.IOException("mkdir " + parent);
        File tmp = new File(to.getPath() + ".tmp");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
        if (to.exists() && !to.delete()) throw new java.io.IOException("replace " + to);
        if (!tmp.renameTo(to)) throw new java.io.IOException("rename " + tmp);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
