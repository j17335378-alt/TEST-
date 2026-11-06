package com.hajala.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared look-and-feel helpers: colors, press animation, buttons, chips, dialogs. */
final class Ui {
    // ARCANUM palette (gothic / dark fantasy)
    static final int BG = 0xFF100B13;        // near-black violet: main background
    static final int SURFACE = 0xFF200B2B;   // deep violet: secondary surfaces, inputs, bars
    static final int CARD = 0xFF351343;      // dark violet: cards
    static final int ACCENT = 0xFF75416F;    // muted violet: primary buttons, strokes
    static final int LAV = 0xFFC39AB8;       // pale lavender: highlights, active states
    static final int TEXT = 0xFFE8DDE8;      // grey-white text
    static final int MUTED = 0xFFA990A6;     // secondary text
    static final int BORDER = 0xFF4B2A4F;    // faint violet frame
    static final int OUTLINE = 0xFF75416F;   // outlines of secondary buttons
    static final int ERROR = 0xFFD98BA0;     // soft rose, never neon
    static final int ON_LAV = 0xFF100B13;    // text/icons on a lavender fill
    static final int RIPPLE = 0x44C39AB8;    // lavender touch ripple
    static final int SCRIM = 0xFF100B13;     // base colour of the dark gradients over covers

    /** Background thread pool for decoding images. */
    static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- drawables

    static GradientDrawable rounded(Context c, int fill, int stroke, float strokeDp, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) d.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return d;
    }

    static GradientDrawable dashed(Context c, int fill, int stroke, float strokeDp, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, strokeDp)), stroke, dp(c, 6), dp(c, 4));
        return d;
    }

    /** Wraps [content] in a touch ripple clipped to a rounded rectangle. */
    static Drawable ripple(Context c, Drawable content, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(c, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, mask);
    }

    // ---------------------------------------------------------------- press animation

    /**
     * Gives a view a tactile press animation: it shrinks while pressed and springs back on release.
     * The touch listener returns false so normal click handling keeps working.
     */
    static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    view.animate().scaleX(1f).scaleY(1f).setDuration(240)
                            .setInterpolator(new OvershootInterpolator(2f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Makes screen readers announce the view as a button. */
    static void asButton(View v) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
    }

    // ---------------------------------------------------------------- fonts

    private static final Typeface[] FACES = new Typeface[7];
    private static final String[] FACE_FILES = {
            "fonts/tajawal_regular.ttf", "fonts/tajawal_medium.ttf", "fonts/tajawal_bold.ttf",
            "fonts/inter_regular.ttf", "fonts/inter_semibold.ttf",
            "fonts/cinzel_bold.ttf", "fonts/cinzel_semibold.ttf"};

    private static Typeface face(Context c, int i) {
        if (FACES[i] == null) {
            try {
                FACES[i] = Typeface.createFromAsset(c.getApplicationContext().getAssets(), FACE_FILES[i]);
            } catch (RuntimeException e) {
                FACES[i] = (i >= 5) ? Typeface.create("serif", Typeface.BOLD) : Typeface.DEFAULT;
            }
        }
        return FACES[i];
    }

    /** True when the text has Latin letters and no Arabic (e.g. "MangaVerse"), so Inter fits better than Tajawal. */
    private static boolean isLatinOnly(CharSequence s) {
        boolean latin = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x0600 && ch <= 0x06FF) return false;
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) latin = true;
        }
        return latin;
    }

    /**
     * Body font: Tajawal is the default (Arabic, digits, empty text that is filled in later); Inter is used only
     * for text made of Latin letters without any Arabic.
     */
    static Typeface bodyFace(Context c, CharSequence s, boolean bold) {
        if (isLatinOnly(s)) return face(c, bold ? 4 : 3);
        return face(c, bold ? 2 : 0);
    }

    /** Arabic UI font in the given weight. */
    static Typeface arabic(Context c, boolean bold) {
        return face(c, bold ? 2 : 0);
    }

    /** Applies the right body font to a text view according to its current text. */
    static void applyFont(TextView t, boolean bold) {
        t.setTypeface(bodyFace(t.getContext(), t.getText(), bold));
    }

    /** Royal display face (Cinzel) for English titles and the ARCANUM name. */
    static Typeface display(Context c, boolean heavy) {
        return face(c, heavy ? 5 : 6);
    }

    // ---------------------------------------------------------------- views

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bodyFace(c, s, bold));
        t.setLineSpacing(dp(c, 3), 1f);
        // Align to the start of the *view* (right in RTL) even for Latin text such as "MangaVerse".
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    /** The app name, "ARCANUM", in Cinzel with wide letter spacing. */
    static TextView wordmarkText(Context c, float sp, int color) {
        TextView t = text(c, "ARCANUM", sp, color, false);
        t.setTypeface(display(c, true));
        t.setLetterSpacing(0.18f);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    /** English section title (e.g. "MangaVerse") in Cinzel. */
    static TextView displayText(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color, false);
        t.setTypeface(display(c, false));
        t.setLetterSpacing(0.08f);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Centers a text view's text (gravity and alignment). */
    static <T extends TextView> T centered(T t) {
        t.setGravity(Gravity.CENTER);
        t.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        return t;
    }

    static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setMinimumWidth(dp(c, sizeDp));
        iv.setMinimumHeight(dp(c, sizeDp));
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return iv;
    }

    /** The raven emblem from the logo (use sparingly: splash, loading, empty pages). */
    static ImageView crow(Context c, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(R.drawable.arcanum_emblem);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    /** A soft violet glow behind a view: only on Android 9+, where coloured shadows exist. */
    static void glow(View v, float elevationDp, int color) {
        v.setElevation(dp(v.getContext(), elevationDp));
        if (Build.VERSION.SDK_INT >= 28) {
            v.setOutlineSpotShadowColor(color);
            v.setOutlineAmbientShadowColor(color);
        }
    }

    /** A full-width action button: optional icon + label, ripple, and press animation. */
    static LinearLayout button(Context c, String label, int iconRes, boolean primary) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        int fg = primary ? TEXT : LAV;
        if (iconRes != 0) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22));
            ip.setMarginEnd(dp(c, 10));
            b.addView(icon(c, iconRes, fg, 22), ip);
        }
        TextView t = centered(text(c, label, 16, fg, true));
        b.addView(t, new LinearLayout.LayoutParams(-2, -2));
        GradientDrawable bg = primary
                ? rounded(c, ACCENT, 0x99C39AB8, 1, 16)
                : rounded(c, Color.TRANSPARENT, OUTLINE, 1, 16);
        b.setBackground(ripple(c, bg, 16));
        if (primary) glow(b, 3, ACCENT);
        asButton(b);
        press(b);
        return b;
    }

    /** Round icon-only button (used for "back"). */
    static FrameLayout iconButton(Context c, int res, String description) {
        FrameLayout f = new FrameLayout(c);
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(SURFACE);
        bg.setStroke(dp(c, 1), BORDER);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        f.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), bg, mask));
        f.addView(icon(c, res, LAV, 22), new FrameLayout.LayoutParams(dp(c, 22), dp(c, 22), Gravity.CENTER));
        asButton(f);
        press(f);
        return f;
    }

    /**
     * Round translucent "glass" button for icons placed over photos and video. [sizeDp] is the touch target,
     * [iconDp] the drawn icon, [tint] the icon colour.
     */
    static FrameLayout glassButton(Context c, int res, String description, int sizeDp, int iconDp, int tint) {
        FrameLayout f = new FrameLayout(c);
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99100B13);
        bg.setStroke(Math.max(1, dp(c, 1)), 0x5575416F);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        f.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), bg, mask));
        f.addView(icon(c, res, tint, iconDp), new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER));
        f.setMinimumWidth(dp(c, sizeDp));
        f.setMinimumHeight(dp(c, sizeDp));
        asButton(f);
        press(f);
        return f;
    }

    /** Swaps the icon of a button made by [iconButton] or [glassButton] (the first child is the icon). */
    static void setButtonIcon(View button, int res) {
        if (button instanceof FrameLayout && ((FrameLayout) button).getChildCount() > 0) {
            View v = ((FrameLayout) button).getChildAt(0);
            if (v instanceof ImageView) ((ImageView) v).setImageResource(res);
        }
    }

    // ---------------------------------------------------------------- folder icons (no emoji)

    /**
     * Folders keep storing their icon as the same short string as before (so nothing already saved changes), but it
     * is never drawn as text: it is only a key that selects one of the app's own vector icons.
     */
    static final String[] FOLDER_ICON_KEYS = {
            "\uD83D\uDCC1", "\uD83D\uDDBC", "\uD83C\uDFAC", "\uD83D\uDCDA", "\uD83D\uDCDD", "\uD83D\uDD2E",
            "\uD83C\uDF19", "\uD83D\uDD6F", "\u2764", "\uD83D\uDC8A", "\uD83D\uDD2C", "\uD83C\uDFB5"};
    private static final int[] FOLDER_ICON_RES = {
            R.drawable.ic_folder, R.drawable.ic_image, R.drawable.ic_film, R.drawable.ic_books, R.drawable.ic_note,
            R.drawable.ic_orb, R.drawable.ic_crescent, R.drawable.ic_flame, R.drawable.ic_heart, R.drawable.ic_pill,
            R.drawable.ic_flask, R.drawable.ic_music};

    static int folderIconRes(String key) {
        if (key != null) {
            // tolerate a variation selector that some keyboards append (e.g. the heart)
            String k = key.replace("\uFE0F", "");
            for (int i = 0; i < FOLDER_ICON_KEYS.length; i++) {
                if (FOLDER_ICON_KEYS[i].equals(k)) return FOLDER_ICON_RES[i];
            }
        }
        return R.drawable.ic_folder;
    }

    /** A title row: a small vector icon followed by text, for dialogs and list labels that used to start with an emoji. */
    static LinearLayout iconLabel(Context c, int res, String label, float sp, int color, boolean bold) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20));
        ip.setMarginEnd(dp(c, 10));
        row.addView(icon(c, res, color, 20), ip);
        TextView t = text(c, label, sp, color, bold);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    // ---------------------------------------------------------------- shared animation vocabulary

    static final int ANIM_FAST = 160;
    static final int ANIM_BASE = 220;

    /**
     * Fades a view in or out with a small vertical slide. The view is only made GONE after the fade ends, and only if
     * nobody asked to show it again in the meantime, so rapid show/hide taps can never leave it stuck.
     */
    static void fadeVisible(final View v, final boolean show, float slideDp) {
        v.animate().cancel();
        float dy = dp(v.getContext(), slideDp);
        if (show) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setTranslationY(dy);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).translationY(0f).setDuration(ANIM_BASE)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            v.animate().alpha(0f).translationY(dy).setDuration(ANIM_FAST)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> {
                        v.setVisibility(View.GONE);
                        v.setTranslationY(0f);
                    }).start();
        }
    }

    static TextView chip(Context c, String label, boolean selected) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        t.setMinHeight(dp(c, 44));
        t.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        styleChip(c, t, selected);
        asButton(t);
        press(t);
        return t;
    }

    /** Small springy "pop" played on a chip when it becomes selected. */
    static void pop(View v) {
        v.animate().cancel();
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(ANIM_BASE + 60)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
    }

    static void styleChip(Context c, TextView t, boolean selected) {
        t.setTextColor(selected ? ON_LAV : TEXT);
        applyFont(t, selected);
        t.setBackground(ripple(c, rounded(c, selected ? LAV : SURFACE, selected ? LAV : BORDER, 1, 22), 22));
        t.setSelected(selected);
    }

    /** The "+ تصنيف" chip: dashed outline, used to add a custom category. */
    static TextView addChip(Context c, String label) {
        TextView t = new TextView(c);
        // a vector plus replaces any leading "+" typed in the label
        t.setText(label.startsWith("+") ? label.substring(1).trim() : label);
        Drawable plus = c.getDrawable(R.drawable.ic_plus).mutate();
        plus.setTint(LAV);
        plus.setBounds(0, 0, dp(c, 16), dp(c, 16));
        t.setCompoundDrawablesRelative(plus, null, null, null);
        t.setCompoundDrawablePadding(dp(c, 6));
        t.setTextSize(14);
        applyFont(t, true);
        t.setTextColor(LAV);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        t.setMinHeight(dp(c, 44));
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        t.setBackground(ripple(c, dashed(c, Color.TRANSPARENT, ACCENT, 1.5f, 22), 22));
        asButton(t);
        press(t);
        return t;
    }

    static TextView pill(Context c, String label, int fg, int fill, int stroke) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(11);
        applyFont(t, true);
        t.setTextColor(fg);
        t.setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3));
        t.setBackground(rounded(c, fill, stroke, stroke == 0 ? 0 : 1, 10));
        return t;
    }

    static EditText input(Context c, String hint, boolean multiLine) {
        final EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(15);
        e.setTypeface(arabic(c, false));
        e.setHighlightColor(0x66C39AB8);
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setBackground(rounded(c, SURFACE, BORDER, 1, 14));
        e.setOnFocusChangeListener((v, hasFocus) ->
                v.setBackground(rounded(c, SURFACE, hasFocus ? LAV : BORDER, hasFocus ? 1.5f : 1, 14)));
        if (multiLine) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setMaxLines(6);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setSingleLine(true);
            e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        }
        return e;
    }

    // ---------------------------------------------------------------- decoration

    /** A thin ornamental divider: a line that fades out on both sides with a small diamond in the middle. */
    static final class Ornament extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path diamond = new Path();

        Ornament(Context c) {
            super(c);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(getContext(), 14));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float cy = getHeight() / 2f;
            float cx = w / 2f;
            float gap = dp(getContext(), 10);
            float line = Math.max(1f, dp(getContext(), 1) * 0.8f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(line);
            paint.setShader(new LinearGradient(0, 0, cx - gap, 0, 0x0075416F, ACCENT, Shader.TileMode.CLAMP));
            cv.drawLine(0, cy, cx - gap, cy, paint);
            paint.setShader(new LinearGradient(cx + gap, 0, w, 0, ACCENT, 0x0075416F, Shader.TileMode.CLAMP));
            cv.drawLine(cx + gap, cy, w, cy, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(LAV);
            float r = dp(getContext(), 4);
            diamond.reset();
            diamond.moveTo(cx, cy - r);
            diamond.lineTo(cx + r * 0.7f, cy);
            diamond.lineTo(cx, cy + r);
            diamond.lineTo(cx - r * 0.7f, cy);
            diamond.close();
            cv.drawPath(diamond, paint);
        }
    }

    static View ornament(Context c) {
        return new Ornament(c);
    }

    /** Section heading: a small crescent, the title, and a hairline that fades toward the end. */
    static LinearLayout sectionTitle(Context c, String label) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16));
        ip.setMarginEnd(dp(c, 8));
        row.addView(icon(c, R.drawable.ic_crescent, ACCENT, 16), ip);
        row.addView(text(c, label, 19, LAV, true), new LinearLayout.LayoutParams(-2, -2));
        View line = new View(c);
        line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x0075416F, 0xAA75416F}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 1), 1f);
        lp.setMarginStart(dp(c, 12));
        row.addView(line, lp);
        return row;
    }

    /** Elegant empty page: the raven, a title and a hint, inside a softly framed card. */
    static LinearLayout emptyState(Context c, String title, String hint) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 20), dp(c, 22), dp(c, 20), dp(c, 26));
        box.setBackground(rounded(c, SURFACE, BORDER, 1, 20));
        ImageView crow = crow(c, 124);
        crow.setAlpha(0.92f);
        box.addView(crow);
        TextView t = centered(text(c, title, 17, TEXT, true));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-2, -2);
        tLp.topMargin = dp(c, 8);
        box.addView(t, tLp);
        if (hint != null && !hint.isEmpty()) {
            TextView d = centered(text(c, hint, 14, MUTED, false));
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-2, -2);
            dLp.topMargin = dp(c, 6);
            box.addView(d, dLp);
        }
        return box;
    }

    /** Entrance for newly added items (photos, videos): they pop in from slightly smaller with a gentle overshoot. */
    static void pop(View v, int index) {
        v.setAlpha(0f);
        v.setScaleX(0.82f);
        v.setScaleY(0.82f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setStartDelay(Math.min(index, 10) * 35L)
                .setInterpolator(new OvershootInterpolator(1.3f)).start();
    }

    /** Light entrance animation (fade + small rise). Cheap: only alpha and translation. */
    static void reveal(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 10));
        v.animate().alpha(1f).translationY(0f).setDuration(240)
                .setStartDelay(Math.min(index, 8) * 40L)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    // ---------------------------------------------------------------- dialogs / toast

    static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, R.style.AppDialog);
    }

    /** Shows the dialog in RTL with lavender buttons and Tajawal text, and returns it. */
    static AlertDialog show(AlertDialog.Builder b) {
        final Context c0 = b.getContext();
        AlertDialog d = b.create();
        d.show();
        if (d.getWindow() != null) d.getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int[] which = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int w : which) {
            Button btn = d.getButton(w);
            if (btn != null) { btn.setTextColor(LAV); btn.setTypeface(arabic(c0, true)); }
        }
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) msg.setTypeface(arabic(c0, false));
        int titleId = c0.getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId != 0 ? d.findViewById(titleId) : null;
        if (title != null) {
            title.setTypeface(arabic(c0, true));
            title.setTextColor(LAV);
        }
        return d;
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- images

    /** Decodes an image from a content Uri, down-sampled close to the requested size. Returns null on failure. */
    static Bitmap decode(Context c, String uriString, int reqW, int reqH) {
        try {
            Uri uri = Uri.parse(uriString);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            InputStream in2 = c.getContentResolver().openInputStream(uri);
            if (in2 == null) return null;
            try {
                return BitmapFactory.decodeStream(in2, null, o);
            } finally {
                in2.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads only the pixel size of an image: {width, height}, or null on failure. */
    static int[] bounds(Context c, String uriString) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(Uri.parse(uriString));
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            return new int[] {o.outWidth, o.outHeight};
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes a reader page: about [targetW] wide, full-colour ARGB_8888, capped at 12M pixels so tall pages cannot exhaust memory. */
    static Bitmap decodePage(Context c, String uriString, int targetW) {
        try {
            Uri uri = Uri.parse(uriString);
            int[] b = bounds(c, uriString);
            if (b == null) return null;
            int sample = 1;
            while (b[0] / (sample * 2) >= targetW
                    || (long) (b[0] / sample) * (b[1] / sample) > 12_000_000L) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888; // full colour depth, no banding
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Copies an image picked from the gallery into the app's private storage, so it stays available
     * even if the original is deleted from the gallery. Returns the copy's Uri string, or null on failure.
     * Call off the UI thread.
     */
    static String importImage(Context c, Uri src, String prefix) {
        File out = null;
        try {
            File dir = new File(c.getFilesDir(), "media");
            if (!dir.exists() && !dir.mkdirs()) return null;
            out = new File(dir, prefix + "_" + System.nanoTime() + ".img");
            InputStream in = c.getContentResolver().openInputStream(src);
            if (in == null) return null;
            try (InputStream input = in; OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = input.read(buf)) > 0) os.write(buf, 0, n);
            }
            return Uri.fromFile(out).toString();
        } catch (Exception e) {
            if (out != null) out.delete();
            return null;
        }
    }

    /** Deletes an image copy made by [importImage]. Gallery (content://) Uris are never touched. */
    static void deleteLocal(String uriString) {
        try {
            if (uriString == null || uriString.isEmpty()) return;
            Uri u = Uri.parse(uriString);
            String path = u.getPath();
            if ("file".equals(u.getScheme()) && path != null && path.contains("/media/")) new File(path).delete();
        } catch (Exception ignored) {
            // nothing to clean up
        }
    }
}
