package com.hajala.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Work page: cover, story, categories and the list of chapters, with "add chapter" and "read" actions. */
final class WorkDetailScreen {
    interface Host {
        void close();

        void openAddChapter(String workId);

        void openReader(String workId, String chapterId);
    }

    private final Activity act;
    private final Store store;
    private final String workId;
    private final Host host;
    private LinearLayout body;

    WorkDetailScreen(Activity act, Store store, String workId, Host host) {
        this.act = act;
        this.store = store;
        this.workId = workId;
        this.host = host;
    }

    private int dp(float v) {
        return Ui.dp(act, v);
    }

    View build() {
        Context c = act;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setClickable(true); // swallow touches so the screen underneath never reacts

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(10));
        FrameLayout back = Ui.iconButton(c, R.drawable.ic_back, "رجوع");
        back.setOnClickListener(v -> host.close());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView h = Ui.text(c, "تفاصيل العمل", 22, Ui.LAV, true);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-2, -2);
        hLp.setMarginStart(dp(12));
        header.addView(h, hLp);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View headerLine = new View(c);
        headerLine.setBackgroundColor(Ui.BORDER);
        root.addView(headerLine, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(24));
        sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        rebuild();
        return root;
    }

    /** Re-reads the work and its chapters from storage and redraws the page. */
    void refresh() {
        if (body != null) rebuild();
    }

    private LinearLayout.LayoutParams bottom(int dpValue) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(dpValue);
        return lp;
    }

    private void rebuild() {
        body.removeAllViews();
        final Store.Work w = store.work(workId);
        if (w == null) {
            body.addView(Ui.text(act, "لم يعد هذا العمل موجودًا", 16, Ui.MUTED, false), bottom(8));
            return;
        }
        final List<Store.Chapter> chapters = store.chapters(workId);

        // ---- cover + title block
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(coverBox(w), new LinearLayout.LayoutParams(dp(116), dp(154)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(act, w.title, 22, Ui.TEXT, true);
        col.addView(title, bottom(8));

        LinearLayout pills = new LinearLayout(act);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.addView(Ui.pill(act, Store.typeLabel(w.type), Ui.TEXT, Ui.CARD, 0));
        boolean published = Store.PUBLISHED.equals(w.status);
        TextView st = published
                ? Ui.pill(act, "منشور", Ui.ON_LAV, Ui.LAV, 0)
                : Ui.pill(act, "مسودة", Ui.TEXT, Ui.SURFACE, Ui.OUTLINE);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-2, -2);
        stLp.setMarginStart(dp(8));
        pills.addView(st, stLp);
        col.addView(pills, bottom(10));

        col.addView(Ui.text(act, chapters.size() + " فصل", 14, Ui.MUTED, false), bottom(0));
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1f);
        colLp.setMarginStart(dp(14));
        top.addView(col, colLp);
        body.addView(top, bottom(18));

        // ---- categories
        if (!w.genres.isEmpty()) {
            body.addView(Ui.text(act, "التصنيفات", 14, Ui.TEXT, true), bottom(8));
            FlowLayout flow = new FlowLayout(act, dp(8));
            for (String g : w.genres) {
                TextView chip = Ui.pill(act, g, Ui.LAV, Ui.SURFACE, Ui.BORDER);
                chip.setTextSize(13);
                chip.setPadding(dp(12), dp(6), dp(12), dp(6));
                flow.addView(chip, new ViewGroup.LayoutParams(-2, -2));
            }
            body.addView(flow, bottom(18));
        }

        // ---- story
        body.addView(Ui.text(act, "القصة", 14, Ui.TEXT, true), bottom(8));
        TextView desc = Ui.text(act, w.desc.isEmpty() ? "لا توجد قصة مكتوبة لهذا العمل بعد." : w.desc,
                15, w.desc.isEmpty() ? Ui.MUTED : Ui.TEXT, false);
        LinearLayout.LayoutParams dLp = bottom(20);
        body.addView(desc, dLp);

        // ---- actions
        if (!chapters.isEmpty()) {
            final String last = store.lastRead(workId);
            Store.Chapter target = chapters.get(0);
            boolean resume = false;
            for (Store.Chapter ch : chapters) {
                if (ch.id.equals(last)) {
                    target = ch;
                    resume = true;
                }
            }
            final Store.Chapter open = target;
            LinearLayout read = Ui.button(act, resume ? "تابع القراءة • " + target.label() : "ابدأ القراءة",
                    R.drawable.ic_book, true);
            read.setOnClickListener(v -> host.openReader(workId, open.id));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, dp(54));
            rLp.bottomMargin = dp(10);
            body.addView(read, rLp);
        }

        LinearLayout add = Ui.button(act, "إضافة فصل", R.drawable.ic_plus, chapters.isEmpty());
        add.setOnClickListener(v -> host.openAddChapter(workId));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(54));
        addLp.bottomMargin = dp(10);
        body.addView(add, addLp);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout toggle = Ui.button(act, published ? "تحويل لمسودة" : "نشر", 0, false);
        toggle.setOnClickListener(v -> {
            store.setStatus(workId, published ? Store.DRAFT : Store.PUBLISHED);
            rebuild();
        });
        row.addView(toggle, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout del = Ui.button(act, "حذف العمل", 0, false);
        del.setOnClickListener(v -> confirmDeleteWork(w));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        delLp.setMarginStart(dp(10));
        row.addView(del, delLp);
        body.addView(row, bottom(24));

        // ---- chapters
        body.addView(Ui.sectionTitle(act, "الفصول (" + chapters.size() + ")"), bottom(12));
        if (chapters.isEmpty()) {
            body.addView(emptyChapters(), bottom(0));
        } else {
            // newest chapter first
            for (int i = chapters.size() - 1; i >= 0; i--) {
                View rowView = chapterRow(chapters.get(i));
                body.addView(rowView, bottom(8));
                Ui.reveal(rowView, chapters.size() - 1 - i);
            }
            body.addView(Ui.text(act, "اضغط مطولًا على فصل لحذفه.", 12, Ui.MUTED, false), bottom(0));
        }
    }

    private View coverBox(Store.Work w) {
        FrameLayout box = new FrameLayout(act);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF351343, 0xFF200B2B, 0xFF100B13});
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x8075416F);
        box.setBackground(bg);
        box.setClipToOutline(true);
        if (w.cover.isEmpty()) {
            box.addView(Ui.icon(act, R.drawable.ic_book, Ui.LAV, 36), new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
        } else {
            final ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            final String cover = w.cover;
            Ui.IO.execute(() -> {
                final Bitmap bmp = Ui.decode(act, cover, 348, 462);
                if (bmp != null) iv.post(() -> iv.setImageBitmap(bmp));
            });
        }
        return box;
    }

    private View emptyChapters() {
        return Ui.emptyState(act, "لا توجد فصول بعد", "اضغط «إضافة فصل» لرفع أول فصل لهذا العمل.");
    }

    private View chapterRow(final Store.Chapter ch) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Ui.ripple(act, Ui.rounded(act, Ui.SURFACE, Ui.BORDER, 1, 14), 14));

        LinearLayout info = new LinearLayout(act);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(Ui.text(act, ch.label(), 16, Ui.TEXT, true));
        String sub = ch.title.isEmpty() ? "" : ch.title + " • ";
        sub += ch.isText() ? "نص" : ch.pages.size() + " صفحة";
        TextView subTv = Ui.text(act, sub, 13, Ui.MUTED, false);
        subTv.setMaxLines(1);
        subTv.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(subTv);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

        int p = store.progress(ch.id);
        if (p > 0) {
            String label = p >= 98 ? "تمت القراءة" : p + "%";
            row.addView(Ui.pill(act, label, p >= 98 ? Ui.ON_LAV : Ui.LAV,
                    p >= 98 ? Ui.LAV : Ui.CARD, 0));
        }

        row.setOnClickListener(v -> host.openReader(workId, ch.id));
        row.setOnLongClickListener(v -> {
            confirmDeleteChapter(ch);
            return true;
        });
        Ui.asButton(row);
        Ui.press(row);
        return row;
    }

    private void confirmDeleteChapter(final Store.Chapter ch) {
        AlertDialog.Builder b = Ui.dialog(act)
                .setTitle("حذف الفصل؟")
                .setMessage("سيتم حذف «" + ch.label() + "» من هذا العمل.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (d, i) -> {
                    store.deleteChapter(ch.id);
                    rebuild();
                    Ui.toast(act, "تم حذف الفصل");
                });
        Ui.show(b);
    }

    private void confirmDeleteWork(final Store.Work w) {
        AlertDialog.Builder b = Ui.dialog(act)
                .setTitle("حذف العمل؟")
                .setMessage("سيتم حذف «" + w.title + "» وكل فصوله من مكتبتك على هذا الجهاز.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (d, i) -> {
                    store.deleteWork(w.id);
                    Ui.toast(act, "تم حذف العمل");
                    host.close();
                });
        Ui.show(b);
    }
}
