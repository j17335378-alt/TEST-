package com.hajala.app;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** "إضافة فصل": chapter number, optional title, page images (manga / manhwa / one-shot) or text (one-shot). */
final class AddChapterScreen {
    interface Host {
        void closeAddChapter(boolean saved);

        void pickPages();
    }

    private final Activity act;
    private final Store store;
    private final Store.Work work;
    private final Host host;

    private final List<String> pages = new ArrayList<>();
    private final Map<String, String> names = new HashMap<>();

    private EditText number;
    private EditText title;
    private EditText text;
    private TextView numberError;
    private TextView contentError;
    private TextView pagesCount;
    private FlowLayout thumbs;
    private int importing;
    private int failed;
    private boolean saved;
    private boolean closed;

    AddChapterScreen(Activity act, Store store, Store.Work work, Host host) {
        this.act = act;
        this.store = store;
        this.work = work;
        this.host = host;
    }

    private int dp(float v) {
        return Ui.dp(act, v);
    }

    private LinearLayout.LayoutParams bottom(int dpValue) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(dpValue);
        return lp;
    }

    View build() {
        Context c = act;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setClickable(true);

        // ---- header
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(10));
        FrameLayout back = Ui.iconButton(c, R.drawable.ic_back, "رجوع");
        back.setOnClickListener(v -> host.closeAddChapter(false));
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(c, "إضافة فصل", 22, Ui.LAV, true));
        TextView sub = Ui.text(c, work.title, 13, Ui.MUTED, false);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(sub);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, -2, 1f);
        tLp.setMarginStart(dp(12));
        header.addView(titles, tLp);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View headerLine = new View(c);
        headerLine.setBackgroundColor(Ui.BORDER);
        root.addView(headerLine, new LinearLayout.LayoutParams(-1, dp(1)));

        // ---- body
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(18));
        sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // number + title
        body.addView(label("رقم الفصل"), bottom(8));
        number = Ui.input(c, "مثال: 1", false);
        number.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        number.setText(Store.fmt(store.nextChapterNumber(work.id)));
        number.setFilters(new InputFilter[] {new InputFilter.LengthFilter(8)});
        body.addView(number, new LinearLayout.LayoutParams(-1, dp(50)));
        numberError = Ui.text(c, "", 12, Ui.ERROR, true);
        numberError.setVisibility(View.GONE);
        LinearLayout.LayoutParams nErr = bottom(0);
        nErr.topMargin = dp(6);
        body.addView(numberError, nErr);
        View gap = new View(c);
        body.addView(gap, new LinearLayout.LayoutParams(-1, dp(16)));

        body.addView(label("عنوان الفصل (اختياري)"), bottom(8));
        title = Ui.input(c, "اكتب عنوان الفصل", false);
        title.setFilters(new InputFilter[] {new InputFilter.LengthFilter(80)});
        body.addView(title, new LinearLayout.LayoutParams(-1, dp(50)));
        View gap2 = new View(c);
        body.addView(gap2, new LinearLayout.LayoutParams(-1, dp(18)));

        // pages
        boolean oneshot = Store.ONESHOT.equals(work.type);
        body.addView(label("صفحات الفصل"), bottom(4));
        body.addView(Ui.text(c, work.type.equals(Store.MANHWA)
                ? "اختر صور الفصل بالترتيب؛ تُعرض متتابعة بالتمرير العمودي."
                : "اختر صور الفصل؛ تُرتَّب تلقائيًا حسب اسم الملف.", 12, Ui.MUTED, false), bottom(10));
        LinearLayout pick = Ui.button(c, "اختيار الصور", R.drawable.ic_upload, false);
        pick.setOnClickListener(v -> host.pickPages());
        body.addView(pick, new LinearLayout.LayoutParams(-1, dp(52)));

        pagesCount = Ui.text(c, "", 14, Ui.LAV, true);
        LinearLayout.LayoutParams cLp = bottom(8);
        cLp.topMargin = dp(12);
        body.addView(pagesCount, cLp);
        thumbs = new FlowLayout(c, dp(8));
        body.addView(thumbs, bottom(8));

        // text (one-shot only)
        if (oneshot) {
            View gap3 = new View(c);
            body.addView(gap3, new LinearLayout.LayoutParams(-1, dp(10)));
            body.addView(label("أو اكتب نص الفصل"), bottom(8));
            text = Ui.input(c, "اكتب نص الونشوت هنا…", true);
            text.setMinLines(8);
            text.setMaxLines(30);
            body.addView(text, bottom(8));
        }

        contentError = Ui.text(c, oneshot ? "اختر صورًا أو اكتب نص الفصل أولًا" : "اختر صور الفصل أولًا",
                12, Ui.ERROR, true);
        contentError.setVisibility(View.GONE);
        body.addView(contentError, bottom(0));

        refreshPages();

        // ---- bottom bar
        View line = new View(c);
        line.setBackgroundColor(Ui.BORDER);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setPadding(dp(20), dp(12), dp(20), dp(14));
        LinearLayout save = Ui.button(c, "حفظ الفصل", 0, true);
        save.setOnClickListener(v -> save());
        bar.addView(save, new LinearLayout.LayoutParams(-1, dp(54)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        return root;
    }

    private TextView label(String s) {
        return Ui.text(act, s, 14, Ui.TEXT, true);
    }

    // ------------------------------------------------------------------ pages

    /** Called by the activity with the images chosen in the system picker; copies them into app storage. */
    void onPagesPicked(List<Uri> uris) {
        importing += uris.size();
        contentError.setVisibility(View.GONE);
        refreshPages();
        for (final Uri u : uris) {
            Ui.IO.execute(() -> {
                final String name = displayName(u);
                final String local = Ui.importImage(act, u, "p");
                pagesCount.post(() -> {
                    importing--;
                    if (local == null) {
                        failed++;
                    } else if (closed) {
                        Ui.deleteLocal(local);
                    } else {
                        pages.add(local);
                        names.put(local, name);
                    }
                    if (importing == 0 && !closed) {
                        Collections.sort(pages, (a, b) -> naturalCompare(names.get(a), names.get(b)));
                        if (failed > 0) {
                            Ui.toast(act, "تعذّر نسخ " + failed + " صورة");
                            failed = 0;
                        }
                    }
                    if (!closed) refreshPages();
                });
            });
        }
    }

    /** Called when the screen is removed: copies that were never saved are deleted. */
    void onClosed() {
        closed = true;
        if (!saved) {
            for (String p : pages) Ui.deleteLocal(p);
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor cur = act.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) {
            // fall back to the last path segment
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "" : name;
    }

    /** Compares file names so that "2.jpg" comes before "10.jpg". */
    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i);
            char y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.toLowerCase(x) - Character.toLowerCase(y);
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private void refreshPages() {
        thumbs.removeAllViews();
        if (importing > 0) {
            pagesCount.setText("جارٍ نسخ الصفحات إلى التطبيق… (" + importing + ")");
            pagesCount.setTextColor(Ui.MUTED);
        } else if (pages.isEmpty()) {
            pagesCount.setText("لم يتم اختيار صفحات بعد");
            pagesCount.setTextColor(Ui.MUTED);
        } else {
            pagesCount.setText("الصفحات المختارة: " + pages.size());
            pagesCount.setTextColor(Ui.LAV);
        }
        int idx = 0;
        for (final String p : new ArrayList<>(pages)) {
            idx++;
            thumbs.addView(thumb(p, idx), new ViewGroup.LayoutParams(dp(78), dp(104)));
        }
    }

    private View thumb(final String uri, int index) {
        FrameLayout f = new FrameLayout(act);
        f.setBackground(Ui.rounded(act, Ui.SURFACE, Ui.BORDER, 1, 10));
        f.setClipToOutline(true);

        final ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        Ui.IO.execute(() -> {
            final Bitmap bmp = Ui.decode(act, uri, 160, 214);
            if (bmp != null) iv.post(() -> iv.setImageBitmap(bmp));
        });

        TextView n = Ui.pill(act, String.valueOf(index), Ui.TEXT, 0xD1100B13, 0);
        FrameLayout.LayoutParams nLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        nLp.setMargins(dp(4), 0, 0, dp(4));
        f.addView(n, nLp);

        FrameLayout x = new FrameLayout(act);
        x.setBackground(Ui.rounded(act, 0xD1100B13, 0, 0, 12));
        x.setClickable(true);
        x.setContentDescription("حذف الصفحة " + index);
        x.addView(Ui.icon(act, R.drawable.ic_close, Ui.LAV, 14), new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
        x.setOnClickListener(v -> {
            pages.remove(uri);
            Ui.deleteLocal(uri);
            refreshPages();
        });
        Ui.press(x);
        FrameLayout.LayoutParams xLp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.END);
        xLp.setMargins(dp(4), dp(4), dp(4), 0);
        f.addView(x, xLp);
        return f;
    }

    // ------------------------------------------------------------------ saving

    /** Accepts Arabic-Indic digits and the Arabic decimal separator too. */
    private static String latinDigits(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch >= '\u0660' && ch <= '\u0669') b.append((char) ('0' + (ch - '\u0660')));
            else if (ch >= '\u06F0' && ch <= '\u06F9') b.append((char) ('0' + (ch - '\u06F0')));
            else if (ch == '\u066B' || ch == ',') b.append('.');
            else b.append(ch);
        }
        return b.toString();
    }

    private void showNumberError(String msg) {
        numberError.setText(msg);
        numberError.setVisibility(View.VISIBLE);
        number.requestFocus();
    }

    private void save() {
        numberError.setVisibility(View.GONE);
        contentError.setVisibility(View.GONE);

        double n;
        try {
            n = Double.parseDouble(latinDigits(number.getText().toString().trim()));
        } catch (NumberFormatException e) {
            showNumberError("اكتب رقم الفصل (مثال: 1 أو 2.5)");
            return;
        }
        if (n <= 0) {
            showNumberError("رقم الفصل يجب أن يكون أكبر من صفر");
            return;
        }
        if (store.hasChapterNumber(work.id, n)) {
            showNumberError("الفصل " + Store.fmt(n) + " موجود بالفعل");
            return;
        }
        if (importing > 0) {
            Ui.toast(act, "انتظر حتى ينتهي نسخ الصفحات");
            return;
        }
        String body = text == null ? "" : text.getText().toString().trim();
        if (pages.isEmpty() && body.isEmpty()) {
            contentError.setVisibility(View.VISIBLE);
            return;
        }

        Store.Chapter ch = new Store.Chapter();
        ch.id = String.valueOf(System.currentTimeMillis());
        ch.workId = work.id;
        ch.number = n;
        ch.title = title.getText().toString().trim();
        ch.text = pages.isEmpty() ? body : "";
        ch.createdAt = System.currentTimeMillis();
        ch.pages.addAll(pages);
        store.addChapter(ch);
        saved = true;
        Ui.toast(act, "تمت إضافة الفصل " + Store.fmt(n));
        host.closeAddChapter(true);
    }
}
