package com.hajala.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.ArrayList;
import java.util.List;

/** Native Android UI for ARCANUM MangaVerse. This intentionally does not load the website in WebView. */
public class NativeHomeActivity extends Activity {
    private static final int PICK_COVER = 7102;
    private static final int PICK_PAGES = 7103;

    private static final String HOME = "الرئيسية";
    private static final String MANGA = "مانغا";
    private static final String STUDIO = "الاستوديو";
    private static final String FRIENDS = "الأصدقاء";
    private static final String PROFILE = "ملفي";
    private static final String[] TABS = {HOME, MANGA, STUDIO, FRIENDS, PROFILE};
    private static final int[] TAB_ICONS = {
            R.drawable.ic_home, R.drawable.ic_book, R.drawable.ic_add_circle,
            R.drawable.ic_people, R.drawable.ic_person};

    private FrameLayout container;
    private LinearLayout content;
    private ScrollView scroll;
    private final ImageView[] tabIcons = new ImageView[TABS.length];
    private final TextView[] tabLabels = new TextView[TABS.length];
    private final View[] tabPills = new View[TABS.length];
    private View splash;
    private String active = HOME;
    private static final int PICK_STUDIO = 7110;
    private static final int CREATE_BACKUP = 7120;
    private StudioContext.UriCallback backupCallback;

    private Store store;
    private StudioStore studioStore;
    private StudioContext studioCtx;
    private StudioScreen studio;
    private StudioContext.UriCallback studioCallback;
    private MangaVerseScreen mangaVerse;
    private PublishScreen publish;
    private AddChapterScreen addChapter;
    private WorkDetailScreen detail;
    private ReaderScreen reader;
    private Object backCallback;
    private java.util.function.BooleanSupplier backInterceptor;

    /** Full-screen pages stacked above the tabs (work page, publish, add chapter, reader). */
    private static final class Overlay {
        final View view;
        final Runnable onPop;
        final Runnable onRevealed;

        Overlay(View view, Runnable onPop, Runnable onRevealed) {
            this.view = view;
            this.onPop = onPop;
            this.onRevealed = onRevealed;
        }
    }

    private final ArrayList<Overlay> overlays = new ArrayList<>();

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.BG);
        w.getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        // Data safety first: run pending data migrations (snapshot + rollback on failure) before any store reads data.
        final MigrationRunner.Result migration = AppMigrations.run(this);
        final int updatedFrom = AppUpdater.onStartup(this);
        store = new Store(this);
        studioStore = new StudioStore(this);
        studioCtx = new StudioContext(this, studioStore, new StudioContext.Host() {
            @Override
            public void push(View v, Runnable onPop, Runnable onRevealed) {
                pushOverlay(v, onPop, onRevealed);
            }

            @Override
            public void pop() {
                popOverlay();
            }

            @Override
            public void setBackInterceptor(java.util.function.BooleanSupplier interceptor) {
                backInterceptor = interceptor;
            }

            @Override
            public void pickFiles(String[] mimeTypes, boolean multiple, StudioContext.UriCallback cb) {
                NativeHomeActivity.this.pickStudioFiles(mimeTypes, multiple, cb);
            }
        });
        studio = new StudioScreen(studioCtx);
        mangaVerse = new MangaVerseScreen(this, store, new MangaVerseScreen.Host() {
            @Override
            public void openPublish() {
                NativeHomeActivity.this.openPublish();
            }

            @Override
            public void openWork(String workId) {
                NativeHomeActivity.this.openWork(workId);
            }
        });
        buildShell();
        showPage(HOME);
        if (state == null) showSplash();
        afterStart(migration, updatedFrom);
    }

    // ------------------------------------------------------------------ updates

    /** Post-launch notices: "updated", a failed migration, and a quiet check for a new version. */
    private void afterStart(final MigrationRunner.Result migration, final int updatedFrom) {
        container.postDelayed(() -> {
            if (isFinishing()) return;
            if (migration != null && !migration.ok) {
                Ui.show(Ui.dialog(this).setTitle("تعذّر ترحيل البيانات")
                        .setMessage("تمت إعادة بياناتك كما كانت ولم يُفقد شيء. يمكنك تصدير نسخة احتياطية من شاشة التحديثات.")
                        .setPositiveButton("حسناً", null));
            } else if (updatedFrom > 0) {
                Ui.toast(this, "تم التحديث إلى " + AppUpdater.currentName(this) + " ✓ بياناتك كما هي");
            }
            if (AppUpdater.autoCheckDue(this)) {
                AppUpdater.check(this, (info, error) -> {
                    if (error == null && AppUpdater.isNewer(this, info) && !AppUpdater.wasDismissed(this, info)) {
                        showUpdateNotice(info);
                    }
                });
            } else {
                AppUpdater.Info known = AppUpdater.lastKnown(this);
                if (known != null && AppUpdater.isNewer(this, known) && !AppUpdater.wasDismissed(this, known)) {
                    showUpdateNotice(known);
                }
            }
        }, 1800);
    }

    private void showUpdateNotice(final AppUpdater.Info info) {
        if (isFinishing()) return;
        String msg = "الإصدار الحالي: " + AppUpdater.currentName(this) + "\nالإصدار الجديد: " + info.versionName;
        if (!info.notes.isEmpty()) msg += "\n\nما الجديد:\n" + info.notes;
        Ui.show(Ui.dialog(this).setTitle("تحديث جديد متوفر").setMessage(msg)
                .setPositiveButton("تحديث الآن", (d, w) -> openUpdates())
                .setNegativeButton("لاحقاً", (d, w) -> AppUpdater.dismiss(this, info)));
    }

    private void openUpdates() {
        final UpdateScreen screen = new UpdateScreen(this, new UpdateScreen.Host() {
            @Override
            public void close() {
                popOverlay();
            }

            @Override
            public void pickSaveLocation(String fileName, StudioContext.UriCallback cb) {
                backupCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_TITLE, fileName);
                    startActivityForResult(i, CREATE_BACKUP);
                } catch (Exception e) {
                    backupCallback = null;
                    Ui.toast(NativeHomeActivity.this, "تعذّر فتح منتقي الملفات على هذا الهاتف");
                }
            }

            @Override
            public void pickBackupFile(StudioContext.UriCallback cb) {
                pickStudioFiles(new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"},
                        false, cb);
            }

            @Override
            public void flushPending() {
                if (studio != null) studio.flush();
            }

            @Override
            public void restart() {
                recreate();
            }
        });
        pushOverlay(screen.build(), null, null);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------------ shell

    private void buildShell() {
        container = new FrameLayout(this);
        container.setBackgroundColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= 35) {
            // From Android 15 the window is edge-to-edge: keep content clear of the bars and the keyboard.
            container.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        }

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Ui.BG);

        // brand bar: crescent · ARCANUM · feather, over a hairline
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(dp(16), dp(14), dp(16), dp(12));
        brand.addView(Ui.icon(this, R.drawable.ic_crescent, Ui.ACCENT, 18), new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout.LayoutParams wmLp = new LinearLayout.LayoutParams(-2, -2);
        wmLp.setMargins(dp(12), 0, dp(12), 0);
        brand.addView(Ui.wordmarkText(this, 24, Ui.LAV), wmLp);
        brand.addView(Ui.icon(this, R.drawable.ic_feather, Ui.ACCENT, 18), new LinearLayout.LayoutParams(dp(18), dp(18)));
        shell.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(this);
        line.setBackgroundColor(Ui.BORDER);
        shell.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        // page content
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // bottom navigation
        View navLine = new View(this);
        navLine.setBackgroundColor(Ui.BORDER);
        shell.addView(navLine, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(Ui.SURFACE);
        nav.setPadding(dp(4), dp(6), dp(4), dp(6));
        for (int i = 0; i < TABS.length; i++) {
            final String tab = TABS[i];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setContentDescription(tab);
            item.setBackground(Ui.ripple(this, null, 16));

            // icon sits on a soft violet pill that fades in for the active tab
            FrameLayout iconBox = new FrameLayout(this);
            View pill = new View(this);
            pill.setBackground(Ui.rounded(this, Ui.CARD, 0x6675416F, 1, 14));
            pill.setAlpha(0f);
            iconBox.addView(pill, new FrameLayout.LayoutParams(dp(52), dp(30), Gravity.CENTER));
            ImageView ic = Ui.icon(this, TAB_ICONS[i], Ui.MUTED, 24);
            iconBox.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            item.addView(iconBox, new LinearLayout.LayoutParams(dp(52), dp(30)));

            TextView lb = Ui.centered(Ui.text(this, tab, 11, Ui.MUTED, false));
            LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(-2, -2);
            lbLp.topMargin = dp(3);
            item.addView(lb, lbLp);
            item.setOnClickListener(v -> showPage(tab));
            Ui.asButton(item);
            Ui.press(item);
            tabIcons[i] = ic;
            tabLabels[i] = lb;
            tabPills[i] = pill;
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1f));
        }
        shell.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        container.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);
    }

    private void updateTabs() {
        for (int i = 0; i < TABS.length; i++) {
            boolean on = TABS[i].equals(active);
            int color = on ? Ui.LAV : Ui.MUTED;
            tabIcons[i].setColorFilter(color, PorterDuff.Mode.SRC_IN);
            tabLabels[i].setTextColor(color);
            tabLabels[i].setTypeface(Ui.arabic(this, on));
            tabPills[i].animate().cancel();
            tabPills[i].animate().alpha(on ? 1f : 0f).setDuration(180).start();
            tabIcons[i].animate().cancel();
            tabIcons[i].animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).setDuration(180).start();
        }
    }

    // ------------------------------------------------------------------ pages

    private void add(View v) {
        content.addView(v, new LinearLayout.LayoutParams(-1, -2));
    }

    private int tabIndex(String page) {
        for (int i = 0; i < TABS.length; i++) if (TABS[i].equals(page)) return i;
        return -1;
    }

    private void showPage(String page) {
        int from = tabIndex(active);
        int to = tabIndex(page);
        active = page;
        content.removeAllViews();
        updateTabs();
        scroll.scrollTo(0, 0);
        // soft page transition: fade in with a small rise, sliding in from the side the new tab lies on
        // (the tab bar is right-to-left, so a tab further along the bar comes in from the left)
        float slide = (from < 0 || from == to) ? 0f : (to > from ? -dp(28) : dp(28));
        content.animate().cancel();
        content.setAlpha(0f);
        content.setTranslationX(slide);
        content.setTranslationY(slide == 0f ? dp(12) : 0f);
        content.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();

        if (MANGA.equals(page)) {
            TextView heading = Ui.displayText(this, "MangaVerse", 28, Ui.LAV);
            heading.setPadding(0, dp(6), 0, dp(10));
            add(heading);
            LinearLayout.LayoutParams orn = new LinearLayout.LayoutParams(-1, -2);
            orn.bottomMargin = dp(10);
            content.addView(Ui.ornament(this), orn);
        } else {
            TextView heading = Ui.text(this, page, 25, Ui.LAV, true);
            heading.setPadding(0, dp(6), 0, dp(6));
            add(heading);
            LinearLayout.LayoutParams orn = new LinearLayout.LayoutParams(-1, -2);
            orn.bottomMargin = dp(12);
            content.addView(Ui.ornament(this), orn);
        }

        if (HOME.equals(page)) {
            content.addView(heroCard());
            addCard("المانغا والمانهوا", "تابع القراءة واستكشف مكتبتك", "فتح المكتبة", () -> showPage(MANGA));
            addCard("الاستوديو", "اختر صورك وفيديوهاتك من الهاتف", "فتح الاستوديو", () -> showPage(STUDIO));
            addCard("الفيديوهات", "سيتم ربط المشغل الأصلي في المرحلة التالية", "اختيار فيديو",
                    () -> pickMedia("video/*"));
            addCard("تيك جيل", "من دون نظام البوتات", "فتح القسم",
                    () -> Ui.toast(this, "تم تعطيل نظام البوتات في هذه الواجهة الأولية"));
        } else if (STUDIO.equals(page)) {
            add(studio.build());
        } else if (MANGA.equals(page)) {
            add(mangaVerse.build());
        } else if (FRIENDS.equals(page)) {
            LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(-1, -2);
            st.bottomMargin = dp(12);
            content.addView(Ui.sectionTitle(this, "الأصدقاء والرسائل"), st);
            addCard("الأصدقاء", "واجهة أولية", "قريبًا",
                    () -> Ui.toast(this, "سيتم ربط هذه الوظيفة بعد نقل بيانات الموقع"));
            addCard("صندوق الوارد", "واجهة أولية", "فتح",
                    () -> Ui.toast(this, "صندوق الوارد يحتاج ربط البيانات"));
        } else if (PROFILE.equals(page)) {
            LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(-1, -2);
            st.bottomMargin = dp(12);
            content.addView(Ui.sectionTitle(this, "ملفي الشخصي"), st);
            boolean hasUpdate = AppUpdater.updateKnown(this);
            addCard("التحديثات والنسخ الاحتياطي",
                    hasUpdate ? "يتوفر إصدار جديد. الإصدار الحالي " + AppUpdater.currentName(this)
                            : "الإصدار الحالي " + AppUpdater.currentName(this) + " · حدّث دون فقدان بياناتك",
                    hasUpdate ? "عرض التحديث" : "فتح", this::openUpdates);
            addCard("إعدادات ARCANUM", "المظهر والتفضيلات", "الإعدادات",
                    () -> Ui.toast(this, "الإعدادات الأصلية قيد النقل"));
        }
        updateBack();
    }

    /** Welcome card on the home page: the raven emblem next to the greeting. */
    private View heroCard() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(14), dp(12), dp(16), dp(12));
        hero.setBackground(Ui.rounded(this, Ui.SURFACE, 0x8075416F, 1, 20));
        hero.addView(Ui.crow(this, 92));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, "مرحبًا بك في ARCANUM", 20, Ui.TEXT, true));
        TextView sub = Ui.text(this, "عالمك من المانغا والمانهوا والونشوت", 14, Ui.MUTED, false);
        sub.setPadding(0, dp(4), 0, 0);
        col.addView(sub);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -2, 1f);
        cLp.setMarginStart(dp(10));
        hero.addView(col, cLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        hero.setLayoutParams(lp);
        return hero;
    }

    private void addCard(String title, String desc, String action, Runnable run) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(Ui.rounded(this, Ui.CARD, 0x6675416F, 1, 20));
        card.addView(Ui.text(this, title, 19, Ui.LAV, true), new LinearLayout.LayoutParams(-1, -2));
        TextView d = Ui.text(this, desc, 14, Ui.MUTED, false);
        d.setPadding(0, dp(6), 0, dp(12));
        card.addView(d, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout b = Ui.button(this, action, 0, false);
        b.setOnClickListener(v -> run.run());
        card.addView(b, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        content.addView(card, lp);
        Ui.reveal(card, content.getChildCount());
    }

    private void addButton(String label, int iconRes, Runnable run, boolean primary) {
        LinearLayout b = Ui.button(this, label, iconRes, primary);
        b.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.bottomMargin = dp(10);
        content.addView(b, lp);
    }

    // ------------------------------------------------------------------ splash

    /** Brief branded start screen: the full logo, a breathing ornament, then a soft fade into the app. */
    private void showSplash() {
        FrameLayout sp = new FrameLayout(this);
        sp.setBackgroundColor(Ui.BG);
        sp.setClickable(true); // swallow touches while it is visible
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.arcanum_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAdjustViewBounds(true);
        logo.setContentDescription("ARCANUM MangaVerse");
        int side = Math.min(dp(340), (int) (getResources().getDisplayMetrics().widthPixels * 0.86f));
        col.addView(logo, new LinearLayout.LayoutParams(side, side));
        final View orn = Ui.ornament(this);
        col.addView(orn, new LinearLayout.LayoutParams(Math.round(side * 0.55f), -2));
        sp.addView(col, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        container.addView(sp, new FrameLayout.LayoutParams(-1, -1));
        splash = sp;

        logo.setAlpha(0f);
        logo.setScaleX(0.94f);
        logo.setScaleY(0.94f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520)
                .setInterpolator(new DecelerateInterpolator()).start();
        orn.setAlpha(0.2f);
        orn.animate().alpha(1f).setDuration(700).start();
        sp.postDelayed(this::hideSplash, 1300);
    }

    private void hideSplash() {
        final View sp = splash;
        if (sp == null) return;
        splash = null;
        sp.animate().alpha(0f).setDuration(320).withEndAction(() -> container.removeView(sp)).start();
    }

    // ------------------------------------------------------------------ overlays (full-screen pages)

    private void pushOverlay(View v, Runnable onPop, Runnable onRevealed) {
        container.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setAlpha(0f);
        v.setTranslationX(-dp(36));
        v.setScaleX(0.97f);
        v.setScaleY(0.97f);
        v.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
        overlays.add(new Overlay(v, onPop, onRevealed));
        updateBack();
    }

    private void popOverlay() {
        if (overlays.isEmpty()) return;
        hideKeyboard();
        Overlay top = overlays.remove(overlays.size() - 1);
        if (top.onPop != null) top.onPop.run();
        final View v = top.view;
        v.setClickable(false); // the closing page must not eat touches meant for the page below it
        v.animate().alpha(0f).translationX(-dp(36)).scaleX(0.97f).scaleY(0.97f).setDuration(180)
                .withEndAction(() -> container.removeView(v)).start();
        if (!overlays.isEmpty()) {
            Runnable r = overlays.get(overlays.size() - 1).onRevealed;
            if (r != null) r.run();
        } else {
            mangaVerse.refresh();
            if (STUDIO.equals(active)) studio.refresh();
        }
        updateBack();
    }

    private void openPublish() {
        if (publish != null) return;
        publish = new PublishScreen(this, store, new PublishScreen.Host() {
            @Override
            public void closePublish(boolean saved) {
                NativeHomeActivity.this.closePublish(saved);
            }

            @Override
            public void pickCover() {
                NativeHomeActivity.this.pickCover();
            }
        });
        final PublishScreen screen = publish;
        pushOverlay(screen.build(), () -> {
            screen.onClosed();
            publish = null;
        }, null);
    }

    private void closePublish(boolean saved) {
        if (publish == null) return;
        popOverlay();
        if (saved) showPage(MANGA);
    }

    /** The work page: cover, story, categories and chapters. */
    private void openWork(String workId) {
        if (detail != null) return;
        final WorkDetailScreen screen = new WorkDetailScreen(this, store, workId, new WorkDetailScreen.Host() {
            @Override
            public void close() {
                popOverlay();
            }

            @Override
            public void openAddChapter(String id) {
                NativeHomeActivity.this.openAddChapter(id);
            }

            @Override
            public void openReader(String id, String chapterId) {
                NativeHomeActivity.this.openReader(id, chapterId);
            }
        });
        detail = screen;
        pushOverlay(screen.build(), () -> detail = null, screen::refresh);
    }

    private void openAddChapter(String workId) {
        if (addChapter != null) return;
        Store.Work work = store.work(workId);
        if (work == null) return;
        addChapter = new AddChapterScreen(this, store, work, new AddChapterScreen.Host() {
            @Override
            public void closeAddChapter(boolean saved) {
                popOverlay();
            }

            @Override
            public void pickPages() {
                NativeHomeActivity.this.pickPages();
            }
        });
        final AddChapterScreen screen = addChapter;
        pushOverlay(screen.build(), () -> {
            screen.onClosed();
            addChapter = null;
        }, null);
    }

    private void openReader(String workId, String chapterId) {
        if (reader != null) return;
        final ReaderScreen screen = new ReaderScreen(this, store, workId, chapterId, () -> popOverlay());
        reader = screen;
        pushOverlay(screen.build(), () -> {
            screen.onClosed();
            reader = null;
        }, null);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void pickCover() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_COVER);
    }

    private void pickPages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_PAGES);
    }

    // ------------------------------------------------------------------ back button

    /** Handles "back": closes the top full-screen page, or returns to Home from another tab. */
    private boolean handleBack() {
        if (backInterceptor != null && backInterceptor.getAsBoolean()) return true;
        if (!overlays.isEmpty()) {
            popOverlay();
            return true;
        }
        if (!HOME.equals(active)) {
            showPage(HOME);
            return true;
        }
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!handleBack()) super.onBackPressed();
    }

    /** On Android 13+ the back gesture is delivered through a callback, registered only when we need it. */
    private void updateBack() {
        boolean need = !overlays.isEmpty() || !HOME.equals(active);
        if (Build.VERSION.SDK_INT >= 33) {
            if (need && backCallback == null) {
                backCallback = BackApi33.register(this, this::handleBack);
            } else if (!need && backCallback != null) {
                BackApi33.unregister(this, backCallback);
                backCallback = null;
            }
        }
    }

    private static final class BackApi33 {
        static Object register(Activity a, final Runnable action) {
            OnBackInvokedCallback cb = action::run;
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            return cb;
        }

        static void unregister(Activity a, Object cb) {
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) cb);
        }
    }

    // ------------------------------------------------------------------ media picking (Studio)

    /** Home-page shortcut: pick photos/videos and save them straight into the Studio gallery. */
    private void pickMedia(String mime) {
        pickStudioFiles(mime.split(","), true, uris -> {
            if (uris == null || uris.isEmpty()) return;
            showPage(STUDIO);
            studioCtx.importPicked(uris, StudioStore.SCOPE_GALLERY, "", "", "", "media", () -> studio.refresh());
        });
    }

    /** Opens Android's own document picker (no storage permission needed) for the Studio screens. */
    private void pickStudioFiles(String[] mimeTypes, boolean multiple, StudioContext.UriCallback cb) {
        studioCallback = cb;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            startActivityForResult(i, PICK_STUDIO);
        } catch (Exception e) {
            studioCallback = null;
            Ui.toast(this, "تعذّر فتح منتقي الملفات على هذا الهاتف");
        }
    }

    @Override
    protected void onPause() {
        // write any unsaved note text before the app can be closed or killed in the background
        if (studio != null) studio.flush();
        super.onPause();
    }

    private void keepAccess(Intent data, Uri uri) {
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags != 0 ? flags : Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // some providers do not allow persistable access; the Uri still works for this session
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_STUDIO) {
            StudioContext.UriCallback cb = studioCallback;
            studioCallback = null;
            if (cb == null || resultCode != RESULT_OK || data == null) return;
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int n = 0; n < data.getClipData().getItemCount(); n++) {
                    Uri u = data.getClipData().getItemAt(n).getUri();
                    if (u != null) picked.add(u);
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            cb.onPicked(picked);
            return;
        }

        if (requestCode == CREATE_BACKUP) {
            StudioContext.UriCallback cb = backupCallback;
            backupCallback = null;
            if (cb == null || resultCode != RESULT_OK || data == null || data.getData() == null) return;
            List<Uri> one = new ArrayList<>();
            one.add(data.getData());
            cb.onPicked(one);
            return;
        }

        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == PICK_COVER) {
            Uri u = data.getData();
            if (u != null && publish != null) {
                keepAccess(data, u);
                publish.onCoverPicked(u);
            }
            return;
        }

        if (requestCode == PICK_PAGES) {
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int n = 0; n < data.getClipData().getItemCount(); n++) {
                    Uri u = data.getClipData().getItemAt(n).getUri();
                    keepAccess(data, u);
                    picked.add(u);
                }
            } else if (data.getData() != null) {
                Uri u = data.getData();
                keepAccess(data, u);
                picked.add(u);
            }
            if (addChapter != null && !picked.isEmpty()) addChapter.onPagesPicked(picked);
            return;
        }
    }
}
