package com.hajala.app;

import android.content.Context;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen note editor. The text is saved automatically ~0.7 s after the last keystroke, whenever the app
 * goes to the background, and when the page closes, so nothing is lost. Bodies are stored in their own file,
 * so very long notes are never truncated.
 */
final class NoteEditorScreen {
    private static final long AUTOSAVE_MS = 700;

    private final StudioContext ctx;
    private final String noteId;
    private final Runnable onClosed;

    private String notebookId;
    private String categoryId;
    private EditText title;
    private EditText body;
    private TextView status;
    private TextView notebookChip;
    private TextView categoryChip;
    private LinearLayout imagesBox;
    private boolean dirty;
    private boolean closed;
    private boolean loading = true;
    private boolean saveFailed;

    private final Runnable autosave = this::saveNow;

    NoteEditorScreen(StudioContext ctx, String noteId, Runnable onClosed) {
        this.ctx = ctx;
        this.noteId = noteId;
        this.onClosed = onClosed;
    }

    private int dp(float v) {
        return ctx.dp(v);
    }

    View build() {
        Context c = ctx.act;
        final StudioStore.Note note = ctx.store.note(noteId);
        notebookId = note == null ? "" : note.notebookId;
        categoryId = note == null ? "" : note.categoryId;

        LinearLayout page = ctx.pageRoot();
        View menu = ctx.iconActionButton(R.drawable.ic_more, "خيارات الملاحظة", v -> menu());
        page.addView(ctx.header("ملاحظة", v -> ctx.host.pop(), menu), new LinearLayout.LayoutParams(-1, -2));
        page.addView(ctx.hairline());

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setKeepScreenOn(false);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(28));
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // notebook / category chips
        LinearLayout chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        notebookChip = Ui.chip(c, "", false);
        notebookChip.setOnClickListener(v -> chooseNotebook());
        categoryChip = Ui.chip(c, "", false);
        categoryChip.setOnClickListener(v -> chooseCategory());
        chips.addView(notebookChip, chipLp());
        chips.addView(categoryChip, chipLp());
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        col.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        refreshChips();

        status = Ui.text(c, "", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(8);
        col.addView(status, sl);

        // title
        title = Ui.input(c, "عنوان الملاحظة", false);
        title.setTextSize(19);
        title.setTypeface(Ui.arabic(c, true));
        title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(8);
        col.addView(title, tl);

        // body: unlimited length, multi-line, direction follows the first strong character (Arabic = RTL)
        body = Ui.input(c, "اكتب ملاحظاتك هنا… يمكنك كتابة نص طويل جداً دون أي اقتطاع.", true);
        body.setMinLines(10);
        body.setMaxLines(Integer.MAX_VALUE);
        body.setHorizontallyScrolling(false);
        body.setFilters(new android.text.InputFilter[0]);
        body.setTextSize(16);
        body.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(10);
        col.addView(body, bl);

        // attached images
        TextView imgTitle = Ui.text(c, "الصور المرفقة", 16, Ui.LAV, true);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.topMargin = dp(18);
        col.addView(imgTitle, il);
        imagesBox = new LinearLayout(c);
        imagesBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(imagesBox, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout addImg = Ui.button(c, "إضافة صور إلى الملاحظة", R.drawable.ic_plus, false);
        addImg.setOnClickListener(v -> addImages());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, dp(50));
        al.topMargin = dp(10);
        col.addView(addImg, al);

        // load text, then start watching for edits
        title.setText(note == null ? "" : note.title);
        body.setText(ctx.store.noteBody(noteId));
        loading = false;
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void afterTextChanged(Editable e) {
                if (loading) return;
                dirty = true;
                setStatus("جارٍ الحفظ…");
                body.removeCallbacks(autosave);
                body.postDelayed(autosave, AUTOSAVE_MS);
            }
        };
        title.addTextChangedListener(watcher);
        body.addTextChangedListener(watcher);
        setStatus(note != null && (!note.title.isEmpty() || !note.preview.isEmpty()) ? "محفوظة" : "ملاحظة جديدة");
        renderImages();

        ctx.setFlushHook(this::saveNow);
        if (note != null && note.title.isEmpty() && note.preview.isEmpty()) {
            title.requestFocus();
        }
        return page;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(8));
        return lp;
    }

    private void setStatus(String s) {
        status.setText(s);
        status.setTextColor(saveFailed ? Ui.ERROR : Ui.MUTED);
        Ui.applyFont(status, false);
    }

    // ------------------------------------------------------------------ saving

    /** Writes the note to disk right now (safe to call repeatedly). */
    private void saveNow() {
        if (title == null || body == null) return;
        body.removeCallbacks(autosave);
        if (!dirty) return;
        boolean ok = ctx.store.saveNote(noteId, title.getText().toString(), body.getText().toString(),
                notebookId, categoryId);
        saveFailed = !ok;
        if (ok) dirty = false;
        if (!closed) setStatus(ok ? "تم الحفظ" : "تعذّر الحفظ! تحقق من مساحة التخزين");
    }

    /** Called when the page closes. An empty untouched note is removed so the list stays clean. */
    void onClosed() {
        saveNow();
        closed = true;
        ctx.setFlushHook(null);
        StudioStore.Note n = ctx.store.note(noteId);
        if (n != null && n.title.isEmpty() && n.preview.isEmpty()
                && ctx.store.medicalImages(null, noteId).isEmpty()
                && ctx.store.noteBody(noteId).trim().isEmpty()) {
            ctx.store.deleteNote(noteId);
        }
        if (onClosed != null) onClosed.run();
    }

    // ------------------------------------------------------------------ notebook / category

    private void refreshChips() {
        StudioStore.Notebook nb = ctx.store.notebook(notebookId);
        StudioStore.Category cat = ctx.store.category(categoryId);
        notebookChip.setText("الدفتر: " + (nb == null ? "بدون دفتر" : nb.name));
        categoryChip.setText("التصنيف: " + (cat == null ? "بدون تصنيف" : cat.name));
        Ui.applyFont(notebookChip, false);
        Ui.applyFont(categoryChip, false);
    }

    private void chooseNotebook() {
        final List<StudioStore.Notebook> list = ctx.store.notebooks();
        final List<String> labels = new ArrayList<>();
        labels.add("بدون دفتر");
        for (StudioStore.Notebook n : list) labels.add(n.name);
        labels.add("دفتر جديد…");
        ctx.choose("نقل الملاحظة إلى دفتر", labels.toArray(new String[0]), which -> {
            if (which == 0) {
                moveTo("");
            } else if (which == labels.size() - 1) {
                ctx.prompt("دفتر جديد", "اسم الدفتر", "", "إنشاء", text -> moveTo(ctx.store.addNotebook(text).id), null);
            } else {
                moveTo(list.get(which - 1).id);
            }
        });
    }

    private void moveTo(String newNotebookId) {
        notebookId = newNotebookId;
        dirty = true;
        saveNow();
        refreshChips();
        renderImages();
    }

    private void chooseCategory() {
        final List<StudioStore.Category> list = ctx.store.categories();
        final List<String> labels = new ArrayList<>();
        labels.add("بدون تصنيف");
        for (StudioStore.Category cat : list) labels.add(cat.name);
        labels.add("تصنيف جديد…");
        ctx.choose("نقل الملاحظة إلى تصنيف", labels.toArray(new String[0]), which -> {
            if (which == 0) {
                setCategory("");
            } else if (which == labels.size() - 1) {
                ctx.prompt("تصنيف جديد", "اسم التصنيف", "", "إنشاء", text -> {
                    StudioStore.Category created = ctx.store.addCategory(text);
                    if (created != null) setCategory(created.id);
                }, text -> ctx.store.categoryNameTaken(text, "") ? "هذا التصنيف موجود مسبقاً" : null);
            } else {
                setCategory(list.get(which - 1).id);
            }
        });
    }

    private void setCategory(String id) {
        categoryId = id;
        dirty = true;
        saveNow();
        refreshChips();
    }

    // ------------------------------------------------------------------ menu

    private void menu() {
        ctx.choose("الملاحظة", new String[] {"حفظ الآن", "نقل إلى دفتر", "نقل إلى تصنيف", "حذف الملاحظة"}, which -> {
            if (which == 0) {
                dirty = true;
                saveNow();
                ctx.toast(saveFailed ? "تعذّر الحفظ" : "تم الحفظ");
            } else if (which == 1) {
                chooseNotebook();
            } else if (which == 2) {
                chooseCategory();
            } else {
                ctx.confirm("حذف الملاحظة", "ستُحذف الملاحظة وصورها المرفقة نهائياً.", "حذف", () -> {
                    dirty = false;
                    body.removeCallbacks(autosave);
                    ctx.store.deleteNote(noteId);
                    ctx.host.pop();
                });
            }
        });
    }

    // ------------------------------------------------------------------ images

    private void addImages() {
        // make sure the note exists on disk before attaching files to it
        dirty = true;
        saveNow();
        ctx.host.pickFiles(new String[] {"image/*"}, true, (List<Uri> uris) -> {
            if (uris == null || uris.isEmpty()) return;
            ctx.importPicked(uris, StudioStore.SCOPE_MEDICAL, "", notebookId, noteId, "image", this::renderImages);
        });
    }

    private void renderImages() {
        if (imagesBox == null) return;
        Context c = ctx.act;
        imagesBox.removeAllViews();
        final List<StudioStore.Item> imgs = ctx.store.medicalImages(null, noteId);
        if (imgs.isEmpty()) {
            TextView none = Ui.text(c, "لا توجد صور مرفقة بعد.", 13, Ui.MUTED, false);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
            nl.topMargin = dp(6);
            imagesBox.addView(none, nl);
            return;
        }
        for (int i = 0; i < imgs.size(); i++) {
            final int idx = i;
            final StudioStore.Item it = imgs.get(i);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackground(Ui.rounded(c, Ui.SURFACE, Ui.BORDER, 1, 14));
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.rounded(c, Ui.BG, 0, 0, 10));
            iv.setClipToOutline(true);
            iv.setClickable(true);
            iv.setContentDescription("فتح الصورة " + it.name);
            iv.setOnClickListener(v -> ctx.openViewer(imgs, idx, true, this::renderImages));
            Ui.press(iv);
            row.addView(iv, new LinearLayout.LayoutParams(dp(84), dp(84)));
            StudioMedia.load(ctx.store, it, iv, dp(84), null);

            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(c, it.name, 13, Ui.TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            info.addView(name);
            TextView cap = Ui.text(c, it.caption.isEmpty() ? "اضغط على الصورة لإضافة وصف" : it.caption, 13,
                    it.caption.isEmpty() ? Ui.MUTED : Ui.TEXT, false);
            cap.setMaxLines(3);
            cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(cap);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, -2, 1f);
            infoLp.setMarginStart(dp(10));
            row.addView(info, infoLp);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(8);
            imagesBox.addView(row, rl);
        }
    }
}
