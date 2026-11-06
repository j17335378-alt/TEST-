package com.hajala.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A real-looking notebook: a hard cover, ruled paper pages with a binding on one side, page numbers, a page
 * turn animation, and a layout that depends on the notebook type (free writing, questions and answers, study,
 * planner). All text is written straight into [BookModel] and saved automatically through [StudioStore].
 */
final class BookScreen {
    interface LegacyOpener {
        /** Opens the old notes/images page of this notebook; [afterReveal] runs when it is closed. */
        void open(Runnable afterReveal);
    }

    interface CreateCallback {
        void onCreate(String name, String type, int cover);
    }

    // ------------------------------------------------------------------ look

    /** Cover colours: top, bottom. They stay inside the black / violet identity of the app. */
    static final int[][] COVERS = {
            {0xFF4A1D63, 0xFF1E0B2D},
            {0xFF6B2F62, 0xFF2E1230},
            {0xFF2F2C6B, 0xFF161436},
            {0xFF2D2433, 0xFF0E0A12},
            {0xFF5E2540, 0xFF2A0F1B},
            {0xFF2B4068, 0xFF121C33}};
    static final String[] COVER_NAMES = {"بنفسجي ملكي", "برقوقي", "نيلي", "أسود جلدي", "عنابي", "أزرق ليلي"};

    private static final int PAPER = 0xFFF2ECF2;
    private static final int INK = 0xFF23182B;
    private static final int INK_SOFT = 0x8A4B3A55;
    private static final int RULE = 0x5575416F;
    private static final int MARGIN_LINE = 0x66B5698A;
    private static final int LABEL_Q = 0xFF6A2F66;
    private static final int LABEL_A = 0xFF9A4A72;
    private static final int TOOL = 0xFF5A2F56;

    static String typeLabel(String t) {
        if (StudioStore.BOOK_QA.equals(t)) return "أسئلة وأجوبة";
        if (StudioStore.BOOK_STUDY.equals(t)) return "دراسة ومحاضرات";
        if (StudioStore.BOOK_PLANNER.equals(t)) return "تخطيط ومهام";
        return "ملاحظات وكتابة حرة";
    }

    static String typeHint(String t) {
        if (StudioStore.BOOK_QA.equals(t)) return "س١ وج١ مرتبة، مع وضع اختبار يخفي الإجابات";
        if (StudioStore.BOOK_STUDY.equals(t)) return "عناوين ومحاضرات ونقاط مهمة وصور";
        if (StudioStore.BOOK_PLANNER.equals(t)) return "مهام بمربعات إنجاز وملاحظات";
        return "صفحات مسطرة للكتابة الحرة والصور";
    }

    static int typeIcon(String t) {
        if (StudioStore.BOOK_QA.equals(t)) return R.drawable.ic_help;
        if (StudioStore.BOOK_STUDY.equals(t)) return R.drawable.ic_study;
        if (StudioStore.BOOK_PLANNER.equals(t)) return R.drawable.ic_tasks;
        return R.drawable.ic_feather;
    }

    private static int[] cover(int index) {
        return COVERS[Math.max(0, Math.min(COVERS.length - 1, index)) % COVERS.length];
    }

    /** Edit field drawn on ruled paper: one horizontal rule under every text line. */
    static final class LinedEditText extends EditText {
        private final Paint rule = new Paint();
        private final Rect r = new Rect();

        LinedEditText(Context c) {
            super(c);
            rule.setColor(RULE);
            rule.setStrokeWidth(Math.max(1f, Ui.dp(c, 1) * 0.8f));
        }

        @Override
        protected void onDraw(Canvas cv) {
            int n = getLineCount();
            float w = getWidth();
            for (int i = 0; i < n; i++) {
                getLineBounds(i, r);
                float y = r.bottom - rule.getStrokeWidth();
                cv.drawLine(0, y, w, y, rule);
            }
            super.onDraw(cv);
        }
    }

    /** The open book: a stack of page edges, the paper, the shadow of the binding, the margin line and stitching. */
    static final class BookDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float stack;
        private final float radius;
        private final float spine;
        private final float hole;
        private final float step;
        private final float marginOffset;

        BookDrawable(Context c) {
            stack = Ui.dp(c, 7);
            radius = Ui.dp(c, 8);
            spine = Ui.dp(c, 26);
            hole = Ui.dp(c, 3);
            step = Ui.dp(c, 44);
            marginOffset = Ui.dp(c, 34);
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float l = b.left;
            float t = b.top;
            float r = b.right;
            float bt = b.bottom;
            // the paper leaves room for the page stack at the fore-edge (end side) and the bottom
            float pl = rtl ? l + stack : l;
            float pr = rtl ? r : r - stack;
            float pb = bt - stack;
            int[] edge = {0xFFD9CFDC, 0xFFC9BDCD, 0xFFB9ACBE};
            for (int i = 3; i >= 1; i--) {
                float ext = stack * i / 3f;
                RectF sheet = new RectF(rtl ? pl - ext : pl, t + ext * 0.4f, rtl ? pr : pr + ext, pb + ext);
                p.setStyle(Paint.Style.FILL);
                p.setColor(edge[3 - i]);
                cv.drawRoundRect(sheet, radius, radius, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1f);
                p.setColor(0x33000000);
                cv.drawRoundRect(sheet, radius, radius, p);
            }
            RectF paper = new RectF(pl, t, pr, pb);
            p.setStyle(Paint.Style.FILL);
            p.setColor(PAPER);
            cv.drawRoundRect(paper, radius, radius, p);

            // shadow of the binding on the start side
            float sx0 = rtl ? pr : pl;
            float sx1 = rtl ? pr - spine : pl + spine;
            p.setShader(new LinearGradient(sx0, 0, sx1, 0, 0x55000000, 0x00000000, Shader.TileMode.CLAMP));
            cv.save();
            cv.clipRect(Math.min(sx0, sx1), t, Math.max(sx0, sx1), pb);
            cv.drawRoundRect(paper, radius, radius, p);
            cv.restore();
            p.setShader(null);

            // margin line and stitch holes near the binding
            float mx = rtl ? pr - marginOffset : pl + marginOffset;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, radius * 0.18f));
            p.setColor(MARGIN_LINE);
            cv.drawLine(mx, t + radius, mx, pb - radius, p);
            float hx = rtl ? pr - radius * 1.5f : pl + radius * 1.5f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x40231A2B);
            for (float y = t + step * 0.7f; y < pb - step * 0.4f; y += step) cv.drawCircle(hx, y, hole, p);
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Hard cover of a notebook: spine with stitching, an elastic band, and the page edges on the opposite side. */
    static final class CoverDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int top;
        private final int bottom;
        private final float radius;
        private final float spine;
        private final float edge;
        private final float band;

        CoverDrawable(Context c, int coverIndex) {
            int[] cv = cover(coverIndex);
            top = cv[0];
            bottom = cv[1];
            radius = Ui.dp(c, 12);
            spine = Ui.dp(c, 18);
            edge = Ui.dp(c, 6);
            band = Ui.dp(c, 7);
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            RectF all = new RectF(b);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP));
            cv.drawRoundRect(all, radius, radius, p);
            p.setShader(null);

            // spine band on the start side
            float s0 = rtl ? b.right - spine : b.left;
            float s1 = rtl ? b.right : b.left + spine;
            cv.save();
            cv.clipRect(s0, b.top, s1, b.bottom);
            p.setColor(0x66000000);
            cv.drawRoundRect(all, radius, radius, p);
            cv.restore();
            // light along the inner edge of the spine and a dashed stitch
            float ex = rtl ? s0 : s1;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, radius * 0.12f));
            p.setColor(0x33FFFFFF);
            cv.drawLine(ex, b.top + radius * 0.6f, ex, b.bottom - radius * 0.6f, p);
            p.setColor(0x88C39AB8);
            float sx = rtl ? s0 + (s1 - s0) * 0.4f : s0 + (s1 - s0) * 0.6f;
            float dash = radius * 0.5f;
            for (float y = b.top + radius * 1.2f; y < b.bottom - radius * 1.2f; y += dash * 2f) {
                cv.drawLine(sx, y, sx, Math.min(y + dash, b.bottom - radius), p);
            }

            // page edges on the fore-edge (end side)
            float pe0 = rtl ? b.left : b.right - edge;
            float pe1 = rtl ? b.left + edge : b.right;
            RectF pages = new RectF(pe0, b.top + radius * 0.9f, pe1, b.bottom - radius * 0.9f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFE6DCE8);
            cv.drawRect(pages, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(0x55422B4C);
            for (float y = pages.top + 3; y < pages.bottom; y += 4f) cv.drawLine(pages.left, y, pages.right, y, p);

            // elastic band
            float bx0 = rtl ? b.left + edge + radius * 0.7f : b.right - edge - radius * 0.7f - band;
            RectF strap = new RectF(bx0, b.top, bx0 + band, b.bottom);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCC0E0A12);
            cv.drawRect(strap, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0x44C39AB8);
            cv.drawLine(strap.left, b.top, strap.left, b.bottom, p);
            cv.drawLine(strap.right, b.top, strap.right, b.bottom, p);

            // thin frame on the cover
            float inset = radius * 0.9f;
            RectF frame = new RectF(rtl ? b.left + edge + inset : s1 + inset * 0.4f, b.top + inset,
                    rtl ? s0 - inset * 0.4f : b.right - edge - inset, b.bottom - inset);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, radius * 0.1f));
            p.setColor(0x44C39AB8);
            cv.drawRoundRect(frame, radius * 0.5f, radius * 0.5f, p);
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** A frame whose height follows its width, so every notebook cover has the same book proportions. */
    static final class AspectFrame extends FrameLayout {
        private final float ratio;

        AspectFrame(Context c, float ratio) {
            super(c);
            this.ratio = ratio;
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(width * ratio), MeasureSpec.EXACTLY));
        }
    }

    /** Horizontal swipes turn the page; vertical drags and taps still reach the text fields and the scroll view. */
    static final class SwipeLayout extends FrameLayout {
        interface OnSwipe {
            void onSwipe(boolean rightward);
        }

        private final int slop;
        private final int need;
        private float downX;
        private float downY;
        private boolean swiping;
        private OnSwipe listener;

        SwipeLayout(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            need = Ui.dp(c, 90);
        }

        void setOnSwipe(OnSwipe l) {
            listener = l;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    swiping = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (!swiping && Math.abs(dx) > slop * 4 && Math.abs(dx) > Math.abs(dy) * 2f) swiping = true;
                    break;
                default:
                    break;
            }
            return swiping;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!swiping) return super.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = e.getX() - downX;
                swiping = false;
                if (Math.abs(dx) >= need && listener != null) listener.onSwipe(dx > 0);
            } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                swiping = false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ notebook list: cover cards and the create dialog

    /** One notebook on the shelf: a cover with its icon, name, type and size. */
    static View coverCard(StudioContext ctx, StudioStore.Notebook nb) {
        Context c = ctx.act;
        BookModel m = BookModel.parse(ctx.store.bookJson(nb.id), nb.type);
        int legacy = ctx.store.noteCount(nb.id);
        String summary;
        if (ctx.store.bookJson(nb.id).isEmpty() && legacy > 0) summary = legacy + " ملاحظة";
        else if (StudioStore.BOOK_QA.equals(nb.type)) summary = m.pages.size() + " صفحة  ·  " + m.questionCount() + " سؤال";
        else if (StudioStore.BOOK_PLANNER.equals(nb.type)) summary = m.pages.size() + " صفحة  ·  " + m.taskDone() + "/" + m.taskCount() + " مهمة";
        else summary = m.pages.size() + " صفحة";

        AspectFrame card = new AspectFrame(c, 1.32f);
        card.setBackground(new CoverDrawable(c, nb.cover));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(nb.name + "، " + typeLabel(nb.type));
        card.setForeground(Ui.ripple(c, new ColorDrawable(Color.TRANSPARENT), 12));

        LinearLayout in = new LinearLayout(c);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setGravity(Gravity.CENTER_HORIZONTAL);
        in.setPaddingRelative(Ui.dp(c, 28), Ui.dp(c, 16), Ui.dp(c, 24), Ui.dp(c, 14));
        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.rounded(c, 0x33100B13, 0x66C39AB8, 1, 22));
        badge.addView(Ui.icon(c, typeIcon(nb.type), Ui.LAV, 22), new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER));
        in.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        TextView title = Ui.centered(Ui.text(c, nb.name, 16, 0xFFF1E4EF, true));
        title.setMaxLines(3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, 0, 1f);
        tl.topMargin = Ui.dp(c, 8);
        title.setGravity(Gravity.CENTER);
        in.addView(title, tl);
        TextView type = Ui.centered(Ui.text(c, typeLabel(nb.type), 11, Ui.LAV, true));
        type.setGravity(Gravity.CENTER);
        in.addView(type, new LinearLayout.LayoutParams(-1, -2));
        TextView sum = Ui.centered(Ui.text(c, summary, 11, 0xFFBFA9BC, false));
        sum.setGravity(Gravity.CENTER);
        in.addView(sum, new LinearLayout.LayoutParams(-1, -2));
        card.addView(in, new FrameLayout.LayoutParams(-1, -1));
        Ui.asButton(card);
        Ui.press(card);
        return card;
    }

    /** "New notebook": name, type and cover colour in one dialog. */
    static void showCreate(final StudioContext ctx, final java.util.function.Function<String, String> validator,
                           final CreateCallback cb) {
        final Context c = ctx.act;
        final String[] types = {StudioStore.BOOK_FREE, StudioStore.BOOK_QA, StudioStore.BOOK_STUDY, StudioStore.BOOK_PLANNER};
        final String[] sel = {StudioStore.BOOK_FREE};
        final int[] cov = {0};

        final EditText input = Ui.input(c, "اسم الدفتر (مثال: تشريح السنة الأولى)", false);
        final TextView error = Ui.text(c, "", 13, Ui.ERROR, false);
        error.setVisibility(View.GONE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), Ui.dp(c, 4));
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        box.addView(error, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(-1, -2);
        secLp.topMargin = Ui.dp(c, 14);
        secLp.bottomMargin = Ui.dp(c, 6);
        box.addView(Ui.text(c, "نوع الدفتر", 13, Ui.MUTED, true), secLp);

        final List<LinearLayout> rows = new ArrayList<>();
        final Runnable[] restyle = new Runnable[1];
        for (int i = 0; i < types.length; i++) {
            final String t = types[i];
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
            row.setClickable(true);
            row.setFocusable(true);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24));
            il.setMarginEnd(Ui.dp(c, 12));
            row.addView(Ui.icon(c, typeIcon(t), Ui.LAV, 24), il);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(Ui.text(c, typeLabel(t), 15, Ui.TEXT, true));
            texts.addView(Ui.text(c, typeHint(t), 12, Ui.MUTED, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> {
                sel[0] = t;
                restyle[0].run();
            });
            Ui.asButton(row);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = Ui.dp(c, 8);
            box.addView(row, rl);
            rows.add(row);
        }

        box.addView(Ui.text(c, "لون الغلاف", 13, Ui.MUTED, true), secLp);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout sw = new LinearLayout(c);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        final List<View> swatches = new ArrayList<>();
        for (int i = 0; i < COVERS.length; i++) {
            final int idx = i;
            View v = new View(c);
            v.setClickable(true);
            v.setFocusable(true);
            v.setContentDescription(COVER_NAMES[i]);
            v.setOnClickListener(x -> {
                cov[0] = idx;
                restyle[0].run();
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
            sl.setMarginEnd(Ui.dp(c, 10));
            sw.addView(v, sl);
            swatches.add(v);
        }
        hs.addView(sw);
        box.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        restyle[0] = () -> {
            for (int i = 0; i < rows.size(); i++) {
                boolean on = types[i].equals(sel[0]);
                rows.get(i).setBackground(Ui.ripple(c, Ui.rounded(c, on ? Ui.CARD : Ui.SURFACE, on ? Ui.LAV : Ui.BORDER, on ? 1.5f : 1, 14), 14));
            }
            for (int i = 0; i < swatches.size(); i++) {
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, COVERS[i]);
                g.setShape(GradientDrawable.OVAL);
                boolean on = i == cov[0];
                g.setStroke(Ui.dp(c, on ? 3 : 1), on ? Ui.LAV : Ui.BORDER);
                swatches.get(i).setBackground(g);
            }
        };
        restyle[0].run();

        ScrollView scroll = new ScrollView(c);
        scroll.addView(box, new FrameLayout.LayoutParams(-1, -2));
        final android.app.AlertDialog d = Ui.show(Ui.dialog(c).setTitle("دفتر جديد").setView(scroll)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إنشاء", null));
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = StudioStore.clean(input.getText().toString());
            String err = validator == null ? (text.isEmpty() ? "اكتب اسماً أولاً" : null) : validator.apply(text);
            if (err != null) {
                error.setText(err);
                error.setVisibility(View.VISIBLE);
                return;
            }
            d.dismiss();
            cb.onCreate(text, sel[0], cov[0]);
        });
        input.requestFocus();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    // ------------------------------------------------------------------ instance: one open notebook

    private static final long SAVE_DELAY_MS = 600;

    private final StudioContext ctx;
    private final String notebookId;
    private final LegacyOpener legacy;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, EditText> editors = new HashMap<>();
    private final Set<String> revealed = new HashSet<>();

    private BookModel model;
    private String type = StudioStore.BOOK_FREE;
    private String name = "";
    private int pageIndex;
    private boolean dirty;
    private boolean closed;
    private boolean deleted;
    private boolean flipping;
    private boolean keyboardUp;
    private String pendingFocus;
    private String pendingScroll;

    private LinearLayout page;
    private LinearLayout header;
    private SwipeLayout frame;
    private ScrollView scroll;
    private LinearLayout col;
    private View quizBar;
    private View addBar;
    private View navBar;
    private TextView pageLabel;
    private TextView saveLabel;
    private View prevBtn;
    private View nextBtn;
    private View quizBtn;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    private final Runnable saveTask = this::saveNow;

    BookScreen(StudioContext ctx, String notebookId, LegacyOpener legacy) {
        this.ctx = ctx;
        this.notebookId = notebookId;
        this.legacy = legacy;
    }

    private int dp(float v) {
        return ctx.dp(v);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(0, dp(t), 0, dp(b));
        p.setMarginStart(dp(l));
        p.setMarginEnd(dp(r));
        return p;
    }

    View build() {
        final Context c = ctx.act;
        StudioStore.Notebook nb = ctx.store.notebook(notebookId);
        type = nb == null ? StudioStore.BOOK_FREE : StudioStore.normType(nb.type);
        name = nb == null ? "" : nb.name;
        model = BookModel.parse(ctx.store.bookJson(notebookId), type);

        page = ctx.pageRoot();
        View menu = ctx.iconActionButton(R.drawable.ic_more, "خيارات الدفتر", v -> openMenu());
        if (StudioStore.BOOK_QA.equals(type)) {
            quizBtn = ctx.iconActionButton(model.quiz ? R.drawable.ic_eye_off : R.drawable.ic_eye, "وضع الاختبار", v -> toggleQuiz());
            header = ctx.header(R.drawable.ic_notebook, name, v -> ctx.host.pop(), quizBtn, menu);
        } else {
            header = ctx.header(R.drawable.ic_notebook, name, v -> ctx.host.pop(), menu);
        }
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));
        page.addView(ctx.hairline());

        quizBar = buildQuizBar();
        page.addView(quizBar, lp(-1, -2, 14, 8, 14, 0));

        FrameLayout desk = new FrameLayout(c);
        desk.setPadding(dp(12), dp(10), dp(12), dp(6));
        page.addView(desk, new LinearLayout.LayoutParams(-1, 0, 1f));

        frame = new SwipeLayout(c);
        frame.setBackground(new BookDrawable(c));
        frame.setPaddingRelative(0, 0, dp(7), dp(7));
        frame.setOnSwipe(rightward -> {
            boolean rtl = frame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            // the binding is on the start side: swiping away from it goes to the next page
            boolean next = rtl == rightward;
            goToPage(pageIndex + (next ? 1 : -1), true);
        });
        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPaddingRelative(dp(44), dp(18), dp(14), dp(28));
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        desk.addView(frame, new FrameLayout.LayoutParams(-1, -1));

        addBar = buildAddBar();
        page.addView(addBar, lp(-1, -2, 0, 4, 0, 0));
        navBar = buildNavBar();
        page.addView(navBar, lp(-1, -2, 14, 4, 14, 8));

        layoutListener = () -> {
            Rect r = new Rect();
            page.getWindowVisibleDisplayFrame(r);
            int screenH = page.getRootView().getHeight();
            boolean up = screenH - r.bottom > dp(120);
            if (up != keyboardUp) {
                keyboardUp = up;
                navBar.setVisibility(up ? View.GONE : View.VISIBLE);
            }
        };
        page.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);

        ctx.setFlushHook(this::saveNow);
        updateQuizUi();
        renderPage();
        return page;
    }

    /** Called when the notebook closes: whatever is typed is written now. */
    void onClosed() {
        if (!deleted) saveNow();
        closed = true;
        handler.removeCallbacks(saveTask);
        ctx.setFlushHook(null);
        try {
            if (layoutListener != null && page != null && page.getViewTreeObserver().isAlive()) {
                page.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            }
        } catch (RuntimeException ignored) {
            // the window is already gone
        }
    }

    // ------------------------------------------------------------------ saving

    private void markDirty() {
        dirty = true;
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, SAVE_DELAY_MS);
        if (saveLabel != null) saveLabel.setText("…");
    }

    private void saveNow() {
        handler.removeCallbacks(saveTask);
        if (deleted || model == null || !dirty) return;
        boolean ok = ctx.store.saveBookJson(notebookId, model.toJson());
        dirty = !ok;
        if (saveLabel != null) {
            saveLabel.setText(ok ? "تم الحفظ" : "تعذّر الحفظ");
            saveLabel.setTextColor(ok ? Ui.MUTED : Ui.ERROR);
        }
        if (!ok && !closed) handler.postDelayed(saveTask, 3000);
    }

    // ------------------------------------------------------------------ bars

    private View buildQuizBar() {
        Context c = ctx.act;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout showAll = Ui.button(c, "إظهار كل الإجابات", R.drawable.ic_eye, true);
        showAll.setOnClickListener(v -> {
            for (BookModel.Page p : model.pages) for (BookModel.Block b : p.blocks) if (BookModel.T_QA.equals(b.type)) revealed.add(b.id);
            renderPage();
        });
        LinearLayout hideAll = Ui.button(c, "إخفاء الكل", R.drawable.ic_eye_off, false);
        hideAll.setOnClickListener(v -> {
            revealed.clear();
            renderPage();
        });
        row.addView(showAll, lp(0, 46, 0, 0, 4, 0));
        ((LinearLayout.LayoutParams) showAll.getLayoutParams()).weight = 1f;
        row.addView(hideAll, lp(0, 46, 4, 0, 0, 0));
        ((LinearLayout.LayoutParams) hideAll.getLayoutParams()).weight = 1f;
        return row;
    }

    private View buildAddBar() {
        Context c = ctx.act;
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setPadding(dp(14), 0, dp(14), 0);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (StudioStore.BOOK_QA.equals(type)) {
            addAction(row, "إضافة سؤال", R.drawable.ic_plus, true, () -> addBlock(BookModel.T_QA));
            addAction(row, "نص", R.drawable.ic_text, false, () -> addBlock(BookModel.T_TEXT));
        } else if (StudioStore.BOOK_STUDY.equals(type)) {
            addAction(row, "عنوان", R.drawable.ic_edit, true, () -> addBlock(BookModel.T_HEAD));
            addAction(row, "نص", R.drawable.ic_text, false, () -> addBlock(BookModel.T_TEXT));
            addAction(row, "نقطة مهمة", R.drawable.ic_star, false, () -> addBlock(BookModel.T_KEY));
        } else if (StudioStore.BOOK_PLANNER.equals(type)) {
            addAction(row, "مهمة", R.drawable.ic_plus, true, () -> addBlock(BookModel.T_TASK));
            addAction(row, "عنوان", R.drawable.ic_edit, false, () -> addBlock(BookModel.T_HEAD));
            addAction(row, "نص", R.drawable.ic_text, false, () -> addBlock(BookModel.T_TEXT));
        } else {
            addAction(row, "نص", R.drawable.ic_text, true, () -> addBlock(BookModel.T_TEXT));
            addAction(row, "عنوان", R.drawable.ic_edit, false, () -> addBlock(BookModel.T_HEAD));
        }
        addAction(row, "صورة", R.drawable.ic_image, false, this::addImages);
        hs.addView(row);
        return hs;
    }

    private void addAction(LinearLayout row, String label, int icon, boolean primary, final Runnable action) {
        LinearLayout b = Ui.button(ctx.act, label, icon, primary);
        b.setOnClickListener(v -> action.run());
        row.addView(b, lp(-2, 46, 0, 0, 8, 0));
    }

    private View buildNavBar() {
        Context c = ctx.act;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean rtl = c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                || ctx.act.getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        prevBtn = ctx.iconActionButton(rtl ? R.drawable.ic_chevron_right : R.drawable.ic_chevron_left, "الصفحة السابقة",
                v -> goToPage(pageIndex - 1, true));
        nextBtn = ctx.iconActionButton(rtl ? R.drawable.ic_chevron_left : R.drawable.ic_chevron_right, "الصفحة التالية",
                v -> goToPage(pageIndex + 1, true));
        View addPage = ctx.iconActionButton(R.drawable.ic_page_add, "إضافة صفحة", v -> addPage());
        View delPage = ctx.iconActionButton(R.drawable.ic_trash, "حذف الصفحة", v -> confirmDeletePage());

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        mid.setClickable(true);
        mid.setFocusable(true);
        mid.setOnClickListener(v -> jumpToPage());
        pageLabel = Ui.centered(Ui.text(c, "", 15, Ui.TEXT, true));
        pageLabel.setGravity(Gravity.CENTER);
        saveLabel = Ui.centered(Ui.text(c, "", 11, Ui.MUTED, false));
        saveLabel.setGravity(Gravity.CENTER);
        mid.addView(pageLabel, new LinearLayout.LayoutParams(-1, -2));
        mid.addView(saveLabel, new LinearLayout.LayoutParams(-1, -2));

        row.addView(prevBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(mid, lp(0, 48, 8, 0, 8, 0));
        ((LinearLayout.LayoutParams) mid.getLayoutParams()).weight = 1f;
        row.addView(nextBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(addPage, lp(44, 44, 10, 0, 0, 0));
        row.addView(delPage, lp(44, 44, 6, 0, 0, 0));
        return row;
    }

    private void updateNav() {
        int n = model.pages.size();
        String label = "صفحة " + (pageIndex + 1) + " من " + n;
        if (StudioStore.BOOK_PLANNER.equals(type) && model.taskCount() > 0) {
            label += "  ·  " + model.taskDone() + "/" + model.taskCount() + " مهمة";
        }
        pageLabel.setText(label);
        prevBtn.setAlpha(pageIndex > 0 ? 1f : 0.35f);
        prevBtn.setEnabled(pageIndex > 0);
        nextBtn.setAlpha(pageIndex < n - 1 ? 1f : 0.35f);
        nextBtn.setEnabled(pageIndex < n - 1);
    }

    private void updateQuizUi() {
        boolean q = StudioStore.BOOK_QA.equals(type) && model.quiz;
        quizBar.setVisibility(q ? View.VISIBLE : View.GONE);
        addBar.setVisibility(q ? View.GONE : View.VISIBLE);
        if (quizBtn != null) Ui.setButtonIcon(quizBtn, q ? R.drawable.ic_eye_off : R.drawable.ic_eye);
    }

    // ------------------------------------------------------------------ page navigation and the page-turn animation

    private void goToPage(final int target, boolean animate) {
        if (target < 0 || target >= model.pages.size() || target == pageIndex || flipping) return;
        hideKeyboard();
        final boolean forward = target > pageIndex;
        pageIndex = target;
        if (!animate) {
            renderPage();
            return;
        }
        flipping = true;
        boolean rtl = frame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final float swing = rtl ? 78f : -78f;
        frame.setCameraDistance(9000f * ctx.act.getResources().getDisplayMetrics().density);
        frame.setPivotX(rtl ? frame.getWidth() : 0f);
        frame.setPivotY(frame.getHeight() / 2f);
        if (forward) {
            // the page lifts and swings away around the binding, then the next page settles in
            frame.animate().rotationY(swing).alpha(0.2f).setDuration(190)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        renderPage();
                        frame.setRotationY(0f);
                        frame.setAlpha(0f);
                        frame.animate().alpha(1f).setDuration(180).setInterpolator(new DecelerateInterpolator())
                                .withEndAction(() -> flipping = false).start();
                    }).start();
        } else {
            // the previous page swings back in around the binding
            frame.animate().alpha(0f).setDuration(110)
                    .withEndAction(() -> {
                        renderPage();
                        frame.setRotationY(swing);
                        frame.animate().alpha(1f).rotationY(0f).setDuration(270)
                                .setInterpolator(new DecelerateInterpolator())
                                .withEndAction(() -> flipping = false).start();
                    }).start();
        }
    }

    private void addPage() {
        if (flipping) return;
        hideKeyboard();
        int at = model.addPageAfter(pageIndex);
        markDirty();
        saveNow();
        // jump to the new page with the same turn animation
        goToPage(at, true);
    }

    private void jumpToPage() {
        int n = model.pages.size();
        if (n <= 1) return;
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = "صفحة " + (i + 1) + (i == pageIndex ? "  (الحالية)" : "");
        ctx.choose("الانتقال إلى صفحة", labels, which -> goToPage(which, true));
    }

    private void confirmDeletePage() {
        final BookModel.Page p = model.page(pageIndex);
        boolean any = false;
        for (BookModel.Block b : p.blocks) if (b.hasContent()) any = true;
        final Runnable doDelete = () -> {
            List<String> images = model.removePage(pageIndex);
            if (!images.isEmpty()) ctx.store.deleteItems(images);
            if (pageIndex >= model.pages.size()) pageIndex = model.pages.size() - 1;
            markDirty();
            saveNow();
            renderPage();
        };
        if (!any && model.pages.size() > 1) {
            doDelete.run();
            return;
        }
        String msg = model.pages.size() == 1
                ? "ستُفرَّغ هذه الصفحة (آخر صفحة في الدفتر) مع صورها."
                : "ستُحذف الصفحة " + (pageIndex + 1) + " بما فيها وصورها نهائياً.";
        ctx.confirm("حذف الصفحة", msg, "حذف", doDelete);
    }

    private void hideKeyboard() {
        View f = ctx.act.getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) ctx.act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (f != null && imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        if (f != null) f.clearFocus();
    }

    // ------------------------------------------------------------------ rendering a page

    private void renderPage() {
        col.removeAllViews();
        editors.clear();
        BookModel.Page p = model.page(pageIndex);
        boolean quiz = StudioStore.BOOK_QA.equals(type) && model.quiz;
        if (p.blocks.isEmpty()) {
            col.addView(emptyHint(quiz), lp(-1, -2, 0, 40, 0, 0));
        }
        for (BookModel.Block b : new ArrayList<>(p.blocks)) {
            col.addView(quiz ? quizBlock(b) : blockView(p, b));
        }
        updateNav();
        final String focus = pendingFocus;
        final String scrollTo = pendingScroll;
        pendingFocus = null;
        pendingScroll = null;
        if (scrollTo != null) {
            final View target = col.findViewWithTag("blk:" + scrollTo);
            if (target != null) {
                scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(40))));
            }
        }
        if (focus != null) {
            final EditText e = editors.get(focus);
            if (e != null) {
                e.post(() -> {
                    e.requestFocus();
                    InputMethodManager imm = (InputMethodManager) ctx.act.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
                });
            }
        }
    }

    private View emptyHint(boolean quiz) {
        String s;
        if (quiz) s = "لا توجد أسئلة في هذه الصفحة.";
        else if (StudioStore.BOOK_QA.equals(type)) s = "صفحة فارغة. اضغط «إضافة سؤال» لتبدأ بـ س١.";
        else if (StudioStore.BOOK_PLANNER.equals(type)) s = "صفحة فارغة. اضغط «مهمة» لإضافة أول مهمة.";
        else if (StudioStore.BOOK_STUDY.equals(type)) s = "صفحة فارغة. ابدأ بعنوان المحاضرة.";
        else s = "صفحة فارغة. اضغط «نص» وابدأ الكتابة.";
        TextView t = Ui.centered(Ui.text(ctx.act, s, 14, INK_SOFT, false));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private interface Setter {
        void set(String s);
    }

    private LinedEditText paperEdit(String hint, float sp, boolean bold) {
        Context c = ctx.act;
        LinedEditText e = new LinedEditText(c);
        e.setHint(hint);
        e.setHintTextColor(INK_SOFT);
        e.setTextColor(INK);
        e.setTextSize(sp);
        e.setTypeface(Ui.arabic(c, bold));
        e.setLineSpacing(dp(9), 1f);
        e.setBackground(null);
        e.setPadding(dp(2), dp(2), dp(2), dp(2));
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setMinLines(1);
        e.setMaxLines(Integer.MAX_VALUE);
        e.setHorizontallyScrolling(false);
        e.setFilters(new android.text.InputFilter[0]);
        e.setHighlightColor(0x5575416F);
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cur = new GradientDrawable();
            cur.setColor(LABEL_Q);
            cur.setSize(dp(2), dp(22));
            e.setTextCursorDrawable(cur);
        }
        return e;
    }

    private void bind(final EditText e, String initial, final Setter setter) {
        e.setText(initial);
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                setter.set(s.toString());
                markDirty();
            }
        });
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx.act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private TextView label(String s, float sp, int color) {
        TextView t = Ui.text(ctx.act, s, sp, color, true);
        t.setMinWidth(dp(40));
        t.setPadding(0, dp(2), dp(4), 0);
        t.setLineSpacing(dp(9), 1f);
        return t;
    }

    private View toolPill(int icon, String text, View.OnClickListener l) {
        Context c = ctx.act;
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(12), 0, dp(14), 0);
        b.setBackground(Ui.ripple(c, Ui.rounded(c, 0x1A75416F, 0x4475416F, 1, 20), 20));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(16), dp(16));
        il.setMarginEnd(dp(6));
        b.addView(Ui.icon(c, icon, TOOL, 16), il);
        b.addView(Ui.text(c, text, 12, TOOL, true), new LinearLayout.LayoutParams(-2, -2));
        b.setOnClickListener(l);
        Ui.asButton(b);
        Ui.press(b);
        b.setMinimumHeight(dp(40));
        return b;
    }

    private View toolIcon(int icon, String desc, boolean enabled, View.OnClickListener l) {
        Context c = ctx.act;
        FrameLayout f = new FrameLayout(c);
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription(desc);
        f.setBackground(Ui.ripple(c, new ColorDrawable(Color.TRANSPARENT), 20));
        f.addView(Ui.icon(c, icon, TOOL, 20), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        f.setAlpha(enabled ? 1f : 0.3f);
        f.setEnabled(enabled);
        f.setOnClickListener(l);
        Ui.asButton(f);
        Ui.press(f);
        return f;
    }

    private View moreButton(final BookModel.Page p, final BookModel.Block b) {
        return toolIcon(R.drawable.ic_more, "خيارات", true, v -> blockMenu(p, b));
    }

    // ---- block views (editing mode)

    private View blockView(final BookModel.Page p, final BookModel.Block b) {
        View v;
        if (BookModel.T_QA.equals(b.type)) v = qaView(p, b);
        else if (BookModel.T_TASK.equals(b.type)) v = taskView(p, b);
        else if (BookModel.T_IMAGE.equals(b.type)) v = imageView(p, b, true);
        else if (BookModel.T_HEAD.equals(b.type)) v = headView(p, b);
        else if (BookModel.T_KEY.equals(b.type)) v = keyView(p, b);
        else v = textView(p, b);
        v.setTag("blk:" + b.id);
        return v;
    }

    private View textView(BookModel.Page p, final BookModel.Block b) {
        LinearLayout r = row();
        LinedEditText e = paperEdit("اكتب هنا…", 16, false);
        bind(e, b.text, s -> b.text = s);
        editors.put(b.id, e);
        r.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(moreButton(p, b), new LinearLayout.LayoutParams(dp(40), dp(40)));
        r.setPadding(0, 0, 0, dp(10));
        return r;
    }

    private View headView(BookModel.Page p, final BookModel.Block b) {
        LinearLayout r = row();
        LinedEditText e = paperEdit("العنوان", 21, true);
        e.setTextColor(0xFF4B1F4A);
        bind(e, b.text, s -> b.text = s);
        editors.put(b.id, e);
        r.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(moreButton(p, b), new LinearLayout.LayoutParams(dp(40), dp(40)));
        r.setPadding(0, dp(6), 0, dp(10));
        return r;
    }

    private View keyView(BookModel.Page p, final BookModel.Block b) {
        Context c = ctx.act;
        LinearLayout r = row();
        r.setGravity(Gravity.TOP);
        r.setPadding(dp(10), dp(8), dp(2), dp(8));
        r.setBackground(Ui.rounded(c, 0x1F9A4A72, 0x669A4A72, 1, 12));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(20), dp(20));
        il.setMarginEnd(dp(8));
        il.topMargin = dp(5);
        r.addView(Ui.icon(c, R.drawable.ic_star, LABEL_A, 20), il);
        LinedEditText e = paperEdit("نقطة مهمة…", 16, true);
        bind(e, b.text, s -> b.text = s);
        editors.put(b.id, e);
        r.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(moreButton(p, b), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, 0, 0, dp(12));
        wrap.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private View taskView(BookModel.Page p, final BookModel.Block b) {
        Context c = ctx.act;
        LinearLayout r = row();
        r.setGravity(Gravity.TOP);
        final FrameLayout box = new FrameLayout(c);
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription("إنجاز المهمة");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(28), dp(28));
        bl.setMarginEnd(dp(10));
        bl.topMargin = dp(2);
        final ImageView tick = Ui.icon(c, R.drawable.ic_check, Color.WHITE, 18);
        box.addView(tick, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        final LinedEditText e = paperEdit("اكتب المهمة…", 16, false);
        final Runnable style = () -> {
            box.setBackground(Ui.rounded(c, b.done ? LABEL_Q : 0x00000000, LABEL_Q, 2, 8));
            tick.setVisibility(b.done ? View.VISIBLE : View.INVISIBLE);
            e.setAlpha(b.done ? 0.55f : 1f);
            if (b.done) e.setPaintFlags(e.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            else e.setPaintFlags(e.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        };
        bind(e, b.text, s -> b.text = s);
        style.run();
        box.setOnClickListener(v -> {
            b.done = !b.done;
            style.run();
            Ui.pop(v);
            markDirty();
            updateNav();
        });
        editors.put(b.id, e);
        r.addView(box, bl);
        r.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(moreButton(p, b), new LinearLayout.LayoutParams(dp(40), dp(40)));
        r.setPadding(0, 0, 0, dp(8));
        return r;
    }

    private View qaView(final BookModel.Page p, final BookModel.Block b) {
        Context c = ctx.act;
        int num = model.questionNumber(b);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(16));

        LinearLayout q = row();
        q.addView(label("س" + num + ".", 17, LABEL_Q));
        LinedEditText qe = paperEdit("اكتب السؤال هنا", 17, true);
        bind(qe, b.text, s -> b.text = s);
        editors.put(b.id, qe);
        q.addView(qe, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(q, new LinearLayout.LayoutParams(-1, -2));

        for (int k = 0; k < b.lines.size(); k++) {
            final int idx = k;
            LinearLayout a = row();
            TextView al = label(k == 0 ? "ج" + num + "." : "", 16, LABEL_A);
            a.addView(al);
            LinedEditText ae = paperEdit("اكتب الإجابة هنا", 16, false);
            bind(ae, b.lines.get(k), s -> b.lines.set(idx, s));
            editors.put(b.id + "#" + k, ae);
            a.addView(ae, new LinearLayout.LayoutParams(0, -2, 1f));
            if (k > 0) {
                a.addView(toolIcon(R.drawable.ic_close, "حذف سطر الإجابة", true, v -> {
                    if (BookModel.removeAnswerLine(b, idx)) {
                        markDirty();
                        renderPage();
                    }
                }), new LinearLayout.LayoutParams(dp(40), dp(40)));
            }
            box.addView(a, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout tools = row();
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(toolPill(R.drawable.ic_plus, "سطر للإجابة", v -> {
            BookModel.addAnswerLine(b);
            markDirty();
            pendingFocus = b.id + "#" + (b.lines.size() - 1);
            pendingScroll = b.id;
            renderPage();
        }), new LinearLayout.LayoutParams(-2, dp(40)));
        View spacer = new View(c);
        tools.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        int pos = p.blocks.indexOf(b);
        tools.addView(toolIcon(R.drawable.ic_up, "نقل السؤال للأعلى", pos > 0, v -> moveBlock(p, b, -1)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        tools.addView(toolIcon(R.drawable.ic_down, "نقل السؤال للأسفل", pos < p.blocks.size() - 1, v -> moveBlock(p, b, 1)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        tools.addView(toolIcon(R.drawable.ic_trash, "حذف السؤال", true, v -> deleteBlock(p, b)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.setMarginStart(dp(40));
        box.addView(tools, tl);
        return box;
    }

    private View imageView(final BookModel.Page p, final BookModel.Block b, boolean editable) {
        final Context c = ctx.act;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(14));
        final StudioStore.Item it = ctx.store.item(b.itemId);
        if (it == null) {
            box.addView(Ui.text(c, "الصورة غير متاحة (ربما حُذفت من الأستوديو).", 13, INK_SOFT, false));
        } else {
            FrameLayout holder = new FrameLayout(c);
            holder.setBackground(Ui.rounded(c, 0xFFE3D9E5, 0x55422B4C, 1, 10));
            holder.setPadding(dp(4), dp(4), dp(4), dp(4));
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setContentDescription(b.text.isEmpty() ? "صورة" : b.text);
            final float radius = dp(8);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            iv.setClipToOutline(true);
            StudioMedia.load(ctx.store, it, iv, 900, null);
            iv.setOnClickListener(v -> {
                List<StudioStore.Item> one = new ArrayList<>();
                one.add(it);
                ctx.openViewer(one, 0, true, () -> { });
            });
            holder.addView(iv, new FrameLayout.LayoutParams(-1, dp(210)));
            box.addView(holder, new LinearLayout.LayoutParams(-1, -2));
        }
        if (editable) {
            LinearLayout r = row();
            LinedEditText e = paperEdit("وصف الصورة (اختياري)", 14, false);
            bind(e, b.text, s -> b.text = s);
            editors.put(b.id, e);
            r.addView(e, new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(moreButton(p, b), new LinearLayout.LayoutParams(dp(40), dp(40)));
            box.addView(r, new LinearLayout.LayoutParams(-1, -2));
        } else if (!b.text.isEmpty()) {
            box.addView(Ui.text(c, b.text, 13, INK_SOFT, false));
        }
        return box;
    }

    // ---- quiz mode (read-only review)

    private View quizBlock(final BookModel.Block b) {
        View v;
        if (BookModel.T_QA.equals(b.type)) {
            final LinearLayout card = new LinearLayout(ctx.act);
            card.setOrientation(LinearLayout.VERTICAL);
            fillQuizCard(card, b);
            v = card;
        } else if (BookModel.T_IMAGE.equals(b.type)) {
            v = imageView(null, b, false);
        } else {
            TextView t = Ui.text(ctx.act, b.text, BookModel.T_HEAD.equals(b.type) ? 19 : 15, INK, BookModel.T_HEAD.equals(b.type));
            t.setPadding(0, 0, 0, dp(12));
            t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            v = t;
        }
        v.setTag("blk:" + b.id);
        return v;
    }

    private void fillQuizCard(final LinearLayout card, final BookModel.Block b) {
        Context c = ctx.act;
        card.removeAllViews();
        card.setPadding(dp(12), dp(12), dp(8), dp(12));
        card.setBackground(Ui.rounded(c, 0x1475416F, 0x3375416F, 1, 14));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.bottomMargin = dp(12);
        card.setLayoutParams(cl);
        int num = model.questionNumber(b);

        LinearLayout q = row();
        q.addView(label("س" + num + ".", 17, LABEL_Q));
        TextView qt = Ui.text(c, b.text.trim().isEmpty() ? "(سؤال فارغ)" : b.text, 17, INK, true);
        qt.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        q.addView(qt, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(q, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, dp(40));
        bl.topMargin = dp(8);
        bl.setMarginStart(dp(40));
        if (revealed.contains(b.id)) {
            LinearLayout a = row();
            a.addView(label("ج" + num + ".", 16, LABEL_A));
            String ans = BookModel.hasAnswer(b) ? BookModel.answerText(b) : "لم تُكتب إجابة لهذا السؤال.";
            TextView at = Ui.text(c, ans, 16, BookModel.hasAnswer(b) ? INK : INK_SOFT, false);
            at.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            a.addView(at, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(a, lp(-1, -2, 0, 8, 0, 0));
            Ui.reveal(a, 0);
            card.addView(toolPill(R.drawable.ic_eye_off, "إخفاء الإجابة", v -> {
                revealed.remove(b.id);
                fillQuizCard(card, b);
            }), bl);
        } else {
            card.addView(toolPill(R.drawable.ic_eye, "إظهار الإجابة", v -> {
                revealed.add(b.id);
                fillQuizCard(card, b);
            }), bl);
        }
    }

    // ------------------------------------------------------------------ editing actions

    private void addBlock(String blockType) {
        if (flipping) return;
        BookModel.Page p = model.page(pageIndex);
        BookModel.Block b = BookModel.newBlock(blockType);
        p.blocks.add(b);
        markDirty();
        pendingFocus = b.id;
        pendingScroll = b.id;
        renderPage();
    }

    private void moveBlock(BookModel.Page p, BookModel.Block b, int delta) {
        if (model.move(p, b, delta)) {
            markDirty();
            pendingScroll = b.id;
            renderPage();
        }
    }

    private void deleteBlock(final BookModel.Page p, final BookModel.Block b) {
        final Runnable doDelete = () -> {
            p.blocks.remove(b);
            if (!b.itemId.isEmpty()) {
                List<String> ids = new ArrayList<>();
                ids.add(b.itemId);
                ctx.store.deleteItems(ids);
            }
            markDirty();
            saveNow();
            renderPage();
        };
        if (!b.hasContent()) {
            doDelete.run();
            return;
        }
        String what = BookModel.T_QA.equals(b.type) ? "السؤال وإجابته" : BookModel.T_IMAGE.equals(b.type) ? "الصورة" : "هذا العنصر";
        ctx.confirm("حذف", "سيُحذف " + what + " نهائياً.", "حذف", doDelete);
    }

    private void blockMenu(final BookModel.Page p, final BookModel.Block b) {
        final int pos = p.blocks.indexOf(b);
        List<String> labels = new ArrayList<>();
        final List<Integer> actions = new ArrayList<>();
        if (pos > 0) {
            labels.add("نقل للأعلى");
            actions.add(0);
        }
        if (pos < p.blocks.size() - 1) {
            labels.add("نقل للأسفل");
            actions.add(1);
        }
        labels.add("حذف");
        actions.add(2);
        ctx.choose("خيارات", labels.toArray(new String[0]), which -> {
            int a = actions.get(which);
            if (a == 0) moveBlock(p, b, -1);
            else if (a == 1) moveBlock(p, b, 1);
            else deleteBlock(p, b);
        });
    }

    private void toggleQuiz() {
        model.quiz = !model.quiz;
        revealed.clear();
        hideKeyboard();
        markDirty();
        saveNow();
        updateQuizUi();
        renderPage();
        ctx.toast(model.quiz ? "وضع الاختبار: الإجابات مخفية" : "تم إيقاف وضع الاختبار");
    }

    // ------------------------------------------------------------------ images

    private void addImages() {
        final BookModel.Page target = model.page(pageIndex);
        final String tag = "bk:" + target.id;
        ctx.host.pickFiles(new String[] {"image/*"}, true, (List<Uri> uris) -> {
            if (uris == null || uris.isEmpty()) return;
            ctx.importPicked(uris, StudioStore.SCOPE_MEDICAL, "", notebookId, tag, "image", () -> attachImported(target, tag));
        });
    }

    private void attachImported(BookModel.Page target, String tag) {
        if (closed || deleted || !model.pages.contains(target)) return;
        Set<String> used = new HashSet<>();
        for (BookModel.Page pg : model.pages) for (BookModel.Block b : pg.blocks) if (!b.itemId.isEmpty()) used.add(b.itemId);
        List<StudioStore.Item> items = ctx.store.medicalImages(null, tag);
        BookModel.Block last = null;
        // the store lists the newest first; add them in the order they were picked
        for (int i = items.size() - 1; i >= 0; i--) {
            StudioStore.Item it = items.get(i);
            if (used.contains(it.id) || !StudioStore.KIND_IMAGE.equals(it.kind)) continue;
            BookModel.Block b = BookModel.newBlock(BookModel.T_IMAGE);
            b.itemId = it.id;
            target.blocks.add(b);
            last = b;
        }
        if (last == null) return;
        markDirty();
        saveNow();
        if (model.pages.indexOf(target) == pageIndex) {
            pendingScroll = last.id;
            renderPage();
        }
    }

    // ------------------------------------------------------------------ notebook menu

    private void openMenu() {
        final boolean hasLegacy = legacy != null && ctx.store.noteCount(notebookId) > 0;
        List<String> labels = new ArrayList<>();
        labels.add("إعادة تسمية الدفتر");
        labels.add("تغيير لون الغلاف");
        if (hasLegacy) labels.add("الملاحظات والصور السابقة");
        labels.add("حذف الدفتر");
        final int n = labels.size();
        ctx.choose(name, labels.toArray(new String[0]), which -> {
            if (which == 0) rename();
            else if (which == 1) chooseCover();
            else if (which == n - 1) confirmDeleteNotebook();
            else if (hasLegacy) {
                saveNow();
                legacy.open(() -> {
                    ctx.setFlushHook(this::saveNow);
                    StudioStore.Notebook now = ctx.store.notebook(notebookId);
                    if (now == null) {
                        // the notebook was deleted from the old page: close this book without saving anything
                        deleted = true;
                        handler.removeCallbacks(saveTask);
                        ctx.host.pop();
                        return;
                    }
                    name = now.name;
                    StudioContext.setHeader(header, R.drawable.ic_notebook, name);
                    renderPage();
                });
            }
        });
    }

    private boolean nameTaken(String text) {
        String key = StudioStore.clean(text);
        for (StudioStore.Notebook x : ctx.store.notebooks()) {
            if (!x.id.equals(notebookId) && x.name.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private void rename() {
        ctx.prompt("إعادة تسمية الدفتر", "اسم الدفتر", name, "حفظ", text -> {
            ctx.store.renameNotebook(notebookId, text);
            name = text;
            StudioContext.setHeader(header, R.drawable.ic_notebook, name);
        }, text -> nameTaken(text) ? "يوجد دفتر بهذا الاسم" : null);
    }

    private void chooseCover() {
        ctx.choose("لون الغلاف", COVER_NAMES, which -> {
            ctx.store.setNotebookCover(notebookId, which);
            ctx.toast("تم تغيير الغلاف");
        });
    }

    private void confirmDeleteNotebook() {
        int notes = ctx.store.noteCount(notebookId);
        int images = ctx.store.medicalImages(notebookId, null).size();
        ctx.confirm("حذف الدفتر", "سيُحذف الدفتر «" + name + "» بكل صفحاته و" + notes + " ملاحظة و" + images
                + " صورة بداخله نهائياً.", "حذف", () -> {
            deleted = true;
            handler.removeCallbacks(saveTask);
            ctx.store.deleteNotebook(notebookId);
            ctx.toast("تم حذف الدفتر");
            ctx.host.pop();
        });
    }
}
