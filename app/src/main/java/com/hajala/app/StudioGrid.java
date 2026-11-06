package com.hajala.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds the tidy square-thumbnail grid used by the gallery, personal folders and medical images. */
final class StudioGrid {
    interface Listener {
        void onClick(StudioStore.Item item, int index);

        void onLongClick(StudioStore.Item item, int index);
    }

    private StudioGrid() {}

    static int columns(Context c) {
        float widthDp = c.getResources().getDisplayMetrics().widthPixels / c.getResources().getDisplayMetrics().density;
        return Math.max(3, Math.min(6, (int) (widthDp / 118f)));
    }

    /**
     * Rows of square cells. [selected] is null when selection mode is off. Shows at most [limit] items;
     * the caller adds a "show more" button when list.size() > limit.
     */
    static LinearLayout build(final StudioContext ctx, List<StudioStore.Item> list, int limit,
                              Set<String> selected, final Listener l) {
        return build(ctx, list, limit, selected, l, null);
    }

    /** Same, and the cells whose id is in [fresh] (newly added or newly shown) animate in. */
    static LinearLayout build(final StudioContext ctx, List<StudioStore.Item> list, int limit,
                              Set<String> selected, final Listener l, Set<String> fresh) {
        Context c = ctx.act;
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        int cols = columns(c);
        int gap = ctx.dp(3);
        int pageWidth = c.getResources().getDisplayMetrics().widthPixels - ctx.dp(32);
        int cellPx = Math.max(96, pageWidth / cols);
        int shown = Math.min(limit, list.size());
        for (int i = 0; i < shown; i += cols) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols; j++) {
                int idx = i + j;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(gap, gap, gap, gap);
                if (idx < shown) {
                    StudioStore.Item it = list.get(idx);
                    View cellView = cell(ctx, it, idx, cellPx, selected != null, selected != null && selected.contains(it.id), l);
                    row.addView(cellView, lp);
                    if (fresh != null && fresh.contains(it.id)) Ui.pop(cellView, idx);
                } else {
                    row.addView(new View(c), lp);
                }
            }
            grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        return grid;
    }

    private static View cell(final StudioContext ctx, final StudioStore.Item it, final int index, int px,
                             boolean selecting, boolean isSelected, final Listener l) {
        Context c = ctx.act;
        SquareFrame f = new SquareFrame(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.SURFACE);
        bg.setCornerRadius(ctx.dp(12));
        f.setBackground(bg);
        f.setClipToOutline(true);

        if (StudioStore.KIND_FILE.equals(it.kind)) {
            f.addView(fileFace(ctx, it), new FrameLayout.LayoutParams(-1, -1));
        } else {
            final ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            final FrameLayout holder = f;
            StudioMedia.load(ctx.store, it, iv, px, () -> {
                // unreadable or missing file: show a clear placeholder instead of a blank square
                holder.removeView(iv);
                holder.addView(brokenFace(ctx), 0, new FrameLayout.LayoutParams(-1, -1));
            });
            if (StudioStore.KIND_VIDEO.equals(it.kind)) {
                FrameLayout play = new FrameLayout(c);
                GradientDrawable pb = new GradientDrawable();
                pb.setShape(GradientDrawable.OVAL);
                pb.setColor(0xB3100B13);
                pb.setStroke(Math.max(1, ctx.dp(1)), 0x99C39AB8);
                play.setBackground(pb);
                // nudged a hair toward the point of the triangle so it looks optically centred
                FrameLayout.LayoutParams pi = new FrameLayout.LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.CENTER);
                pi.setMarginStart(ctx.dp(2));
                play.addView(Ui.icon(c, R.drawable.ic_play, Ui.TEXT, 16), pi);
                f.addView(play, new FrameLayout.LayoutParams(ctx.dp(36), ctx.dp(36), Gravity.CENTER));
                if (it.duration > 0) {
                    TextView d = Ui.pill(c, formatDuration(it.duration), Ui.TEXT, 0xCC100B13, 0);
                    FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
                    dl.setMargins(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5));
                    f.addView(d, dl);
                }
            }
        }

        if (it.favorite && !selecting) {
            FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.BOTTOM | Gravity.START);
            hl.setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6));
            f.addView(Ui.icon(c, R.drawable.ic_heart, 0xFFFF6B8A, 16), hl);
        }

        if (selecting) {
            if (isSelected) {
                View veil = new View(c);
                veil.setBackgroundColor(0x6675416F);
                f.addView(veil, new FrameLayout.LayoutParams(-1, -1));
            }
            FrameLayout mark = new FrameLayout(c);
            GradientDrawable mb = new GradientDrawable();
            mb.setShape(GradientDrawable.OVAL);
            mb.setColor(isSelected ? Ui.LAV : 0x66100B13);
            mb.setStroke(Math.max(1, ctx.dp(2)), isSelected ? Ui.LAV : 0xCCE8DDE8);
            mark.setBackground(mb);
            if (isSelected) {
                mark.addView(Ui.icon(c, R.drawable.ic_check, Ui.ON_LAV, 14),
                        new FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14), Gravity.CENTER));
            }
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.TOP | Gravity.START);
            ml.setMargins(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6));
            f.addView(mark, ml);
        }

        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.TRANSPARENT);
        frame.setCornerRadius(ctx.dp(12));
        frame.setStroke(Math.max(1, ctx.dp(1)), isSelected ? Ui.LAV : 0x8075416F);
        f.setForeground(Ui.ripple(c, frame, 12));
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription((StudioStore.KIND_VIDEO.equals(it.kind) ? "فيديو " : StudioStore.KIND_IMAGE.equals(it.kind) ? "صورة " : "ملف ") + it.name);
        f.setOnClickListener(v -> l.onClick(it, index));
        Ui.press(f);
        f.setOnLongClickListener(v -> {
            l.onLongClick(it, index);
            return true;
        });
        return f;
    }

    private static View fileFace(StudioContext ctx, StudioStore.Item it) {
        Context c = ctx.act;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6));
        int dot = it.name.lastIndexOf('.');
        String ext = dot >= 0 && dot < it.name.length() - 1 ? it.name.substring(dot + 1).toUpperCase(Locale.ROOT) : "FILE";
        if (ext.length() > 5) ext = ext.substring(0, 5);
        TextView e = Ui.centered(Ui.text(c, ext, 16, Ui.LAV, true));
        e.setBackground(Ui.rounded(c, Ui.CARD, Ui.OUTLINE, 1, 8));
        e.setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4));
        box.addView(e, new LinearLayout.LayoutParams(-2, -2));
        TextView n = Ui.centered(Ui.text(c, it.name, 11, Ui.MUTED, false));
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = ctx.dp(6);
        box.addView(n, nl);
        return box;
    }

    private static View brokenFace(StudioContext ctx) {
        Context c = ctx.act;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView t = Ui.centered(Ui.text(c, "تعذّر\nالعرض", 11, Ui.MUTED, false));
        box.addView(t);
        return box;
    }

    static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** A frame that is always as tall as it is wide. */
    private static final class SquareFrame extends FrameLayout {
        SquareFrame(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY));
        }
    }
}
