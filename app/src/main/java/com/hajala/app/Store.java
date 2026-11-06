package com.hajala.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local persistence for MangaVerse works and the user's custom categories (SharedPreferences + JSON). */
final class Store {
    static final String MANGA = "manga";
    static final String MANHWA = "manhwa";
    static final String ONESHOT = "oneshot";
    static final String PUBLISHED = "published";
    static final String DRAFT = "draft";

    private static final List<String> BUILT_IN_GENRES =
            Arrays.asList("أكشن", "رومانسي", "فانتازيا", "دراما", "كوميديا");

    static final class Work {
        String id = "";
        String title = "";
        String type = MANGA;
        String desc = "";
        String status = DRAFT;
        String cover = "";
        long createdAt;
        final List<String> genres = new ArrayList<>();
    }

    static final class Chapter {
        String id = "";
        String workId = "";
        double number;
        String title = "";
        String text = "";
        long createdAt;
        final List<String> pages = new ArrayList<>();

        String label() {
            return "الفصل " + fmt(number);
        }

        boolean isText() {
            return pages.isEmpty() && !text.isEmpty();
        }
    }

    static String fmt(double n) {
        return n == Math.floor(n) ? String.valueOf((long) n) : String.valueOf(n);
    }

    private static final String KEY_CHAPTERS = "chapters";
    private static final String KEY_WORKS = "works";
    private static final String KEY_GENRES = "custom_genres";

    private final SharedPreferences sp;

    Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("mangaverse", Context.MODE_PRIVATE);
    }

    static String typeLabel(String type) {
        if (MANHWA.equals(type)) return "مانهوا";
        if (ONESHOT.equals(type)) return "ونشوت";
        return "مانغا";
    }

    static String statusLabel(String status) {
        return PUBLISHED.equals(status) ? "منشور" : "مسودة";
    }

    // ---------------------------------------------------------------- works

    List<Work> works() {
        List<Work> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_WORKS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Work w = new Work();
                w.id = o.optString("id");
                w.title = o.optString("title");
                w.type = o.optString("type", MANGA);
                w.desc = o.optString("desc");
                w.status = o.optString("status", DRAFT);
                w.cover = o.optString("cover");
                w.createdAt = o.optLong("createdAt");
                JSONArray g = o.optJSONArray("genres");
                if (g != null) {
                    for (int j = 0; j < g.length(); j++) w.genres.add(g.optString(j));
                }
                out.add(w);
            }
        } catch (JSONException ignored) {
            // corrupted data: start from an empty list
        }
        return out;
    }

    private void saveWorks(List<Work> list) {
        JSONArray a = new JSONArray();
        for (Work w : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", w.id);
                o.put("title", w.title);
                o.put("type", w.type);
                o.put("desc", w.desc);
                o.put("status", w.status);
                o.put("cover", w.cover);
                o.put("createdAt", w.createdAt);
                JSONArray g = new JSONArray();
                for (String s : w.genres) g.put(s);
                o.put("genres", g);
                a.put(o);
            } catch (JSONException ignored) {
                // skip a work that cannot be serialised
            }
        }
        sp.edit().putString(KEY_WORKS, a.toString()).apply();
    }

    void addWork(Work w) {
        List<Work> l = works();
        l.add(0, w);
        saveWorks(l);
    }

    Work work(String id) {
        for (Work w : works()) if (w.id.equals(id)) return w;
        return null;
    }

    void deleteWork(String id) {
        Work old = work(id);
        if (old != null) Ui.deleteLocal(old.cover);
        List<Chapter> keep = new ArrayList<>();
        for (Chapter ch : allChapters()) {
            if (ch.workId.equals(id)) {
                for (String p : ch.pages) Ui.deleteLocal(p);
            } else {
                keep.add(ch);
            }
        }
        saveChapters(keep);
        List<Work> l = works();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(id)) {
                l.remove(i);
                break;
            }
        }
        saveWorks(l);
    }

    void setStatus(String id, String status) {
        List<Work> l = works();
        for (Work w : l) {
            if (w.id.equals(id)) w.status = status;
        }
        saveWorks(l);
    }

    // ---------------------------------------------------------------- chapters

    private List<Chapter> allChapters() {
        List<Chapter> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_CHAPTERS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Chapter c = new Chapter();
                c.id = o.optString("id");
                c.workId = o.optString("workId");
                c.number = o.optDouble("number", 0);
                c.title = o.optString("title");
                c.text = o.optString("text");
                c.createdAt = o.optLong("createdAt");
                JSONArray p = o.optJSONArray("pages");
                if (p != null) {
                    for (int j = 0; j < p.length(); j++) c.pages.add(p.optString(j));
                }
                out.add(c);
            }
        } catch (JSONException ignored) {
            // corrupted data: start from an empty list
        }
        return out;
    }

    private void saveChapters(List<Chapter> list) {
        JSONArray a = new JSONArray();
        for (Chapter c : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("workId", c.workId);
                o.put("number", c.number);
                o.put("title", c.title);
                o.put("text", c.text);
                o.put("createdAt", c.createdAt);
                JSONArray p = new JSONArray();
                for (String s : c.pages) p.put(s);
                o.put("pages", p);
                a.put(o);
            } catch (JSONException ignored) {
                // skip a chapter that cannot be serialised
            }
        }
        sp.edit().putString(KEY_CHAPTERS, a.toString()).apply();
    }

    /** Chapters of one work, ordered by chapter number (ascending). */
    List<Chapter> chapters(String workId) {
        List<Chapter> out = new ArrayList<>();
        for (Chapter c : allChapters()) if (c.workId.equals(workId)) out.add(c);
        Collections.sort(out, (x, y) -> Double.compare(x.number, y.number));
        return out;
    }

    Chapter chapter(String id) {
        for (Chapter c : allChapters()) if (c.id.equals(id)) return c;
        return null;
    }

    int chapterCount(String workId) {
        int n = 0;
        for (Chapter c : allChapters()) if (c.workId.equals(workId)) n++;
        return n;
    }

    double nextChapterNumber(String workId) {
        double max = 0;
        for (Chapter c : allChapters()) if (c.workId.equals(workId)) max = Math.max(max, c.number);
        return Math.floor(max) + 1;
    }

    boolean hasChapterNumber(String workId, double number) {
        for (Chapter c : allChapters()) {
            if (c.workId.equals(workId) && Double.compare(c.number, number) == 0) return true;
        }
        return false;
    }

    void addChapter(Chapter c) {
        List<Chapter> l = allChapters();
        l.add(c);
        saveChapters(l);
    }

    void deleteChapter(String id) {
        List<Chapter> l = allChapters();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(id)) {
                for (String p : l.get(i).pages) Ui.deleteLocal(p);
                l.remove(i);
                break;
            }
        }
        saveChapters(l);
        sp.edit().remove("prog_" + id).apply();
    }

    // reading progress (0..100 per chapter, plus the last chapter opened for each work)

    void saveProgress(String workId, String chapterId, int percent) {
        sp.edit().putInt("prog_" + chapterId, Math.max(0, Math.min(100, percent)))
                .putString("last_" + workId, chapterId).apply();
    }

    int progress(String chapterId) {
        return sp.getInt("prog_" + chapterId, 0);
    }

    String lastRead(String workId) {
        return sp.getString("last_" + workId, "");
    }

    // ---------------------------------------------------------------- categories

    static List<String> builtInGenres() {
        return BUILT_IN_GENRES;
    }

    List<String> customGenres() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_GENRES, "[]"));
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i);
                if (!s.isEmpty()) out.add(s);
            }
        } catch (JSONException ignored) {
            // corrupted data: no custom categories
        }
        return out;
    }

    /** True when [name] (ignoring case and extra spaces) already exists among built-in or custom categories. */
    boolean hasGenre(String name) {
        String key = normalize(name);
        for (String s : BUILT_IN_GENRES) if (normalize(s).equals(key)) return true;
        for (String s : customGenres()) if (normalize(s).equals(key)) return true;
        return false;
    }

    /** Adds a custom category. Returns false if it is empty or already exists. */
    boolean addGenre(String name) {
        String clean = name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty() || hasGenre(clean)) return false;
        List<String> l = customGenres();
        l.add(clean);
        saveGenres(l);
        return true;
    }

    void removeGenre(String name) {
        List<String> l = customGenres();
        l.remove(name);
        saveGenres(l);
    }

    private void saveGenres(List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        sp.edit().putString(KEY_GENRES, a.toString()).apply();
    }

    private static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
