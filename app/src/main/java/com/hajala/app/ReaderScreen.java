package com.hajala.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;

/**
 * Chapter reader. Pages are shown as one vertical strip (images are decoded lazily and released when far from the
 * screen). A tap shows or hides the top and bottom bars; the bottom bar has a progress slider and previous/next chapter.
 */
final class ReaderScreen {
    interface Host {
        void closeReader();
    }

    private static final int SEEK_MAX = 1000;

    /** Reading backgrounds (kept neutral on purpose: the reader never forces the violet identity). {page, text, placeholder} */
    private static final int[][] THEMES = {
            {0xFF000000, 0xFFE6E1E6, 0xFF141414},
            {0xFF1B1B1D, 0xFFD8D4DA, 0xFF26262A},
            {0xFFF2ECE0, 0xFF2A2520, 0xFFE4DDCE},
            {0xFFFFFFFF, 0xFF111111, 0xFFEEEEEE},
    };
    private static final String[] THEME_NAMES = {"أسود", "رمادي داكن", "ورقي", "أبيض"};
    private static final String PREFS = "arcanum_reader";
    private static final String PREF_THEME = "theme";

    private final Activity act;
    private final Store store;
    private final String workId;
    private final Host host;
    private final Store.Work work;
    private final List<Store.Chapter> chapters;
    private int index;

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout strip;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView chapterTitle;
    private TextView percentText;
    private SeekBar seek;
    private LinearLayout prevBtn;
    private LinearLayout nextBtn;

    private int theme;
    private boolean barsVisible = true;
    /** Bars that were just brought back are never hidden by scrolling for this long (they used to vanish at once). */
    private static final long BARS_MIN_VISIBLE_MS = 5000;
    private long barsShownAt = System.currentTimeMillis();
    private boolean fromSeek;
    private boolean userScrolled;
    private int restorePercent;
    private long lastSave;
    private int session; // bumped on every chapter load so stale async work is ignored

    private ImageView[] views = new ImageView[0];
    private Bitmap[] bitmaps = new Bitmap[0];
    private boolean[] loading = new boolean[0];

    ReaderScreen(Activity act, Store store, String workId, String chapterId, Host host) {
        this.act = act;
        this.store = store;
        this.workId = workId;
        this.host = host;
        this.work = store.work(workId);
        this.chapters = store.chapters(workId);
        int found = 0;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).id.equals(chapterId)) found = i;
        }
        this.index = found;
    }

    private int dp(float v) {
        return Ui.dp(act, v);
    }

    View build() {
        Context c = act;
        theme = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PREF_THEME, 0);
        if (theme < 0 || theme >= THEMES.length) theme = 0;
        root = new FrameLayout(c);
        root.setBackgroundColor(THEMES[theme][0]);
        root.setClickable(true);

        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(strip, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        final GestureDetector taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                setBars(!barsVisible);
                return true;
            }
        });
        scroll.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) userScrolled = true;
            taps.onTouchEvent(e);
            return false; // never consume: normal scrolling keeps working
        });
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> onScrolled(y, oy));

        buildTopBar();
        buildBottomBar();
        load();
        return root;
    }

    // ------------------------------------------------------------------ bars

    private void buildTopBar() {
        Context c = act;
        topBar = new LinearLayout(c);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(12), dp(14), dp(26));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xF2100B13, 0x00100B13}));

        FrameLayout back = Ui.iconButton(c, R.drawable.ic_back, "رجوع");
        back.setOnClickListener(v -> close());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView workTitle = Ui.text(c, work == null ? "" : work.title, 13, Ui.MUTED, false);
        workTitle.setMaxLines(1);
        workTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(workTitle);
        chapterTitle = Ui.text(c, "", 17, Ui.TEXT, true);
        chapterTitle.setMaxLines(1);
        chapterTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(chapterTitle);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, -2, 1f);
        tLp.setMarginStart(dp(12));
        topBar.addView(titles, tLp);

        TextView look = Ui.chip(c, "الخلفية", false);
        look.setOnClickListener(v -> showThemePicker());
        LinearLayout.LayoutParams kLp = new LinearLayout.LayoutParams(-2, dp(44));
        kLp.setMarginStart(dp(8));
        topBar.addView(look, kLp);

        TextView list = Ui.chip(c, "الفصول", false);
        list.setOnClickListener(v -> showChapterList());
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(-2, dp(44));
        lLp.setMarginStart(dp(8));
        topBar.addView(list, lLp);

        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    private void buildBottomBar() {
        Context c = act;
        bottomBar = new LinearLayout(c);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(16), dp(26), dp(16), dp(12));
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xF2100B13, 0x00100B13}));

        LinearLayout seekRow = new LinearLayout(c);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seek = new SeekBar(c);
        seek.setMax(SEEK_MAX);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.LAV));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.LAV));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.BORDER));
        seek.setContentDescription("التقدم في الفصل");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                barsShownAt = System.currentTimeMillis();
                int range = scrollRange();
                fromSeek = true;
                scroll.scrollTo(0, (int) ((long) range * p / SEEK_MAX));
                fromSeek = false;
                percentText.setText((p * 100 / SEEK_MAX) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                userScrolled = true;
                barsShownAt = System.currentTimeMillis();
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                barsShownAt = System.currentTimeMillis();
            }
        });
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, dp(40), 1f));
        percentText = Ui.centered(Ui.text(c, "0%", 13, Ui.LAV, true));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(dp(44), -2);
        pLp.setMarginStart(dp(8));
        seekRow.addView(percentText, pLp);
        bottomBar.addView(seekRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout nav = new LinearLayout(c);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        prevBtn = Ui.button(c, "الفصل السابق", 0, false);
        prevBtn.setOnClickListener(v -> go(-1));
        nav.addView(prevBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        nextBtn = Ui.button(c, "الفصل التالي", 0, true);
        nextBtn.setOnClickListener(v -> go(1));
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        nLp.setMarginStart(dp(10));
        nav.addView(nextBtn, nLp);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(-1, -2);
        navLp.topMargin = dp(6);
        bottomBar.addView(nav, navLp);

        root.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void setBars(boolean show) {
        if (show) barsShownAt = System.currentTimeMillis();
        if (show == barsVisible) return;
        barsVisible = show;
        animateBar(topBar, show, -dp(24));
        animateBar(bottomBar, show, dp(24));
    }

    private void animateBar(final View bar, boolean show, float offset) {
        bar.animate().cancel();
        if (show) {
            bar.setVisibility(View.VISIBLE);
            bar.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            bar.animate().alpha(0f).translationY(offset).setDuration(160)
                    .withEndAction(() -> {
                        if (!barsVisible) bar.setVisibility(View.GONE);
                    }).start();
        }
    }

    // ------------------------------------------------------------------ chapter loading

    private void load() {
        session++;
        final int mySession = session;
        releaseAll();
        strip.removeAllViews();
        scroll.scrollTo(0, 0);
        userScrolled = false;

        if (chapters.isEmpty()) {
            chapterTitle.setText("لا توجد فصول");
            TextView none = Ui.centered(Ui.text(act, "لا توجد فصول لقراءتها", 16, Ui.MUTED, false));
            none.setPadding(dp(20), dp(160), dp(20), dp(20));
            strip.addView(none, new LinearLayout.LayoutParams(-1, -2));
            prevBtn.setAlpha(0.4f);
            nextBtn.setAlpha(0.4f);
            return;
        }

        final Store.Chapter ch = chapters.get(index);
        chapterTitle.setText(ch.title.isEmpty() ? ch.label() : ch.label() + " • " + ch.title);
        prevBtn.setAlpha(index > 0 ? 1f : 0.4f);
        nextBtn.setAlpha(index < chapters.size() - 1 ? 1f : 0.4f);
        restorePercent = store.progress(ch.id);
        if (restorePercent >= 98) restorePercent = 0; // finished chapters start over
        seek.setProgress(0);
        percentText.setText("0%");
        store.saveProgress(workId, ch.id, store.progress(ch.id)); // remembers "last read" immediately

        if (ch.isText()) {
            loadText(ch);
        } else {
            loadPages(ch, mySession);
        }
    }

    private void loadText(Store.Chapter ch) {
        TextView t = new TextView(act);
        t.setText(ch.text);
        t.setTextSize(18);
        t.setTextColor(THEMES[theme][1]);
        t.setTypeface(Ui.bodyFace(act, ch.text, false));
        t.setLineSpacing(dp(7), 1f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPadding(dp(22), dp(96), dp(22), dp(150));
        strip.addView(t, new LinearLayout.LayoutParams(-1, -2));
        views = new ImageView[0];
        applyRestoreLater();
    }

    private void loadPages(Store.Chapter ch, final int mySession) {
        final int n = ch.pages.size();
        final int width = Math.max(1, act.getResources().getDisplayMetrics().widthPixels);
        views = new ImageView[n];
        bitmaps = new Bitmap[n];
        loading = new boolean[n];

        // top / bottom breathing room so the first and last pages are not hidden under the bars
        strip.addView(spacer(dp(70)));
        for (int i = 0; i < n; i++) {
            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setBackgroundColor(THEMES[theme][2]);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            views[i] = iv;
            strip.addView(iv, new LinearLayout.LayoutParams(-1, width * 7 / 5)); // placeholder until real size is known
        }
        strip.addView(spacer(dp(150)));

        // read the real image sizes off the UI thread, then size every page once
        final List<String> pageList = ch.pages;
        Ui.IO.execute(() -> {
            final int[] heights = new int[n];
            for (int i = 0; i < n; i++) {
                int[] b = Ui.bounds(act, pageList.get(i));
                heights[i] = b == null ? width * 7 / 5 : Math.max(1, (int) ((long) width * b[1] / b[0]));
            }
            root.post(() -> {
                if (mySession != session) return;
                for (int i = 0; i < n; i++) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) views[i].getLayoutParams();
                    lp.height = heights[i];
                    views[i].setLayoutParams(lp);
                }
                scroll.post(() -> {
                    if (mySession != session) return;
                    applyRestore();
                    updateVisible();
                });
            });
        });
    }

    private View spacer(int h) {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        return v;
    }

    private void applyRestoreLater() {
        scroll.post(this::applyRestore);
    }

    private void applyRestore() {
        if (restorePercent > 0 && !userScrolled) {
            scroll.scrollTo(0, (int) ((long) scrollRange() * restorePercent / 100));
        }
        updateProgressUi();
    }

    // ------------------------------------------------------------------ lazy image loading

    private void updateVisible() {
        int n = views.length;
        if (n == 0) return;
        int h = scroll.getHeight();
        int y = scroll.getScrollY();
        int loadFrom = y - h;
        int loadTo = y + 2 * h;
        int dropFrom = y - 3 * h;
        int dropTo = y + 4 * h;
        final int width = Math.max(1, act.getResources().getDisplayMetrics().widthPixels);
        final List<String> pageList = chapters.get(index).pages;
        final int mySession = session;
        for (int i = 0; i < n; i++) {
            ImageView iv = views[i];
            int top = iv.getTop();
            int bottom = iv.getBottom();
            if (bottom == top) continue; // not laid out yet
            boolean near = bottom >= loadFrom && top <= loadTo;
            boolean far = bottom < dropFrom || top > dropTo;
            if (near && bitmaps[i] == null && !loading[i]) {
                loading[i] = true;
                final int idx = i;
                final String uri = pageList.get(i);
                Ui.IO.execute(() -> {
                    final Bitmap bmp = Ui.decodePage(act, uri, width);
                    iv.post(() -> {
                        loading[idx] = false;
                        if (mySession != session || bmp == null) return;
                        bitmaps[idx] = bmp;
                        iv.setImageBitmap(bmp);
                    });
                });
            } else if (far && bitmaps[i] != null) {
                iv.setImageDrawable(null);
                bitmaps[i].recycle();
                bitmaps[i] = null;
            }
        }
    }

    private void releaseAll() {
        for (int i = 0; i < bitmaps.length; i++) {
            if (bitmaps[i] != null) {
                if (i < views.length) views[i].setImageDrawable(null);
                bitmaps[i].recycle();
                bitmaps[i] = null;
            }
        }
        views = new ImageView[0];
        bitmaps = new Bitmap[0];
        loading = new boolean[0];
    }

    // ------------------------------------------------------------------ scrolling / progress

    private int scrollRange() {
        if (strip.getChildCount() == 0) return 0;
        return Math.max(0, strip.getHeight() - scroll.getHeight());
    }

    private int currentPercent() {
        int range = scrollRange();
        if (range <= 0) return 0;
        return Math.min(100, (int) ((long) scroll.getScrollY() * 100 / range));
    }

    private void updateProgressUi() {
        int range = scrollRange();
        int p = range <= 0 ? 0 : (int) ((long) scroll.getScrollY() * SEEK_MAX / range);
        p = Math.max(0, Math.min(SEEK_MAX, p));
        if (!seek.isPressed()) seek.setProgress(p);
        percentText.setText((p * 100 / SEEK_MAX) + "%");
    }

    private void onScrolled(int y, int oldY) {
        updateProgressUi();
        updateVisible();
        if (!fromSeek && userScrolled) {
            int dy = y - oldY;
            boolean settled = System.currentTimeMillis() - barsShownAt >= BARS_MIN_VISIBLE_MS;
            if (dy > dp(10) && barsVisible && !seek.isPressed() && settled) setBars(false);
            if (dy < -dp(40) && !barsVisible) setBars(true);
            if (scrollRange() > 0 && y >= scrollRange() - dp(4)) setBars(true); // end of chapter: show next-chapter button
        }
        long now = System.currentTimeMillis();
        if (now - lastSave > 700) {
            lastSave = now;
            saveNow();
        }
    }

    private void saveNow() {
        if (chapters.isEmpty() || index >= chapters.size() || strip == null || strip.getChildCount() == 0) return;
        Store.Chapter ch = chapters.get(index);
        int p = currentPercent();
        // do not overwrite saved progress with 0 while the pages are still being sized
        if (p == 0 && !userScrolled) return;
        store.saveProgress(workId, ch.id, p);
    }

    // ------------------------------------------------------------------ navigation

    private void go(int delta) {
        int target = index + delta;
        if (target < 0) {
            Ui.toast(act, "هذا أول فصل");
            return;
        }
        if (target >= chapters.size()) {
            Ui.toast(act, "هذا آخر فصل");
            return;
        }
        saveNow(); // progress of the chapter we are leaving
        index = target;
        load();
        setBars(true);
    }

    private void showChapterList() {
        if (chapters.isEmpty()) return;
        String[] labels = new String[chapters.size()];
        for (int i = 0; i < labels.length; i++) {
            Store.Chapter ch = chapters.get(i);
            labels[i] = ch.title.isEmpty() ? ch.label() : ch.label() + " • " + ch.title;
        }
        AlertDialog.Builder b = Ui.dialog(act)
                .setTitle("الفصول")
                .setItems(labels, (d, which) -> {
                    if (which != index) {
                        saveNow();
                        index = which;
                        load();
                    }
                })
                .setNegativeButton("إغلاق", null);
        Ui.show(b);
    }

    private void showThemePicker() {
        AlertDialog.Builder b = Ui.dialog(act)
                .setTitle("لون القراءة")
                .setSingleChoiceItems(THEME_NAMES, theme, (d, which) -> {
                    applyTheme(which);
                    d.dismiss();
                })
                .setNegativeButton("إغلاق", null);
        Ui.show(b);
    }

    /** Changes the reading background (saved on the device) without reloading the chapter. */
    private void applyTheme(int which) {
        if (which < 0 || which >= THEMES.length || which == theme) return;
        theme = which;
        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(PREF_THEME, theme).apply();
        root.setBackgroundColor(THEMES[theme][0]);
        for (int i = 0; i < strip.getChildCount(); i++) {
            View v = strip.getChildAt(i);
            if (v instanceof ImageView) {
                v.setBackgroundColor(THEMES[theme][2]);
            } else if (v instanceof TextView) {
                ((TextView) v).setTextColor(THEMES[theme][1]);
            }
        }
    }

    private void close() {
        host.closeReader();
    }

    /** Called by the activity when the reader is removed: saves progress and frees the page bitmaps. */
    void onClosed() {
        saveNow();
        session++;
        releaseAll();
    }
}
