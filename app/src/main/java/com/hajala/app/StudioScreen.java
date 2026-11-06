package com.hajala.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** The Studio tab: gallery, "مجلداتي" (personal folders) and "الدراسة الطبية" (medical study). */
final class StudioScreen {
    interface FolderCallback {
        void onFolder(StudioStore.Folder folder);
    }

    private static final int TAB_GALLERY = 0;
    private static final int TAB_PHOTOS = 1;
    private static final int TAB_VIDEOS = 2;
    private static final int TAB_FOLDERS = 3;
    private static final int TAB_FAVORITES = 4;
    private static final int TAB_MEDICAL = 5;
    private static final int TAB_TRASH = 6;
    private static final int TAB_STORAGE = 7;
    private static final String[] TAB_LABELS = {"المعرض", "الصور", "الفيديوهات", "الألبومات", "المفضلة",
            "الدراسة الطبية", "المحذوفات", "التخزين"};

    static final int[] FOLDER_COLORS = {
            0xFF75416F, 0xFFB5698A, 0xFF6A4A9C, 0xFF45508F, 0xFF3F7F86, 0xFF5E7F5A, 0xFF9A7B4F, 0xFF6B6477};
    /** Stored keys (unchanged, so saved folders keep working). They are never drawn as text: see Ui.folderIconRes. */
    static final String[] FOLDER_ICONS = Ui.FOLDER_ICON_KEYS;

    private final StudioContext ctx;
    private final MedicalScreen medical;
    private int tab = TAB_GALLERY;

    private LinearLayout root;
    private LinearLayout tabBody;
    private final List<TextView> tabChips = new ArrayList<>();
    private StudioMediaSection gallery;

    StudioScreen(StudioContext ctx) {
        this.ctx = ctx;
        this.medical = new MedicalScreen(ctx);
    }

    private int dp(float v) {
        return ctx.dp(v);
    }

    /** Called by the activity when the app goes to the background: unsaved note text is written now. */
    void flush() {
        ctx.flush();
    }

    View build() {
        Context c = ctx.act;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);

        if (!ctx.store.loadError().isEmpty()) {
            TextView warn = Ui.text(c, ctx.store.loadError() + ". تم الاحتفاظ بالملفات نفسها على الجهاز.", 13, Ui.ERROR, false);
            root.addView(warn, margins(-1, -2, 0, 0, 0, 10));
        }

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        tabChips.clear();
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int index = i;
            TextView chip = Ui.chip(c, TAB_LABELS[i], i == tab);
            chip.setOnClickListener(v -> {
                tab = index;
                styleTabs();
                renderTab();
            });
            chips.addView(chip, margins(-2, -2, 0, 0, 8, 0));
            tabChips.add(chip);
        }
        hs.addView(chips);
        root.addView(hs, margins(-1, -2, 0, 0, 0, 14));

        tabBody = new LinearLayout(c);
        tabBody.setOrientation(LinearLayout.VERTICAL);
        root.addView(tabBody, new LinearLayout.LayoutParams(-1, -2));
        renderTab();
        return root;
    }

    private void styleTabs() {
        for (int i = 0; i < tabChips.size(); i++) Ui.styleChip(ctx.act, tabChips.get(i), i == tab);
    }

    /** Redraws the visible tab from storage (after a full-screen page above it closes). */
    void refresh() {
        if (tabBody == null) return;
        if ((tab == TAB_GALLERY || tab == TAB_PHOTOS || tab == TAB_VIDEOS || tab == TAB_FAVORITES) && gallery != null) {
            gallery.refresh();
        } else if (tab == TAB_FOLDERS) renderFolders();
        else if (tab == TAB_MEDICAL) medical.refresh();
        else if (tab == TAB_TRASH) renderTrash();
        else if (tab == TAB_STORAGE) renderStorage();
    }

    private void renderTab() {
        tabBody.animate().cancel();
        tabBody.setAlpha(0f);
        tabBody.setTranslationY(dp(8));
        tabBody.animate().alpha(1f).translationY(0f).setDuration(Ui.ANIM_BASE)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        tabBody.removeAllViews();
        if (tab == TAB_GALLERY || tab == TAB_PHOTOS || tab == TAB_VIDEOS || tab == TAB_FAVORITES) {
            String mode = tab == TAB_PHOTOS ? StudioMediaSection.MODE_PHOTOS
                    : tab == TAB_VIDEOS ? StudioMediaSection.MODE_VIDEOS
                    : tab == TAB_FAVORITES ? StudioMediaSection.MODE_FAVORITES : StudioMediaSection.MODE_ALL;
            gallery = new StudioMediaSection(ctx, "", null, mode);
            tabBody.addView(gallery.build());
        } else if (tab == TAB_FOLDERS) {
            renderFolders();
        } else if (tab == TAB_TRASH) {
            renderTrash();
        } else if (tab == TAB_STORAGE) {
            renderStorage();
        } else {
            gallery = null;
            tabBody.addView(medical.build());
        }
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(0, dp(t), 0, dp(b));
        p.setMarginStart(dp(l));
        p.setMarginEnd(dp(r));
        return p;
    }

    // ------------------------------------------------------------------ trash

    private void renderTrash() {
        Context c = ctx.act;
        tabBody.removeAllViews();
        final List<StudioStore.Item> trashed = ctx.store.trashedItems();
        tabBody.addView(Ui.text(c, "تُحذف الملفات نهائياً بعد " + StudioStore.TRASH_DAYS + " يوماً من وجودها هنا.",
                13, Ui.MUTED, false), margins(-1, -2, 0, 0, 0, 12));
        if (trashed.isEmpty()) {
            tabBody.addView(Ui.emptyState(c, "سلة المحذوفات فارغة", "الملفات التي تحذفها تظهر هنا ويمكنك استعادتها."),
                    new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout restoreAll = Ui.button(c, "استعادة الكل", 0, true);
        restoreAll.setOnClickListener(v -> {
            ctx.store.restoreItems(StudioStore.ids(trashed));
            ctx.toast("تمت الاستعادة");
            renderTrash();
        });
        LinearLayout empty = Ui.button(c, "إفراغ السلة", 0, false);
        empty.setOnClickListener(v -> ctx.confirm("إفراغ السلة",
                "سيتم حذف " + trashed.size() + " ملف نهائياً ولا يمكن التراجع.", "إفراغ", () -> {
                    ctx.store.emptyTrash();
                    ctx.toast("تم إفراغ السلة");
                    renderTrash();
                }));
        row.addView(restoreAll, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, dp(48), 1f);
        el.setMarginStart(dp(10));
        row.addView(empty, el);
        tabBody.addView(row, margins(-1, -2, 0, 0, 0, 12));
        tabBody.addView(StudioGrid.build(ctx, trashed, 150, null, new StudioGrid.Listener() {
            @Override
            public void onClick(StudioStore.Item item, int index) {
                trashMenu(item);
            }

            @Override
            public void onLongClick(StudioStore.Item item, int index) {
                trashMenu(item);
            }
        }), new LinearLayout.LayoutParams(-1, -2));
    }

    private void trashMenu(final StudioStore.Item item) {
        ctx.choose(item.name, new String[] {"استعادة", "حذف نهائي"}, which -> {
            final List<String> one = new ArrayList<>();
            one.add(item.id);
            if (which == 0) {
                ctx.store.restoreItems(one);
                ctx.toast("تمت الاستعادة");
                renderTrash();
            } else {
                ctx.confirm("حذف نهائي", "سيُحذف «" + item.name + "» نهائياً ولا يمكن التراجع.", "حذف", () -> {
                    ctx.store.deleteItems(one);
                    renderTrash();
                });
            }
        });
    }

    // ------------------------------------------------------------------ storage

    private void renderStorage() {
        Context c = ctx.act;
        tabBody.removeAllViews();
        long used = ctx.store.usedBytes();
        long trash = ctx.store.trashBytes();
        long free = ctx.store.freeBytes();
        long total = ctx.store.totalBytes();

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(Ui.rounded(c, Ui.CARD, Ui.BORDER, 1, 16));
        card.addView(Ui.text(c, "المساحة المستخدمة في التطبيق", 14, Ui.MUTED, false), margins(-1, -2, 0, 0, 0, 4));
        card.addView(Ui.text(c, StudioStore.sizeLabel(used), 24, Ui.TEXT, true), margins(-1, -2, 0, 0, 0, 10));
        if (total > 0) {
            float frac = Math.max(0.02f, Math.min(1f, (float) used / total));
            LinearLayout bar = new LinearLayout(c);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackground(Ui.rounded(c, Ui.SURFACE, 0, 0, 6));
            View fill = new View(c);
            fill.setBackground(Ui.rounded(c, Ui.LAV, 0, 0, 6));
            bar.addView(fill, new LinearLayout.LayoutParams(0, -1, frac));
            bar.addView(new View(c), new LinearLayout.LayoutParams(0, -1, 1f - frac));
            card.addView(bar, margins(-1, 10, 0, 0, 0, 10));
        }
        card.addView(Ui.text(c, "المتبقي على الهاتف: " + (free < 0 ? "غير معروف" : StudioStore.sizeLabel(free))
                + (total > 0 ? "  من  " + StudioStore.sizeLabel(total) : ""), 14, Ui.TEXT, false), margins(-1, -2, 0, 0, 0, 4));
        card.addView(Ui.text(c, "في سلة المحذوفات: " + StudioStore.sizeLabel(trash), 14, Ui.TEXT, false),
                margins(-1, -2, 0, 0, 0, 0));
        tabBody.addView(card, margins(-1, -2, 0, 0, 0, 16));

        tabBody.addView(Ui.text(c, "أكبر الملفات", 16, Ui.LAV, true), margins(-1, -2, 0, 0, 0, 8));
        final List<StudioStore.Item> big = ctx.store.largest(15);
        if (big.isEmpty()) {
            tabBody.addView(Ui.emptyState(c, "لا توجد ملفات بعد", null), new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < big.size(); i++) {
            final int index = i;
            final StudioStore.Item it = big.get(i);
            LinearLayout line = new LinearLayout(c);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(14), dp(12), dp(14), dp(12));
            line.setBackground(Ui.ripple(c, Ui.rounded(c, Ui.SURFACE, Ui.BORDER, 1, 14), 14));
            line.setClickable(true);
            TextView name = Ui.text(c, it.name, 14, Ui.TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView size = Ui.text(c, StudioStore.sizeLabel(it.size), 13, Ui.LAV, true);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.setMarginStart(dp(10));
            line.addView(size, sl);
            line.setOnClickListener(v -> ctx.openViewer(big, index, false, this::renderStorage));
            Ui.press(line);
            tabBody.addView(line, margins(-1, -2, 0, 0, 0, 8));
        }
    }

    // ------------------------------------------------------------------ personal folders

    private void renderFolders() {
        Context c = ctx.act;
        tabBody.removeAllViews();
        LinearLayout create = Ui.button(c, "ألبوم جديد", R.drawable.ic_plus, true);
        create.setOnClickListener(v -> editFolder(ctx, null, f -> {
            renderFolders();
            openFolder(f.id);
        }));
        tabBody.addView(create, margins(-1, 54, 0, 0, 0, 12));

        final List<StudioStore.Folder> folders = ctx.store.folders();
        tabBody.addView(Ui.text(c, "عدد الألبومات: " + folders.size(), 14, Ui.LAV, true), margins(-1, -2, 0, 0, 0, 12));
        if (folders.isEmpty()) {
            tabBody.addView(Ui.emptyState(c, "لا توجد مجلدات بعد",
                    "أنشئ مجلداً بالاسم واللون والرمز الذي تريده، ثم أضف إليه صورك وفيديوهاتك وملفاتك."),
                    new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < folders.size(); i += 2) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 2; j++) {
                int idx = i + j;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (j == 0) lp.setMarginEnd(dp(7));
                else lp.setMarginStart(dp(7));
                row.addView(idx < folders.size() ? folderCard(folders.get(idx), idx) : new View(c), lp);
            }
            tabBody.addView(row, margins(-1, -2, 0, 0, 0, 14));
        }
    }

    private View folderCard(final StudioStore.Folder f, int idx) {
        Context c = ctx.act;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(12), dp(16), dp(12), dp(14));
        card.setMinimumHeight(dp(140));
        card.setClickable(true);
        card.setFocusable(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {Ui.CARD, Ui.SURFACE});
        bg.setCornerRadius(dp(18));
        bg.setStroke(Math.max(1, dp(1)), (f.color & 0x00FFFFFF) | 0x99000000);
        card.setBackground(Ui.ripple(c, bg, 18));

        FrameLayout badge = new FrameLayout(c);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(f.color);
        badge.setBackground(circle);
        badge.addView(Ui.icon(c, Ui.folderIconRes(f.icon), Ui.TEXT, 28),
                new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        card.addView(badge, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView name = Ui.centered(Ui.text(c, f.name, 16, Ui.TEXT, true));
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name, margins(-1, -2, 0, 10, 0, 0));
        int n = ctx.store.folderItemCount(f.id);
        card.addView(Ui.centered(Ui.text(c, n + " ملف", 13, Ui.MUTED, false)), margins(-1, -2, 0, 3, 0, 0));

        card.setContentDescription("مجلد " + f.name + "، " + n + " ملف");
        card.setOnClickListener(v -> openFolder(f.id));
        card.setOnLongClickListener(v -> {
            folderMenu(f);
            return true;
        });
        Ui.asButton(card);
        Ui.press(card);
        Ui.reveal(card, idx);
        return card;
    }

    private void folderMenu(final StudioStore.Folder f) {
        ctx.choose(f.name, new String[] {"فتح", "تعديل الاسم واللون والرمز", "حذف المجلد"}, which -> {
            if (which == 0) openFolder(f.id);
            else if (which == 1) editFolder(ctx, f, x -> renderFolders());
            else confirmDeleteFolder(f, this::renderFolders);
        });
    }

    private void confirmDeleteFolder(final StudioStore.Folder f, final Runnable after) {
        int n = ctx.store.folderItemCount(f.id);
        String msg = n == 0 ? "سيُحذف المجلد «" + f.name + "»."
                : "سيُحذف المجلد «" + f.name + "» فقط، وتبقى ملفاته (" + n + ") في المعرض الرئيسي دون حذف.";
        ctx.confirm("حذف المجلد", msg, "حذف", () -> {
            ctx.store.deleteFolder(f.id);
            ctx.toast("تم حذف المجلد");
            after.run();
        });
    }

    // ------------------------------------------------------------------ a single folder (full-screen page)

    private void openFolder(final String folderId) {
        final StudioStore.Folder f0 = ctx.store.folder(folderId);
        if (f0 == null) return;
        Context c = ctx.act;
        LinearLayout page = ctx.pageRoot();

        View edit = ctx.iconActionButton(R.drawable.ic_edit, "تعديل المجلد", null);
        View del = ctx.iconActionButton(R.drawable.ic_trash, "حذف المجلد", null);
        final LinearLayout header = ctx.header(Ui.folderIconRes(f0.icon), f0.name, v -> ctx.host.pop(), edit, del);
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));
        page.addView(ctx.hairline());

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        LinearLayout holder = new LinearLayout(c);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(16), dp(14), dp(16), dp(24));
        sv.addView(holder, new FrameLayout.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        final StudioMediaSection section = new StudioMediaSection(ctx, folderId, null);
        holder.addView(section.build());

        edit.setOnClickListener(v -> {
            StudioStore.Folder cur = ctx.store.folder(folderId);
            if (cur == null) return;
            editFolder(ctx, cur, updated -> {
                StudioContext.setHeader(header, Ui.folderIconRes(updated.icon), updated.name);
            });
        });
        del.setOnClickListener(v -> {
            StudioStore.Folder cur = ctx.store.folder(folderId);
            if (cur == null) return;
            confirmDeleteFolder(cur, () -> ctx.host.pop());
        });
        ctx.host.push(page, null, section::refresh);
    }

    // ------------------------------------------------------------------ folder editor dialog

    /** Create (existing == null) or edit a folder: name, colour and icon. Calls [cb] with the saved folder. */
    static void editFolder(final StudioContext ctx, final StudioStore.Folder existing, final FolderCallback cb) {
        Context c = ctx.act;
        final int[] color = {existing != null ? existing.color : FOLDER_COLORS[0]};
        final String[] icon = {existing != null ? existing.icon : FOLDER_ICONS[0]};

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0);

        final EditText name = Ui.input(c, "اسم المجلد", false);
        if (existing != null) name.setText(existing.name);
        name.setSelection(name.getText().length());
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        final TextView error = Ui.text(c, "", 13, Ui.ERROR, false);
        error.setVisibility(View.GONE);
        box.addView(error, new LinearLayout.LayoutParams(-1, -2));

        TextView l1 = Ui.text(c, "اللون", 13, Ui.MUTED, false);
        LinearLayout.LayoutParams l1p = new LinearLayout.LayoutParams(-2, -2);
        l1p.topMargin = ctx.dp(14);
        box.addView(l1, l1p);
        final FlowLayout colors = new FlowLayout(c, ctx.dp(10));
        final List<View> swatches = new ArrayList<>();
        for (final int col : FOLDER_COLORS) {
            View sw = new View(c);
            sw.setClickable(true);
            sw.setContentDescription("لون");
            sw.setOnClickListener(v -> {
                color[0] = col;
                styleSwatches(ctx, swatches, color[0]);
            });
            Ui.press(sw);
            swatches.add(sw);
            colors.addView(sw, new LinearLayout.LayoutParams(ctx.dp(38), ctx.dp(38)));
        }
        styleSwatches(ctx, swatches, color[0]);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = ctx.dp(6);
        box.addView(colors, cp);

        TextView l2 = Ui.text(c, "الرمز", 13, Ui.MUTED, false);
        LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(-2, -2);
        l2p.topMargin = ctx.dp(14);
        box.addView(l2, l2p);
        final FlowLayout icons = new FlowLayout(c, ctx.dp(10));
        final List<View> iconViews = new ArrayList<>();
        for (final String ic : FOLDER_ICONS) {
            FrameLayout t = new FrameLayout(c);
            t.addView(Ui.icon(c, Ui.folderIconRes(ic), Ui.TEXT, 22),
                    new FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER));
            t.setClickable(true);
            t.setContentDescription("رمز");
            t.setOnClickListener(v -> {
                icon[0] = ic;
                styleIcons(ctx, iconViews, icon[0]);
            });
            Ui.press(t);
            iconViews.add(t);
            icons.addView(t, new LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)));
        }
        styleIcons(ctx, iconViews, icon[0]);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.topMargin = ctx.dp(6);
        ip.bottomMargin = ctx.dp(4);
        box.addView(icons, ip);

        ScrollView sv = new ScrollView(c);
        sv.addView(box);
        final AlertDialog d = Ui.show(Ui.dialog(c)
                .setTitle(existing == null ? "مجلد جديد" : "تعديل المجلد")
                .setView(sv)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton(existing == null ? "إنشاء" : "حفظ", null));
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = StudioStore.clean(name.getText().toString());
            String err = null;
            if (n.isEmpty()) err = "اكتب اسم المجلد أولاً";
            else if (ctx.store.folderNameTaken(n, existing == null ? "" : existing.id)) err = "يوجد مجلد بهذا الاسم";
            if (err != null) {
                error.setText(err);
                error.setVisibility(View.VISIBLE);
                return;
            }
            StudioStore.Folder result;
            if (existing == null) {
                result = ctx.store.addFolder(n, color[0], icon[0]);
            } else {
                ctx.store.updateFolder(existing.id, n, color[0], icon[0]);
                result = ctx.store.folder(existing.id);
            }
            d.dismiss();
            if (result != null && cb != null) cb.onFolder(result);
        });
        name.requestFocus();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private static void styleSwatches(StudioContext ctx, List<View> views, int selected) {
        for (int i = 0; i < views.size(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(FOLDER_COLORS[i]);
            boolean on = FOLDER_COLORS[i] == selected;
            g.setStroke(ctx.dp(on ? 3 : 1), on ? Ui.LAV : 0x55E8DDE8);
            views.get(i).setBackground(g);
        }
    }

    private static void styleIcons(StudioContext ctx, List<View> views, String selected) {
        for (int i = 0; i < views.size(); i++) {
            boolean on = FOLDER_ICONS[i].equals(selected);
            views.get(i).setBackground(Ui.rounded(ctx.act, on ? Ui.CARD : Ui.SURFACE, on ? Ui.LAV : Ui.BORDER,
                    on ? 2 : 1, 12));
        }
    }
}
