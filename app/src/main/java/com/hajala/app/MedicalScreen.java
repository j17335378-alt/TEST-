package com.hajala.app;

import android.content.Context;
import android.net.Uri;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * "الدراسة الطبية": notes with categories, study notebooks, medical images with captions, and search by title
 * or content. Everything is saved permanently through [StudioStore].
 */
final class MedicalScreen {
    private static final int SEG_NOTES = 0;
    private static final int SEG_NOTEBOOKS = 1;
    private static final int SEG_IMAGES = 2;
    private static final String[] SEG_LABELS = {"الملاحظات", "الدفاتر", "الصور"};

    private final StudioContext ctx;
    private int seg = SEG_NOTES;
    private LinearLayout root;
    private LinearLayout segBody;
    private final List<TextView> segChips = new ArrayList<>();
    private NotesPanel mainNotes;
    private int imagesLimit = 60;

    MedicalScreen(StudioContext ctx) {
        this.ctx = ctx;
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
        Context c = ctx.act;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView sub = Ui.text(c, "دفتر ملاحظات ومكتبة دراسية لمجالك الطبي، تُحفظ كلها على هاتفك.", 14, Ui.MUTED, false);
        root.addView(sub, lp(-1, -2, 0, 0, 0, 12));

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        segChips.clear();
        for (int i = 0; i < SEG_LABELS.length; i++) {
            final int index = i;
            TextView chip = Ui.chip(c, SEG_LABELS[i], i == seg);
            chip.setOnClickListener(v -> {
                seg = index;
                for (int k = 0; k < segChips.size(); k++) Ui.styleChip(c, segChips.get(k), k == seg);
                Ui.pop(v);
                renderSeg();
                segBody.setAlpha(0f);
                segBody.animate().alpha(1f).setDuration(Ui.ANIM_BASE).start();
            });
            chips.addView(chip, lp(-2, -2, 0, 0, 8, 0));
            segChips.add(chip);
        }
        hs.addView(chips);
        root.addView(hs, lp(-1, -2, 0, 0, 0, 14));

        segBody = new LinearLayout(c);
        segBody.setOrientation(LinearLayout.VERTICAL);
        root.addView(segBody, new LinearLayout.LayoutParams(-1, -2));
        renderSeg();
        return root;
    }

    void refresh() {
        if (segBody != null) renderSeg();
    }

    private void renderSeg() {
        segBody.removeAllViews();
        if (seg == SEG_NOTES) {
            mainNotes = new NotesPanel(null);
            segBody.addView(mainNotes.build());
        } else if (seg == SEG_NOTEBOOKS) {
            renderNotebooks();
        } else {
            renderImages();
        }
    }

    // ------------------------------------------------------------------ notes (panel used by the tab and by each notebook)

    private final class NotesPanel {
        private final String notebookId; // null = all notes
        private String categoryId;       // null = all categories
        private String query = "";
        private int token;
        private LinearLayout box;
        private LinearLayout list;
        private EditText search;
        private LinearLayout chipHolder;
        private HorizontalScrollView chipScroll;
        private int chipScrollX;
        private boolean chipPop;

        NotesPanel(String notebookId) {
            this.notebookId = notebookId;
        }

        View build() {
            Context c = ctx.act;
            box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);

            LinearLayout create = Ui.button(c, "ملاحظة جديدة", R.drawable.ic_plus, true);
            create.setOnClickListener(v -> newNote());
            box.addView(create, lp(-1, 54, 0, 0, 0, 12));

            search = Ui.input(c, "ابحث في العناوين والمحتوى…", false);
            search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            search.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            search.setText(query);
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int d) {}

                @Override
                public void afterTextChanged(Editable e) {
                    query = e.toString();
                    final int mine = ++token;
                    search.postDelayed(() -> {
                        if (mine == token) renderList();
                    }, 280);
                }
            });
            box.addView(search, lp(-1, -2, 0, 0, 0, 12));

            chipHolder = new LinearLayout(c);
            chipHolder.setOrientation(LinearLayout.VERTICAL);
            box.addView(chipHolder, lp(-1, -2, 0, 0, 0, 0));

            list = new LinearLayout(c);
            list.setOrientation(LinearLayout.VERTICAL);
            box.addView(list, new LinearLayout.LayoutParams(-1, -2));
            renderChips();
            renderList();
            return box;
        }

        void refresh() {
            if (box == null) return;
            renderChips();
            renderList();
        }

        private void newNote() {
            StudioStore.Note n = ctx.store.newNote(notebookId == null ? "" : notebookId, categoryId == null ? "" : categoryId);
            openNote(n.id, this::refresh);
        }

        private void renderChips() {
            Context c = ctx.act;
            if (chipScroll != null) chipScrollX = chipScroll.getScrollX(); // keep the row where the user left it
            final boolean pop = chipPop;
            chipPop = false;
            chipHolder.removeAllViews();
            final HorizontalScrollView hs = new HorizontalScrollView(c);
            chipScroll = hs;
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);

            TextView all = Ui.chip(c, "كل التصنيفات", categoryId == null);
            all.setOnClickListener(v -> {
                categoryId = null;
                chipPop = true;
                renderChips();
                renderList();
            });
            if (pop && categoryId == null) Ui.pop(all);
            row.addView(all, lp(-2, -2, 0, 0, 8, 0));

            for (final StudioStore.Category cat : ctx.store.categories()) {
                TextView chip = Ui.chip(c, cat.name, cat.id.equals(categoryId));
                chip.setOnClickListener(v -> {
                    categoryId = cat.id;
                    chipPop = true;
                    renderChips();
                    renderList();
                });
                chip.setOnLongClickListener(v -> {
                    categoryMenu(cat);
                    return true;
                });
                if (pop && cat.id.equals(categoryId)) Ui.pop(chip);
                row.addView(chip, lp(-2, -2, 0, 0, 8, 0));
            }
            TextView add = Ui.addChip(c, "+ تصنيف");
            add.setOnClickListener(v -> ctx.prompt("تصنيف جديد", "مثال: تشريح، علم الأدوية…", "", "إنشاء", text -> {
                StudioStore.Category created = ctx.store.addCategory(text);
                if (created != null) categoryId = created.id;
                renderChips();
                renderList();
            }, text -> ctx.store.categoryNameTaken(text, "") ? "هذا التصنيف موجود مسبقاً" : null));
            row.addView(add, lp(-2, -2, 0, 0, 0, 0));
            hs.addView(row);
            chipHolder.addView(hs, lp(-1, -2, 0, 0, 0, 6));
            final int restoreX = chipScrollX;
            hs.post(() -> hs.scrollTo(restoreX, 0));
            TextView hint = Ui.text(c, "اضغط مطولاً على أي تصنيف لتعديله أو حذفه.", 12, Ui.MUTED, false);
            chipHolder.addView(hint, lp(-1, -2, 0, 0, 0, 12));
        }

        private void categoryMenu(final StudioStore.Category cat) {
            ctx.choose(cat.name, new String[] {"إعادة تسمية", "حذف التصنيف"}, which -> {
                if (which == 0) {
                    ctx.prompt("إعادة تسمية التصنيف", "اسم التصنيف", cat.name, "حفظ", text -> {
                        ctx.store.renameCategory(cat.id, text);
                        refresh();
                    }, text -> ctx.store.categoryNameTaken(text, cat.id) ? "هذا التصنيف موجود مسبقاً" : null);
                } else {
                    ctx.confirm("حذف التصنيف", "ستبقى ملاحظات «" + cat.name + "» كما هي وتصبح بدون تصنيف.", "حذف", () -> {
                        ctx.store.deleteCategory(cat.id);
                        if (cat.id.equals(categoryId)) categoryId = null;
                        refresh();
                    });
                }
            });
        }

        private void renderList() {
            final Context c = ctx.act;
            list.removeAllViews();
            final String q = query.trim();
            if (q.isEmpty()) {
                showNotes(ctx.store.notes(notebookId, categoryId), false);
                return;
            }
            TextView searching = Ui.text(c, "جارٍ البحث…", 13, Ui.MUTED, false);
            list.addView(searching);
            final int mine = token;
            Ui.IO.execute(() -> {
                final List<StudioStore.Note> found = ctx.store.search(q, notebookId);
                ctx.act.runOnUiThread(() -> {
                    if (mine != token || !q.equals(query.trim())) return;
                    list.removeAllViews();
                    showNotes(found, true);
                });
            });
        }

        private void showNotes(List<StudioStore.Note> notes, boolean searching) {
            Context c = ctx.act;
            if (searching) {
                list.addView(Ui.text(c, "نتائج البحث: " + notes.size(), 14, Ui.LAV, true), lp(-1, -2, 0, 0, 0, 10));
            } else {
                list.addView(Ui.text(c, "عدد الملاحظات: " + notes.size(), 14, Ui.LAV, true), lp(-1, -2, 0, 0, 0, 10));
            }
            if (notes.isEmpty()) {
                list.addView(Ui.emptyState(c, searching ? "لا توجد نتائج" : "لا توجد ملاحظات بعد",
                        searching ? "جرّب كلمة أخرى." : "اضغط «ملاحظة جديدة» وابدأ بتدوين دراستك."),
                        new LinearLayout.LayoutParams(-1, -2));
                return;
            }
            for (int i = 0; i < notes.size(); i++) {
                View card = noteCard(notes.get(i));
                Ui.reveal(card, i);
                list.addView(card, lp(-1, -2, 0, 0, 0, 10));
            }
        }

        private View noteCard(final StudioStore.Note n) {
            Context c = ctx.act;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setClickable(true);
            card.setFocusable(true);
            card.setBackground(Ui.ripple(c, Ui.rounded(c, Ui.CARD, 0x6675416F, 1, 16), 16));

            TextView t = Ui.text(c, n.title.isEmpty() ? "بدون عنوان" : n.title, 17, n.title.isEmpty() ? Ui.MUTED : Ui.LAV, true);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            card.addView(t, new LinearLayout.LayoutParams(-1, -2));
            if (!n.preview.isEmpty()) {
                TextView p = Ui.text(c, n.preview, 14, Ui.TEXT, false);
                p.setMaxLines(2);
                p.setEllipsize(TextUtils.TruncateAt.END);
                p.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                card.addView(p, lp(-1, -2, 0, 4, 0, 0));
            }
            LinearLayout meta = new LinearLayout(c);
            meta.setOrientation(LinearLayout.HORIZONTAL);
            StudioStore.Category cat = ctx.store.category(n.categoryId);
            StudioStore.Notebook nb = ctx.store.notebook(n.notebookId);
            if (cat != null) meta.addView(Ui.pill(c, cat.name, Ui.ON_LAV, Ui.LAV, 0), lp(-2, -2, 0, 0, 6, 0));
            if (nb != null && notebookId == null) {
                meta.addView(Ui.pill(c, nb.name, Ui.TEXT, 0xD9100B13, 0x8075416F), lp(-2, -2, 0, 0, 6, 0));
            }
            int imgs = ctx.store.medicalImages(null, n.id).size();
            if (imgs > 0) meta.addView(Ui.pill(c, imgs + " صورة", Ui.TEXT, 0xD9100B13, 0x8075416F), lp(-2, -2, 0, 0, 6, 0));
            meta.addView(Ui.text(c, DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(n.updatedAt)), 12, Ui.MUTED, false));
            card.addView(meta, lp(-1, -2, 0, 8, 0, 0));

            card.setOnClickListener(v -> openNote(n.id, this::refresh));
            card.setOnLongClickListener(v -> {
                noteMenu(n);
                return true;
            });
            Ui.asButton(card);
            Ui.press(card);
            return card;
        }

        private void noteMenu(final StudioStore.Note n) {
            ctx.choose(n.title.isEmpty() ? "ملاحظة" : n.title,
                    new String[] {"فتح", "نقل إلى تصنيف", "نقل إلى دفتر", "حذف"}, which -> {
                        if (which == 0) openNote(n.id, this::refresh);
                        else if (which == 1) moveNoteCategory(n, this::refresh);
                        else if (which == 2) moveNoteNotebook(n, this::refresh);
                        else ctx.confirm("حذف الملاحظة", "ستُحذف الملاحظة وصورها المرفقة نهائياً.", "حذف", () -> {
                            ctx.store.deleteNote(n.id);
                            refresh();
                        });
                    });
        }
    }

    private void openNote(String noteId, final Runnable after) {
        NoteEditorScreen editor = new NoteEditorScreen(ctx, noteId, after);
        ctx.host.push(editor.build(), editor::onClosed, after);
    }

    private void moveNoteCategory(final StudioStore.Note n, final Runnable after) {
        final List<StudioStore.Category> cats = ctx.store.categories();
        final List<String> labels = new ArrayList<>();
        labels.add("بدون تصنيف");
        for (StudioStore.Category c : cats) labels.add(c.name);
        ctx.choose("نقل إلى تصنيف", labels.toArray(new String[0]), which -> {
            ctx.store.moveNote(n.id, null, which == 0 ? "" : cats.get(which - 1).id);
            ctx.toast("تم النقل");
            after.run();
        });
    }

    private void moveNoteNotebook(final StudioStore.Note n, final Runnable after) {
        final List<StudioStore.Notebook> books = ctx.store.notebooks();
        final List<String> labels = new ArrayList<>();
        labels.add("بدون دفتر");
        for (StudioStore.Notebook b : books) labels.add(b.name);
        ctx.choose("نقل إلى دفتر", labels.toArray(new String[0]), which -> {
            ctx.store.moveNote(n.id, which == 0 ? "" : books.get(which - 1).id, null);
            ctx.toast("تم النقل");
            after.run();
        });
    }

    // ------------------------------------------------------------------ notebooks

    private void renderNotebooks() {
        Context c = ctx.act;
        LinearLayout create = Ui.button(c, "دفتر جديد", R.drawable.ic_plus, true);
        create.setOnClickListener(v -> BookScreen.showCreate(ctx,
                text -> notebookNameTaken(text, "") ? "يوجد دفتر بهذا الاسم" : null,
                (text, bookType, cover) -> {
                    StudioStore.Notebook nb = ctx.store.addNotebook(text, StudioStore.SCOPE_MEDICAL, bookType, cover);
                    renderSeg();
                    openNotebook(nb.id);
                }));
        segBody.addView(create, lp(-1, 54, 0, 0, 0, 12));

        List<StudioStore.Notebook> books = ctx.store.notebooks();
        segBody.addView(Ui.text(c, "عدد الدفاتر: " + books.size(), 14, Ui.LAV, true), lp(-1, -2, 0, 0, 0, 10));
        if (books.isEmpty()) {
            segBody.addView(Ui.emptyState(c, "لا توجد دفاتر بعد",
                    "أنشئ دفتراً لكل مادة أو مقرر: كتابة حرة، أسئلة وأجوبة، دراسة أو مهام."), new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < books.size(); i += 2) {
            LinearLayout shelf = new LinearLayout(c);
            shelf.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 2; k++) {
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1f);
                cell.setMarginStart(k == 0 ? 0 : dp(7));
                cell.setMarginEnd(k == 0 ? dp(7) : 0);
                if (i + k < books.size()) {
                    final StudioStore.Notebook nb = books.get(i + k);
                    View card = BookScreen.coverCard(ctx, nb);
                    card.setOnClickListener(v -> openNotebook(nb.id));
                    card.setOnLongClickListener(v -> {
                        notebookMenu(nb, this::renderSeg);
                        return true;
                    });
                    Ui.reveal(card, i + k);
                    shelf.addView(card, cell);
                } else {
                    shelf.addView(new View(c), cell);
                }
            }
            segBody.addView(shelf, lp(-1, -2, 0, 0, 0, 14));
        }
    }

    private boolean notebookNameTaken(String name, String exceptId) {
        String key = StudioStore.clean(name);
        for (StudioStore.Notebook n : ctx.store.notebooks()) {
            if (!n.id.equals(exceptId) && n.name.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private void notebookMenu(final StudioStore.Notebook nb, final Runnable after) {
        ctx.choose(nb.name, new String[] {"فتح", "إعادة تسمية", "حذف الدفتر"}, which -> {
            if (which == 0) openNotebook(nb.id);
            else if (which == 1) renameNotebook(nb, after);
            else confirmDeleteNotebook(nb, after);
        });
    }

    private void renameNotebook(final StudioStore.Notebook nb, final Runnable after) {
        ctx.prompt("إعادة تسمية الدفتر", "اسم الدفتر", nb.name, "حفظ", text -> {
            ctx.store.renameNotebook(nb.id, text);
            after.run();
        }, text -> notebookNameTaken(text, nb.id) ? "يوجد دفتر بهذا الاسم" : null);
    }

    private void confirmDeleteNotebook(final StudioStore.Notebook nb, final Runnable after) {
        int notes = ctx.store.noteCount(nb.id);
        int images = ctx.store.medicalImages(nb.id, null).size();
        ctx.confirm("حذف الدفتر", "سيُحذف الدفتر «" + nb.name + "» مع " + notes + " ملاحظة و" + images
                + " صورة بداخله نهائياً.", "حذف", () -> {
            ctx.store.deleteNotebook(nb.id);
            ctx.toast("تم حذف الدفتر");
            after.run();
        });
    }

    /** Opens the notebook as a real book: cover, ruled pages, page turning. Old notes stay reachable from its menu. */
    private void openNotebook(final String notebookId) {
        if (ctx.store.notebook(notebookId) == null) return;
        final BookScreen book = new BookScreen(ctx, notebookId, after -> openLegacyNotebook(notebookId, after));
        ctx.host.push(book.build(), book::onClosed, this::renderSeg);
    }

    /** The previous notebook page: its notes (with search and categories) and its images. Nothing was removed. */
    private void openLegacyNotebook(final String notebookId, final Runnable afterReveal) {
        final StudioStore.Notebook nb0 = ctx.store.notebook(notebookId);
        if (nb0 == null) return;
        Context c = ctx.act;
        LinearLayout page = ctx.pageRoot();
        View edit = ctx.iconActionButton(R.drawable.ic_edit, "إعادة تسمية الدفتر", null);
        View del = ctx.iconActionButton(R.drawable.ic_trash, "حذف الدفتر", null);
        final LinearLayout header = ctx.header(R.drawable.ic_notebook, nb0.name, v -> ctx.host.pop(), edit, del);
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));
        page.addView(ctx.hairline());

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        final LinearLayout holder = new LinearLayout(c);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(16), dp(14), dp(16), dp(24));
        sv.addView(holder, new FrameLayout.LayoutParams(-1, -2));
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        final NotesPanel panel = new NotesPanel(notebookId);
        holder.addView(panel.build());
        final LinearLayout imagesBox = new LinearLayout(c);
        imagesBox.setOrientation(LinearLayout.VERTICAL);
        holder.addView(imagesBox, lp(-1, -2, 0, 18, 0, 0));
        final Runnable[] draw = new Runnable[1];
        draw[0] = () -> drawNotebookImages(imagesBox, notebookId, draw[0]);
        draw[0].run();

        edit.setOnClickListener(v -> {
            StudioStore.Notebook cur = ctx.store.notebook(notebookId);
            if (cur == null) return;
            renameNotebook(cur, () -> {
                StudioStore.Notebook upd = ctx.store.notebook(notebookId);
                if (upd != null) StudioContext.setHeader(header, R.drawable.ic_notebook, upd.name);
            });
        });
        del.setOnClickListener(v -> {
            StudioStore.Notebook cur = ctx.store.notebook(notebookId);
            if (cur == null) return;
            confirmDeleteNotebook(cur, () -> ctx.host.pop());
        });
        ctx.host.push(page, null, () -> {
            panel.refresh();
            draw[0].run();
            if (afterReveal != null) afterReveal.run();
        });
    }

    private void drawNotebookImages(final LinearLayout box, final String notebookId, final Runnable redraw) {
        Context c = ctx.act;
        box.removeAllViews();
        box.addView(Ui.sectionTitle(c, "صور الدفتر"), lp(-1, -2, 0, 0, 0, 10));
        LinearLayout add = Ui.button(c, "إضافة صور إلى الدفتر", R.drawable.ic_plus, false);
        add.setOnClickListener(v -> pickImages(notebookId, redraw));
        box.addView(add, lp(-1, 50, 0, 0, 0, 10));
        final List<StudioStore.Item> imgs = ctx.store.medicalImages(notebookId, null);
        if (imgs.isEmpty()) {
            box.addView(Ui.text(c, "لا توجد صور في هذا الدفتر بعد.", 13, Ui.MUTED, false));
            return;
        }
        box.addView(StudioGrid.build(ctx, imgs, 120, null, new StudioGrid.Listener() {
            @Override
            public void onClick(StudioStore.Item item, int index) {
                ctx.openViewer(imgs, index, true, redraw);
            }

            @Override
            public void onLongClick(StudioStore.Item item, int index) {
                confirmDeleteImage(item, redraw);
            }
        }), new LinearLayout.LayoutParams(-1, -2));
    }

    // ------------------------------------------------------------------ medical images

    private void renderImages() {
        Context c = ctx.act;
        LinearLayout add = Ui.button(c, "إضافة صور طبية", R.drawable.ic_plus, true);
        add.setOnClickListener(v -> pickImages("", this::renderSeg));
        segBody.addView(add, lp(-1, 54, 0, 0, 0, 12));

        final List<StudioStore.Item> imgs = ctx.store.medicalImages(null, null);
        segBody.addView(Ui.text(c, "عدد الصور: " + imgs.size(), 14, Ui.LAV, true), lp(-1, -2, 0, 0, 0, 6));
        segBody.addView(Ui.text(c, "اضغط على صورة لتكبيرها وكتابة وصف لها. اضغط مطولاً للحذف.", 12, Ui.MUTED, false),
                lp(-1, -2, 0, 0, 0, 10));
        if (imgs.isEmpty()) {
            segBody.addView(Ui.emptyState(c, "لا توجد صور طبية بعد", "أضف صور الأشعة أو الرسوم أو الشرائح من هاتفك."),
                    new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        segBody.addView(StudioGrid.build(ctx, imgs, imagesLimit, null, new StudioGrid.Listener() {
            @Override
            public void onClick(StudioStore.Item item, int index) {
                ctx.openViewer(imgs, index, true, MedicalScreen.this::renderSeg);
            }

            @Override
            public void onLongClick(StudioStore.Item item, int index) {
                confirmDeleteImage(item, MedicalScreen.this::renderSeg);
            }
        }), new LinearLayout.LayoutParams(-1, -2));
        if (imgs.size() > imagesLimit) {
            LinearLayout more = Ui.button(c, "عرض المزيد (" + (imgs.size() - imagesLimit) + ")", 0, false);
            more.setOnClickListener(v -> {
                imagesLimit += 60;
                renderSeg();
            });
            segBody.addView(more, lp(-1, 48, 0, 12, 0, 0));
        }
    }

    private void pickImages(final String notebookId, final Runnable after) {
        ctx.host.pickFiles(new String[] {"image/*"}, true, (List<Uri> uris) -> {
            if (uris == null || uris.isEmpty()) return;
            ctx.importPicked(uris, StudioStore.SCOPE_MEDICAL, "", notebookId, "", "image", after);
        });
    }

    private void confirmDeleteImage(final StudioStore.Item it, final Runnable after) {
        ctx.confirm("حذف الصورة", "ستُحذف «" + it.name + "» نهائياً.", "حذف", () -> {
            List<String> ids = new ArrayList<>();
            ids.add(it.id);
            ctx.store.deleteItems(ids);
            after.run();
        });
    }
}
