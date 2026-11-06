package com.hajala.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** "نشر عمل جديد": type, cover, title, description and categories (including user-made ones). */
final class PublishScreen {
    interface Host {
        void closePublish(boolean saved);

        void pickCover();
    }

    private static final String[] TYPE_IDS = {Store.MANGA, Store.MANHWA, Store.ONESHOT};
    private static final String[] TYPE_LABELS = {"مانغا", "مانهوا", "ونشوت"};
    private static final String[] TYPE_HINTS = {"صفحات وفصول", "تمرير عمودي", "فصل واحد"};

    private final Activity act;
    private final Store store;
    private final Host host;

    private String type = Store.MANGA;
    private String cover = "";
    private final Set<String> selected = new LinkedHashSet<>();

    private final LinearLayout[] typeCards = new LinearLayout[3];
    private final TextView[] typeTitles = new TextView[3];
    private FlowLayout genreFlow;
    private ImageView coverPreview;
    private View coverHint;
    private EditText title;
    private EditText desc;
    private TextView titleError;
    private boolean importingCover;
    private boolean saved;
    private boolean closed;

    PublishScreen(Activity act, Store store, Host host) {
        this.act = act;
        this.store = store;
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

        // ---- header
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(10));
        FrameLayout back = Ui.iconButton(c, R.drawable.ic_back, "رجوع");
        back.setOnClickListener(v -> host.closePublish(false));
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView h = Ui.text(c, "نشر عمل جديد", 22, Ui.LAV, true);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-2, -2);
        hLp.setMarginStart(dp(12));
        header.addView(h, hLp);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View headerLine = new View(c);
        headerLine.setBackgroundColor(Ui.BORDER);
        root.addView(headerLine, new LinearLayout.LayoutParams(-1, dp(1)));

        // ---- scrolling body
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(18));
        sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // type
        body.addView(label("نوع العمل"), bottom(8));
        LinearLayout typeRow = new LinearLayout(c);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TYPE_IDS.length; i++) {
            final int index = i;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setClickable(true);
            card.setFocusable(true);
            card.setPadding(dp(6), dp(14), dp(6), dp(14));
            TextView t = Ui.centered(Ui.text(c, TYPE_LABELS[i], 16, Ui.TEXT, true));
            TextView hint = Ui.centered(Ui.text(c, TYPE_HINTS[i], 12, Ui.MUTED, false));
            card.addView(t, new LinearLayout.LayoutParams(-2, -2));
            card.addView(hint, new LinearLayout.LayoutParams(-2, -2));
            card.setOnClickListener(v -> {
                type = TYPE_IDS[index];
                refreshTypes();
            });
            Ui.asButton(card);
            Ui.press(card);
            typeCards[i] = card;
            typeTitles[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.setMarginStart(dp(10));
            typeRow.addView(card, lp);
        }
        refreshTypes();
        body.addView(typeRow, bottom(18));

        // cover + title
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(buildCoverBox(), new LinearLayout.LayoutParams(dp(116), dp(154)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label("عنوان العمل"), bottom(8));
        title = Ui.input(c, "اكتب عنوان العمل", false);
        title.setFilters(new InputFilter[] {new InputFilter.LengthFilter(80)});
        title.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {}

            @Override public void afterTextChanged(Editable s) {
                setTitleError(false);
            }
        });
        col.addView(title, new LinearLayout.LayoutParams(-1, dp(50)));
        titleError = Ui.text(c, "اكتب عنوان العمل أولًا", 12, Ui.ERROR, true);
        titleError.setVisibility(View.GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(-2, -2);
        errLp.topMargin = dp(6);
        col.addView(titleError, errLp);
        TextView coverTip = Ui.text(c, "يفضّل غلاف بنسبة ٣:٤ ليظهر بشكل واضح في المكتبة.", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(-2, -2);
        tipLp.topMargin = dp(8);
        col.addView(coverTip, tipLp);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1f);
        colLp.setMarginStart(dp(14));
        row.addView(col, colLp);
        body.addView(row, bottom(18));

        // description
        body.addView(label("وصف العمل"), bottom(8));
        desc = Ui.input(c, "عن ماذا يتحدث عملك؟", true);
        desc.setFilters(new InputFilter[] {new InputFilter.LengthFilter(500)});
        body.addView(desc, bottom(18));

        // categories
        body.addView(label("التصنيفات"), bottom(8));
        genreFlow = new FlowLayout(c, dp(8));
        body.addView(genreFlow, new LinearLayout.LayoutParams(-1, -2));
        rebuildGenres();

        // ---- bottom action bar
        View line = new View(c);
        line.setBackgroundColor(Ui.BORDER);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setPadding(dp(20), dp(12), dp(20), dp(14));
        LinearLayout publish = Ui.button(c, "نشر العمل", 0, true);
        publish.setOnClickListener(v -> save(true));
        bar.addView(publish, new LinearLayout.LayoutParams(0, dp(54), 1f));
        LinearLayout draft = Ui.button(c, "حفظ كمسودة", 0, false);
        draft.setOnClickListener(v -> save(false));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(130), dp(54));
        dLp.setMarginStart(dp(10));
        bar.addView(draft, dLp);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        return root;
    }

    // ------------------------------------------------------------------ sections

    private TextView label(String s) {
        return Ui.text(act, s, 14, Ui.TEXT, true);
    }

    private LinearLayout.LayoutParams bottom(int dpValue) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(dpValue);
        return lp;
    }

    private View buildCoverBox() {
        Context c = act;
        FrameLayout box = new FrameLayout(c);
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription("رفع غلاف العمل");
        box.setBackground(Ui.dashed(c, Ui.SURFACE, Ui.ACCENT, 1.5f, 16));
        box.setClipToOutline(true);
        box.setForeground(Ui.ripple(c, null, 16));

        coverPreview = new ImageView(c);
        coverPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverPreview.setVisibility(View.GONE);
        coverPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(coverPreview, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout hint = new LinearLayout(c);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setGravity(Gravity.CENTER);
        hint.addView(Ui.icon(c, R.drawable.ic_upload, Ui.LAV, 28), new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = Ui.centered(Ui.text(c, "رفع الغلاف", 13, Ui.LAV, true));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-2, -2);
        tLp.topMargin = dp(8);
        hint.addView(t, tLp);
        coverHint = hint;
        box.addView(hint, new FrameLayout.LayoutParams(-1, -1));

        box.setOnClickListener(v -> host.pickCover());
        Ui.asButton(box);
        Ui.press(box);
        return box;
    }

    private void refreshTypes() {
        for (int i = 0; i < typeCards.length; i++) {
            boolean sel = TYPE_IDS[i].equals(type);
            typeCards[i].setBackground(Ui.ripple(act,
                    Ui.rounded(act, sel ? Ui.CARD : Ui.SURFACE, sel ? Ui.LAV : Ui.BORDER, sel ? 2 : 1, 16), 16));
            typeTitles[i].setTextColor(sel ? Ui.LAV : Ui.TEXT);
            typeCards[i].setSelected(sel);
            typeCards[i].setContentDescription(TYPE_LABELS[i] + "، " + TYPE_HINTS[i] + (sel ? "، محدد" : ""));
        }
    }

    /** Rebuilds the category chips: built-in, then user-made ones, then the "+ تصنيف" chip. */
    private void rebuildGenres() {
        genreFlow.removeAllViews();
        List<String> custom = store.customGenres();
        List<String> all = new ArrayList<>(Store.builtInGenres());
        all.addAll(custom);
        for (final String g : all) {
            final TextView chip = Ui.chip(act, g, selected.contains(g));
            chip.setOnClickListener(v -> {
                if (selected.contains(g)) selected.remove(g);
                else selected.add(g);
                Ui.styleChip(act, chip, selected.contains(g));
            });
            if (custom.contains(g)) {
                chip.setOnLongClickListener(v -> {
                    confirmRemoveGenre(g);
                    return true;
                });
            }
            genreFlow.addView(chip, new android.view.ViewGroup.LayoutParams(-2, -2));
        }
        TextView add = Ui.addChip(act, "+ تصنيف");
        add.setOnClickListener(v -> promptNewGenre());
        genreFlow.addView(add, new android.view.ViewGroup.LayoutParams(-2, -2));
    }

    private void promptNewGenre() {
        Context c = act;
        final EditText input = new EditText(c);
        input.setHint("اسم التصنيف");
        input.setHintTextColor(Ui.MUTED);
        input.setTextColor(Ui.TEXT);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(24)});
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(input, new FrameLayout.LayoutParams(-1, -2));

        AlertDialog.Builder b = Ui.dialog(c)
                .setTitle("تصنيف جديد")
                .setView(wrap)
                .setPositiveButton("إضافة", null)
                .setNegativeButton("إلغاء", null);
        final AlertDialog d = Ui.show(b);
        // Override the positive button so the dialog stays open when the name is invalid.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim().replaceAll("\\s+", " ");
            if (name.isEmpty()) {
                input.setError("اكتب اسم التصنيف");
                return;
            }
            if (store.hasGenre(name)) {
                input.setError("هذا التصنيف موجود بالفعل");
                return;
            }
            store.addGenre(name);
            selected.add(name);
            rebuildGenres();
            d.dismiss();
            Ui.toast(act, "تمت إضافة التصنيف");
        });
        input.post(() -> {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void confirmRemoveGenre(final String name) {
        AlertDialog.Builder b = Ui.dialog(act)
                .setTitle("حذف التصنيف؟")
                .setMessage("سيتم حذف التصنيف «" + name + "» من قائمتك.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (d, i) -> {
                    store.removeGenre(name);
                    selected.remove(name);
                    rebuildGenres();
                });
        Ui.show(b);
    }

    // ------------------------------------------------------------------ cover

    /** Copies the chosen cover into app storage so it survives deleting the original from the gallery. */
    void onCoverPicked(final Uri uri) {
        importingCover = true;
        Ui.IO.execute(() -> {
            final String local = Ui.importImage(act, uri, "c");
            coverPreview.post(() -> {
                importingCover = false;
                if (closed) {
                    Ui.deleteLocal(local);
                    return;
                }
                if (local == null) {
                    Ui.toast(act, "تعذّر نسخ الغلاف، جرّب صورة أخرى");
                    return;
                }
                Ui.deleteLocal(cover); // replaced cover that was never saved
                cover = local;
                coverHint.setVisibility(View.GONE);
                coverPreview.setVisibility(View.VISIBLE);
                Ui.IO.execute(() -> {
                    final Bitmap bmp = Ui.decode(act, local, 348, 462);
                    if (bmp != null) coverPreview.post(() -> coverPreview.setImageBitmap(bmp));
                });
            });
        });
    }

    /** Called when the screen is removed: a cover that was never saved is deleted. */
    void onClosed() {
        closed = true;
        if (!saved) Ui.deleteLocal(cover);
    }

    // ------------------------------------------------------------------ saving

    private void setTitleError(boolean show) {
        titleError.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) title.setBackground(Ui.rounded(act, Ui.SURFACE, Ui.ERROR, 2, 14));
        else if (!title.hasFocus()) title.setBackground(Ui.rounded(act, Ui.SURFACE, Ui.BORDER, 1, 14));
        else title.setBackground(Ui.rounded(act, Ui.SURFACE, Ui.LAV, 2, 14));
    }

    private void save(boolean publish) {
        String t = title.getText().toString().trim();
        if (t.isEmpty()) {
            setTitleError(true);
            title.requestFocus();
            ObjectAnimator.ofFloat(title, "translationX", 0f, dp(8), -dp(8), dp(6), -dp(6), dp(3), 0f)
                    .setDuration(380).start();
            return;
        }
        if (importingCover) {
            Ui.toast(act, "انتظر حتى ينتهي نسخ الغلاف");
            return;
        }
        Store.Work w = new Store.Work();
        w.id = String.valueOf(System.currentTimeMillis());
        w.title = t;
        w.type = type;
        w.desc = desc.getText().toString().trim();
        w.status = publish ? Store.PUBLISHED : Store.DRAFT;
        w.cover = cover;
        w.createdAt = System.currentTimeMillis();
        w.genres.addAll(selected);
        store.addWork(w);
        saved = true;
        Ui.toast(act, publish ? "تم نشر العمل وحفظه في مكتبتك" : "تم حفظ المسودة");
        host.closePublish(true);
    }
}
