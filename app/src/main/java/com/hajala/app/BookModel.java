package com.hajala.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pages and blocks of one notebook. This class has no Android dependencies, so the logic that matters most
 * (numbering, reordering, deleting, saving and loading) can be tested on a plain JVM.
 *
 * Question numbers (س1, س2…) and answer numbers are never stored: they are computed from the order of the
 * blocks every time, so they are always sequential and correct after any delete or move.
 */
final class BookModel {
    static final String T_TEXT = "text";
    static final String T_HEAD = "head";
    static final String T_KEY = "key";
    static final String T_QA = "qa";
    static final String T_TASK = "task";
    static final String T_IMAGE = "image";

    static final int VERSION = 1;
    private static final Random RND = new Random();

    static final class Block {
        String id = newId();
        String type = T_TEXT;
        String text = "";                       // text / heading / question / task / image caption
        String itemId = "";                     // image blocks: StudioStore.Item id
        boolean done;                           // task blocks
        final List<String> lines = new ArrayList<>(); // qa blocks: the answer lines (at least one)

        boolean hasContent() {
            if (!text.trim().isEmpty() || !itemId.isEmpty()) return true;
            for (String l : lines) if (!l.trim().isEmpty()) return true;
            return false;
        }
    }

    static final class Page {
        String id = newId();
        final List<Block> blocks = new ArrayList<>();
    }

    final List<Page> pages = new ArrayList<>();
    String type = StudioStore.BOOK_FREE;
    boolean quiz;                               // quiz mode (answers hidden) for question notebooks

    BookModel(String type) {
        this.type = type == null ? StudioStore.BOOK_FREE : type;
    }

    static String newId() {
        return Long.toString(System.nanoTime() & 0xFFFFFFFFL, 36) + Integer.toString(RND.nextInt(46656), 36);
    }

    // ------------------------------------------------------------------ load / save

    /** Parses saved data. Empty or damaged data gives a fresh notebook with one empty page. */
    static BookModel parse(String json, String type) {
        BookModel m = new BookModel(type);
        if (json != null && !json.trim().isEmpty()) {
            try {
                JSONObject o = new JSONObject(json);
                m.quiz = o.optBoolean("quiz", false);
                JSONArray pa = o.optJSONArray("pages");
                if (pa != null) {
                    for (int i = 0; i < pa.length(); i++) {
                        JSONObject pj = pa.optJSONObject(i);
                        if (pj == null) continue;
                        Page p = new Page();
                        String pid = pj.optString("id", "");
                        if (!pid.isEmpty()) p.id = pid;
                        JSONArray ba = pj.optJSONArray("blocks");
                        if (ba != null) {
                            for (int k = 0; k < ba.length(); k++) {
                                JSONObject bj = ba.optJSONObject(k);
                                if (bj == null) continue;
                                Block b = new Block();
                                String bid = bj.optString("id", "");
                                if (!bid.isEmpty()) b.id = bid;
                                b.type = normBlockType(bj.optString("t", T_TEXT));
                                b.text = bj.optString("x", "");
                                b.itemId = bj.optString("i", "");
                                b.done = bj.optBoolean("d", false);
                                JSONArray la = bj.optJSONArray("a");
                                if (la != null) for (int n = 0; n < la.length(); n++) b.lines.add(la.optString(n, ""));
                                if (T_QA.equals(b.type) && b.lines.isEmpty()) b.lines.add("");
                                p.blocks.add(b);
                            }
                        }
                        m.pages.add(p);
                    }
                }
            } catch (Exception e) {
                m.pages.clear();
                m.quiz = false;
            }
        }
        if (m.pages.isEmpty()) m.pages.add(new Page());
        return m;
    }

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", VERSION);
            o.put("quiz", quiz);
            JSONArray pa = new JSONArray();
            for (Page p : pages) {
                JSONObject pj = new JSONObject();
                pj.put("id", p.id);
                JSONArray ba = new JSONArray();
                for (Block b : p.blocks) {
                    JSONObject bj = new JSONObject();
                    bj.put("id", b.id);
                    bj.put("t", b.type);
                    if (!b.text.isEmpty()) bj.put("x", b.text);
                    if (!b.itemId.isEmpty()) bj.put("i", b.itemId);
                    if (b.done) bj.put("d", true);
                    if (T_QA.equals(b.type)) {
                        JSONArray la = new JSONArray();
                        for (String l : b.lines) la.put(l);
                        bj.put("a", la);
                    }
                    ba.put(bj);
                }
                pj.put("blocks", ba);
                pa.put(pj);
            }
            o.put("pages", pa);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String normBlockType(String t) {
        if (T_HEAD.equals(t) || T_KEY.equals(t) || T_QA.equals(t) || T_TASK.equals(t) || T_IMAGE.equals(t)) return t;
        return T_TEXT;
    }

    // ------------------------------------------------------------------ pages

    Page page(int index) {
        if (pages.isEmpty()) pages.add(new Page());
        if (index < 0) index = 0;
        if (index >= pages.size()) index = pages.size() - 1;
        return pages.get(index);
    }

    /** Inserts an empty page after [index] and returns its position. */
    int addPageAfter(int index) {
        int at = Math.max(0, Math.min(pages.size(), index + 1));
        pages.add(at, new Page());
        return at;
    }

    /**
     * Removes a page. The notebook always keeps at least one page: deleting the only page empties it instead.
     * Returns the ids of image items that belonged to the removed page so the caller can delete the files.
     */
    List<String> removePage(int index) {
        List<String> images = new ArrayList<>();
        if (index < 0 || index >= pages.size()) return images;
        for (Block b : pages.get(index).blocks) if (!b.itemId.isEmpty()) images.add(b.itemId);
        if (pages.size() == 1) pages.get(0).blocks.clear();
        else pages.remove(index);
        return images;
    }

    // ------------------------------------------------------------------ blocks

    static Block newBlock(String type) {
        Block b = new Block();
        b.type = normBlockType(type);
        if (T_QA.equals(b.type)) b.lines.add("");
        return b;
    }

    /** Question number of [b] (1-based), counted over the whole notebook in page order; 0 if not a question. */
    int questionNumber(Block b) {
        if (!T_QA.equals(b.type)) return 0;
        int n = 0;
        for (Page p : pages) {
            for (Block x : p.blocks) {
                if (!T_QA.equals(x.type)) continue;
                n++;
                if (x == b) return n;
            }
        }
        return 0;
    }

    int questionCount() {
        int n = 0;
        for (Page p : pages) for (Block x : p.blocks) if (T_QA.equals(x.type)) n++;
        return n;
    }

    /** Moves a block one step up (-1) or down (+1) inside its page. Returns true when it moved. */
    boolean move(Page p, Block b, int delta) {
        int i = p.blocks.indexOf(b);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= p.blocks.size()) return false;
        p.blocks.remove(i);
        p.blocks.add(j, b);
        return true;
    }

    /** Adds one more answer line to a question; the next question is not affected. */
    static void addAnswerLine(Block b) {
        b.lines.add("");
    }

    /** Removes an answer line but always keeps one (possibly empty) line. */
    static boolean removeAnswerLine(Block b, int index) {
        if (b.lines.size() <= 1 || index < 0 || index >= b.lines.size()) return false;
        b.lines.remove(index);
        return true;
    }

    /** Joined answer text (used in the quiz view and for emptiness checks). */
    static String answerText(Block b) {
        StringBuilder sb = new StringBuilder();
        for (String l : b.lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }

    static boolean hasAnswer(Block b) {
        for (String l : b.lines) if (!l.trim().isEmpty()) return true;
        return false;
    }

    int taskCount() {
        int n = 0;
        for (Page p : pages) for (Block x : p.blocks) if (T_TASK.equals(x.type)) n++;
        return n;
    }

    int taskDone() {
        int n = 0;
        for (Page p : pages) for (Block x : p.blocks) if (T_TASK.equals(x.type) && x.done) n++;
        return n;
    }
}
