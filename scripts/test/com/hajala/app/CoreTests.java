package com.hajala.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Plain-JVM tests for the update/migration core. Run: scripts/test-core.sh */
public final class CoreTests {
    static int failed = 0;

    static void check(boolean cond, String name) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + name);
        if (!cond) failed++;
    }

    static final class FakeMeta implements MigrationRunner.Meta {
        int schema = 1;
        String inProgress = "";
        String err = "";
        public int schema() { return schema; }
        public void setSchema(int v) { schema = v; }
        public String inProgress() { return inProgress; }
        public void setInProgress(String t) { inProgress = t; }
        public void setLastError(String e) { err = e; }
    }

    /** Guard that really copies a data file, like the Android one copies index.json/prefs. */
    static final class FileGuard implements MigrationRunner.Guard {
        final File data;
        final File snapDir;
        boolean failSnapshot;
        FileGuard(File data, File snapDir) { this.data = data; this.snapDir = snapDir; snapDir.mkdirs(); }
        public void snapshot(String tag) throws Exception {
            if (failSnapshot) throw new Exception("disk full");
            Files.copy(data.toPath(), new File(snapDir, tag).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        public void restore(String tag) throws Exception {
            Files.copy(new File(snapDir, tag).toPath(), data.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void write(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        // ---- versions
        check(VersionUtil.compare("1.7.0", "1.6.1") > 0, "1.7.0 > 1.6.1");
        check(VersionUtil.compare("v1.10.0", "1.9.9") > 0, "v1.10.0 > 1.9.9 (numeric, not text)");
        check(VersionUtil.compare("1.6", "1.6.0") == 0, "1.6 == 1.6.0");
        check(VersionUtil.compare("1.7.0-beta", "1.7.0") == 0, "suffix ignored");
        check(VersionUtil.compare("abc", "0") == 0, "garbage parses as 0");
        check(VersionUtil.isNewer(9, 8) && !VersionUtil.isNewer(8, 8) && !VersionUtil.isNewer(7, 8), "isNewer uses versionCode");
        check("1.5 MB".equals(VersionUtil.formatBytes(1572864)), "formatBytes");

        // ---- media path remap (plain and JSON-escaped forms, other device user id)
        String pre = "file:///data/user/0/com.hajala.app/files/media/";
        String plain = "[{\"cover\":\"file:///data/user/10/com.hajala.app/files/media/cover_1.img\"}]";
        String esc = "[{\"cover\":\"file:\\/\\/\\/data\\/user\\/10\\/com.hajala.app\\/files\\/media\\/cover_1.img\"}]";
        check(PathRemap.remap(plain, pre).contains(pre + "cover_1.img") && !PathRemap.remap(plain, pre).contains("/user/10/"),
                "remap plain path to this install");
        String e2 = PathRemap.remap(esc, pre);
        check(e2.contains("file:\\/\\/\\/data\\/user\\/0\\/com.hajala.app\\/files\\/media\\/cover_1.img") && !e2.contains("user\\/10"),
                "remap JSON-escaped path keeps escaping");
        check("hello".equals(PathRemap.remap("hello", pre)) && PathRemap.remap(null, pre) == null, "remap ignores other strings");
        check("content://x/media/1".equals(PathRemap.remap("content://x/media/1", pre)), "remap leaves content:// uris alone");

        File tmp = Files.createTempDirectory("arcanum-test").toFile();
        File data = new File(tmp, "index.json");

        // ---- no-op when already current
        write(data, "{\"v\":1}");
        FakeMeta m = new FakeMeta();
        MigrationRunner.Result r = MigrationRunner.run(data, m, new FileGuard(data, new File(tmp, "s0")),
                new ArrayList<MigrationRunner.Step<File>>(), 1);
        check(r.ok && !r.migrated() && m.schema == 1, "current schema: nothing runs");

        // ---- successful 1 -> 3 chain, old data preserved & upgraded
        write(data, "{\"v\":1,\"notes\":[\"keep me\"]}");
        m = new FakeMeta();
        List<MigrationRunner.Step<File>> ok = new ArrayList<>();
        ok.add(f -> write(f, read(f).replace("\"v\":1", "\"v\":2")));
        ok.add(f -> write(f, read(f).replace("\"v\":2", "\"v\":3")));
        r = MigrationRunner.run(data, m, new FileGuard(data, new File(tmp, "s1")), ok, 3);
        check(r.ok && r.migrated() && m.schema == 3 && m.inProgress.isEmpty(), "chain 1->3 succeeds and advances schema");
        check(read(data).contains("keep me") && read(data).contains("\"v\":3"), "chain keeps old content");

        // ---- failing step: rolled back, schema NOT advanced, data identical to before
        write(data, "{\"v\":1,\"notes\":[\"precious\"]}");
        m = new FakeMeta();
        List<MigrationRunner.Step<File>> bad = new ArrayList<>();
        bad.add(f -> write(f, read(f).replace("\"v\":1", "\"v\":2")));
        bad.add(f -> { write(f, "CORRUPTED HALF WRITE"); throw new IllegalStateException("boom"); });
        r = MigrationRunner.run(data, m, new FileGuard(data, new File(tmp, "s2")), bad, 3);
        check(!r.ok && m.schema == 2, "failure stops at the failing step (schema 2, step 1 kept)");
        check(read(data).equals("{\"v\":2,\"notes\":[\"precious\"]}"), "failed step is rolled back to the pre-step data");
        check(m.err.contains("boom") && m.inProgress.isEmpty(), "error recorded, no stale in-progress flag");

        // ---- crash in the middle of a step (process died): next launch rolls back first
        write(data, "{\"v\":1,\"notes\":[\"crash-test\"]}");
        m = new FakeMeta();
        FileGuard g = new FileGuard(data, new File(tmp, "s3"));
        g.snapshot("pre-schema-1");
        write(data, "HALF WRITTEN GARBAGE");
        m.inProgress = "pre-schema-1";
        List<MigrationRunner.Step<File>> one = new ArrayList<>();
        one.add(f -> write(f, read(f).replace("\"v\":1", "\"v\":2")));
        r = MigrationRunner.run(data, m, g, one, 2);
        check(r.ok && m.schema == 2 && read(data).equals("{\"v\":2,\"notes\":[\"crash-test\"]}"),
                "crash recovery: rolled back, then migrated cleanly");

        // ---- snapshot failure: migration must not run at all
        write(data, "{\"v\":1}");
        m = new FakeMeta();
        g = new FileGuard(data, new File(tmp, "s4"));
        g.failSnapshot = true;
        final boolean[] ran = {false};
        List<MigrationRunner.Step<File>> never = new ArrayList<>();
        never.add(f -> ran[0] = true);
        r = MigrationRunner.run(data, m, g, never, 2);
        check(!r.ok && !ran[0] && m.schema == 1, "no snapshot -> no migration");

        // ---- older app opening newer data: untouched
        m = new FakeMeta();
        m.schema = 5;
        r = MigrationRunner.run(data, m, new FileGuard(data, new File(tmp, "s5")), one, 2);
        check(r.ok && !r.migrated() && m.schema == 5, "newer stored schema is never downgraded");

        // ---- mismatched table is refused
        r = MigrationRunner.run(data, new FakeMeta(), new FileGuard(data, new File(tmp, "s6")), one, 4);
        check(!r.ok, "step table must match schema number");

        System.out.println(failed == 0 ? "\nALL PASSED" : "\n" + failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
