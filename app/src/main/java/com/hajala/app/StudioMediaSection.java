package com.hajala.app;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The media part of the Studio: used by the main gallery (folderId = "") and by every personal folder.
 * Add photos/videos (and, in folders, any file), thumbnails grid, filters, multi-select, move and delete.
 */
final class StudioMediaSection {
    private static final int PAGE = 60;

    private static final String ALL = "all";
    private static final String IMAGES = StudioStore.KIND_IMAGE;
    private static final String VIDEOS = StudioStore.KIND_VIDEO;
    private static final String FILES = StudioStore.KIND_FILE;

    /** What this section shows: everything, only photos, only videos, or only favourites. */
    static final String MODE_ALL = "";
    static final String MODE_PHOTOS = "photos";
    static final String MODE_VIDEOS = "videos";
    static final String MODE_FAVORITES = "favorites";

    private final StudioContext ctx;
    private final String folderId;
    private final Runnable onStructureChanged;
    private final String mode;
    private int sortMode = StudioStore.SORT_DATE;

    private LinearLayout root;
    private LinearLayout body;
    private String filter = ALL;
    private int limit = PAGE;
    private boolean selecting;
    private final Set<String> selected = new LinkedHashSet<>();
    /** Ids already drawn once: only ids not in here animate in, so selecting or deleting never flickers the grid. */
    private final Set<String> seen = new java.util.HashSet<>();

    StudioMediaSection(StudioContext ctx, String folderId, Runnable onStructureChanged) {
        this(ctx, folderId, onStructureChanged, MODE_ALL);
    }

    StudioMediaSection(StudioContext ctx, String folderId, Runnable onStructureChanged, String mode) {
        this.ctx = ctx;
        this.folderId = folderId == null ? "" : folderId;
        this.onStructureChanged = onStructureChanged;
        this.mode = mode == null ? MODE_ALL : mode;
    }

    private boolean fixedMode() {
        return !MODE_ALL.equals(mode);
    }

    private boolean inFolder() {
        return !folderId.isEmpty();
    }

    private int dp(float v) {
        return ctx.dp(v);
    }

    View build() {
        root = new LinearLayout(ctx.act);
        root.setOrientation(LinearLayout.VERTICAL);
        body = new LinearLayout(ctx.act);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, -2));
        render();
        return root;
    }

    void refresh() {
        if (body != null) render();
    }

    // ------------------------------------------------------------------ rendering

    private List<StudioStore.Item> allItems() {
        List<StudioStore.Item> out = new ArrayList<>();
        if (MODE_FAVORITES.equals(mode)) {
            out.addAll(ctx.store.favorites());
        } else if (MODE_PHOTOS.equals(mode) || MODE_VIDEOS.equals(mode)) {
            String kind = MODE_PHOTOS.equals(mode) ? StudioStore.KIND_IMAGE : StudioStore.KIND_VIDEO;
            for (StudioStore.Item it : ctx.store.allGalleryItems()) if (kind.equals(it.kind)) out.add(it);
        } else {
            out.addAll(ctx.store.galleryItems(folderId));
        }
        StudioStore.sort(out, sortMode);
        return out;
    }

    private static boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** "اليوم" / "أمس" / a date: the heading of each group when sorted by date. */
    private static String dayLabel(long ms) {
        java.util.Calendar d = java.util.Calendar.getInstance();
        d.setTimeInMillis(ms);
        java.util.Calendar now = java.util.Calendar.getInstance();
        if (sameDay(d, now)) return "اليوم";
        now.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(d, now)) return "أمس";
        return new java.text.SimpleDateFormat("d MMMM yyyy", new java.util.Locale("ar")).format(new java.util.Date(ms));
    }

    private List<StudioStore.Item> visibleItems(List<StudioStore.Item> all) {
        List<StudioStore.Item> out = new ArrayList<>();
        for (StudioStore.Item it : all) if (ALL.equals(filter) || filter.equals(it.kind)) out.add(it);
        return out;
    }

    private void render() {
        Context c = ctx.act;
        body.removeAllViews();
        final List<StudioStore.Item> all = allItems();
        final List<StudioStore.Item> shown = visibleItems(all);

        // add buttons
        if (MODE_FAVORITES.equals(mode)) {
            // favourites are only marked, never imported here
        } else if (MODE_PHOTOS.equals(mode) || MODE_VIDEOS.equals(mode)) {
            final boolean photos = MODE_PHOTOS.equals(mode);
            LinearLayout addOne = Ui.button(c, photos ? "إضافة صور" : "إضافة فيديوهات", R.drawable.ic_plus, true);
            addOne.setOnClickListener(v -> pick(new String[] {photos ? "image/*" : "video/*"}, photos ? "image" : "video"));
            body.addView(addOne, lp(-1, 54, 0, 0, 0, 12));
        } else {
        LinearLayout add = Ui.button(c, "إضافة صور وفيديوهات", R.drawable.ic_plus, true);
        add.setOnClickListener(v -> pick(new String[] {"image/*", "video/*"}, "media"));
        body.addView(add, lp(-1, 54, 0, 0, 0, 10));
        if (inFolder()) {
            LinearLayout files = Ui.button(c, "إضافة ملفات أخرى", 0, false);
            files.setOnClickListener(v -> pick(new String[] {"*/*"}, null));
            body.addView(files, lp(-1, 48, 0, 0, 0, 12));
        } else {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout img = Ui.button(c, "صور فقط", 0, false);
            img.setOnClickListener(v -> pick(new String[] {"image/*"}, "image"));
            LinearLayout vid = Ui.button(c, "فيديو فقط", 0, false);
            vid.setOnClickListener(v -> pick(new String[] {"video/*"}, "video"));
            row.addView(img, new LinearLayout.LayoutParams(0, dp(48), 1f));
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(0, dp(48), 1f);
            vl.setMarginStart(dp(10));
            row.addView(vid, vl);
            body.addView(row, lp(-1, -2, 0, 0, 0, 12));
        }
        }

        // counts
        int images = 0;
        int videos = 0;
        int others = 0;
        for (StudioStore.Item it : all) {
            if (StudioStore.KIND_IMAGE.equals(it.kind)) images++;
            else if (StudioStore.KIND_VIDEO.equals(it.kind)) videos++;
            else others++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("عدد الملفات: ").append(all.size());
        if (!all.isEmpty()) {
            sb.append("  ·  ").append(images).append(" صورة  ·  ").append(videos).append(" فيديو");
            if (others > 0) sb.append("  ·  ").append(others).append(" ملف");
        }
        body.addView(Ui.text(c, sb.toString(), 14, Ui.LAV, true), lp(-1, -2, 0, 0, 0, 10));

        // filters
        if (!all.isEmpty()) {
            HorizontalScrollView sh = new HorizontalScrollView(c);
            sh.setHorizontalScrollBarEnabled(false);
            LinearLayout sorts = new LinearLayout(c);
            sorts.setOrientation(LinearLayout.HORIZONTAL);
            addSortChip(sorts, "الأحدث", StudioStore.SORT_DATE);
            addSortChip(sorts, "الحجم", StudioStore.SORT_SIZE);
            addSortChip(sorts, "الاسم", StudioStore.SORT_NAME);
            sh.addView(sorts);
            body.addView(sh, lp(-1, -2, 0, 0, 0, 10));
        }
        if (!all.isEmpty() && !fixedMode()) {
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(c);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            addChip(chips, "الكل", ALL);
            addChip(chips, "صور", IMAGES);
            addChip(chips, "فيديو", VIDEOS);
            if (inFolder() && others > 0) addChip(chips, "ملفات", FILES);
            hs.addView(chips);
            body.addView(hs, lp(-1, -2, 0, 0, 0, 12));
        }

        if (selecting) body.addView(selectionBar(shown), lp(-1, -2, 0, 0, 0, 10));

        if (all.isEmpty()) {
            String title = MODE_FAVORITES.equals(mode) ? "لا توجد عناصر مفضلة"
                    : MODE_VIDEOS.equals(mode) ? "لا توجد فيديوهات"
                    : MODE_PHOTOS.equals(mode) ? "لا توجد صور"
                    : inFolder() ? "هذا المجلد فارغ" : "المعرض فارغ";
            String hint = MODE_FAVORITES.equals(mode) ? "افتح أي صورة أو فيديو واضغط على القلب لإضافته هنا."
                    : "اضغط «إضافة» لحفظ ملفاتك داخل التطبيق. ستبقى بعد إغلاقه.";
            body.addView(Ui.emptyState(c, title, hint), new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        if (shown.isEmpty()) {
            body.addView(Ui.emptyState(c, "لا توجد عناصر من هذا النوع", null), new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        final Set<String> fresh = new java.util.HashSet<>();
        for (int i = 0; i < Math.min(limit, shown.size()); i++) {
            String id = shown.get(i).id;
            if (seen.add(id)) fresh.add(id);
        }
        final int total = Math.min(limit, shown.size());
        int i = 0;
        while (i < total) {
            final String label = sortMode == StudioStore.SORT_DATE ? dayLabel(shown.get(i).createdAt) : "";
            int j = i;
            while (j < total && (sortMode != StudioStore.SORT_DATE || dayLabel(shown.get(j).createdAt).equals(label))) j++;
            if (!label.isEmpty()) body.addView(Ui.text(c, label, 14, Ui.LAV, true), lp(-1, -2, 4, 8, 0, 4));
            final int offset = i;
            List<StudioStore.Item> group = shown.subList(i, j);
            body.addView(StudioGrid.build(ctx, group, group.size(), selecting ? selected : null, new StudioGrid.Listener() {
                @Override
                public void onClick(StudioStore.Item item, int index) {
                    if (selecting) {
                        toggle(item);
                    } else {
                        ctx.openViewer(shown, offset + index, false, () -> {
                            render();
                            if (onStructureChanged != null) onStructureChanged.run();
                        });
                    }
                }

                @Override
                public void onLongClick(StudioStore.Item item, int index) {
                    selecting = true;
                    toggle(item);
                }
            }, fresh), new LinearLayout.LayoutParams(-1, -2));
            i = j;
        }

        if (shown.size() > limit) {
            LinearLayout more = Ui.button(c, "عرض المزيد (" + (shown.size() - limit) + ")", 0, false);
            more.setOnClickListener(v -> {
                limit += PAGE;
                render();
            });
            body.addView(more, lp(-1, 48, 0, 12, 0, 0));
        }
    }

    private void addChip(LinearLayout row, String label, final String id) {
        final TextView chip = Ui.chip(ctx.act, label, id.equals(filter));
        chip.setOnClickListener(v -> {
            filter = id;
            limit = PAGE;
            seen.clear(); // a new filter replays the entrance
            render();
        });
        row.addView(chip, lp(-2, -2, 0, 0, 8, 0));
    }

    private void addSortChip(LinearLayout row, String label, final int id) {
        final TextView chip = Ui.chip(ctx.act, label, id == sortMode);
        chip.setOnClickListener(v -> {
            sortMode = id;
            limit = PAGE;
            render();
        });
        row.addView(chip, lp(-2, -2, 0, 0, 8, 0));
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(0, dp(t), 0, dp(b));
        p.setMarginStart(dp(l));
        p.setMarginEnd(dp(r));
        return p;
    }

    // ------------------------------------------------------------------ selection

    private void toggle(StudioStore.Item it) {
        if (!selected.remove(it.id)) selected.add(it.id);
        if (selected.isEmpty()) selecting = false;
        render();
    }

    private View selectionBar(final List<StudioStore.Item> shown) {
        Context c = ctx.act;
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.setBackground(Ui.rounded(c, Ui.CARD, 0x8075416F, 1, 16));
        bar.addView(Ui.text(c, "تم تحديد " + selected.size() + " من " + shown.size(), 15, Ui.TEXT, true),
                lp(-1, -2, 0, 0, 0, 8));
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);

        boolean everything = selected.size() == shown.size();
        TextView all = Ui.chip(c, everything ? "إلغاء تحديد الكل" : "تحديد الكل", false);
        all.setOnClickListener(v -> {
            selected.clear();
            if (!everything) for (StudioStore.Item it : shown) selected.add(it.id);
            else selecting = false;
            render();
        });
        row.addView(all, lp(-2, -2, 0, 0, 8, 0));

        TextView move = Ui.chip(c, "نقل", false);
        move.setOnClickListener(v -> moveSelected());
        row.addView(move, lp(-2, -2, 0, 0, 8, 0));

        TextView fav = Ui.chip(c, "مفضلة", false);
        fav.setOnClickListener(v -> favoriteSelected(shown));
        row.addView(fav, lp(-2, -2, 0, 0, 8, 0));

        TextView del = Ui.chip(c, "حذف", false);
        del.setTextColor(Ui.ERROR);
        del.setOnClickListener(v -> deleteSelected());
        row.addView(del, lp(-2, -2, 0, 0, 8, 0));

        TextView cancel = Ui.chip(c, "إلغاء", false);
        cancel.setOnClickListener(v -> {
            selected.clear();
            selecting = false;
            render();
        });
        row.addView(cancel, lp(-2, -2, 0, 0, 0, 0));
        hs.addView(row);
        bar.addView(hs);
        return bar;
    }

    private void deleteSelected() {
        if (selected.isEmpty()) return;
        final List<String> ids = new ArrayList<>(selected);
        ctx.confirm("نقل إلى المحذوفات", "سيُنقل " + ids.size() + " ملف إلى سلة المحذوفات ويمكنك استعادتها خلال "
                + StudioStore.TRASH_DAYS + " يوماً.", "نقل", () -> {
            ctx.store.trashItems(ids);
            selected.clear();
            selecting = false;
            render();
            if (onStructureChanged != null) onStructureChanged.run();
            ctx.toast("تم الحذف");
        });
    }

    private void favoriteSelected(List<StudioStore.Item> shown) {
        if (selected.isEmpty()) return;
        boolean allFav = true;
        for (StudioStore.Item it : shown) if (selected.contains(it.id) && !it.favorite) allFav = false;
        ctx.store.setFavorite(new ArrayList<>(selected), !allFav);
        ctx.toast(allFav ? "أُزيلت من المفضلة" : "أُضيفت إلى المفضلة");
        selected.clear();
        selecting = false;
        render();
        if (onStructureChanged != null) onStructureChanged.run();
    }

    private void moveSelected() {
        if (selected.isEmpty()) return;
        final List<String> ids = new ArrayList<>(selected);
        final List<StudioStore.Folder> targets = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        if (inFolder()) {
            targets.add(null);
            labels.add("المعرض الرئيسي");
        }
        for (StudioStore.Folder f : ctx.store.folders()) {
            if (f.id.equals(folderId)) continue;
            targets.add(f);
            labels.add(f.name);
        }
        labels.add("مجلد جديد…");
        ctx.choose("نقل إلى", labels.toArray(new String[0]), which -> {
            if (which == labels.size() - 1) {
                StudioScreen.editFolder(ctx, null, created -> finishMove(ids, created.id));
            } else {
                StudioStore.Folder t = targets.get(which);
                finishMove(ids, t == null ? "" : t.id);
            }
        });
    }

    private void finishMove(List<String> ids, String targetFolderId) {
        ctx.store.moveItems(ids, targetFolderId);
        selected.clear();
        selecting = false;
        render();
        if (onStructureChanged != null) onStructureChanged.run();
        ctx.toast("تم النقل");
    }

    // ------------------------------------------------------------------ picking

    private void pick(String[] mimes, final String accept) {
        ctx.host.pickFiles(mimes, true, (List<Uri> uris) -> {
            if (uris == null || uris.isEmpty()) return;
            ctx.importPicked(uris, StudioStore.SCOPE_GALLERY, folderId, "", "", accept, () -> {
                filter = ALL;
                render();
                if (onStructureChanged != null) onStructureChanged.run();
            });
        });
    }
}
