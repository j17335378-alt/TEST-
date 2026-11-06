package com.hajala.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared services for all Studio screens: storage, dialogs, importing, navigation and the common header. */
final class StudioContext {
    interface UriCallback {
        void onPicked(List<Uri> uris);
    }

    interface TextCallback {
        void onText(String text);
    }

    interface IndexCallback {
        void onIndex(int index);
    }

    /** Implemented by NativeHomeActivity: full-screen pages and the Android file picker. */
    interface Host {
        void push(View v, Runnable onPop, Runnable onRevealed);

        /** While set, "back" asks this first; if it returns true the press was used and the page stays open. */
        void setBackInterceptor(java.util.function.BooleanSupplier interceptor);

        void pop();

        /** Opens the native Android picker (no storage permission needed). */
        void pickFiles(String[] mimeTypes, boolean multiple, UriCallback cb);
    }

    private static final ExecutorService IMPORT = Executors.newSingleThreadExecutor();

    final Activity act;
    final StudioStore store;
    final StudioLock lock;
    final Host host;
    private Runnable flushHook;

    StudioContext(Activity act, StudioStore store, Host host) {
        this.act = act;
        this.store = store;
        this.host = host;
        this.lock = new StudioLock(act);
    }

    int dp(float v) {
        return Ui.dp(act, v);
    }

    void toast(String s) {
        Ui.toast(act, s);
    }

    // ------------------------------------------------------------------ flush on pause

    /** The note editor registers a hook that writes unsaved text immediately. */
    void setFlushHook(Runnable r) {
        flushHook = r;
    }

    void flush() {
        Runnable r = flushHook;
        if (r != null) r.run();
    }

    // ------------------------------------------------------------------ dialogs

    void confirm(String title, String message, String yes, final Runnable onYes) {
        AlertDialog.Builder b = Ui.dialog(act).setTitle(title).setMessage(message)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton(yes, (d, w) -> onYes.run());
        Ui.show(b);
    }

    /** Text prompt; stays open (and explains why) until the text is acceptable. [validator] returns an error or null. */
    void prompt(String title, String hint, String initial, String okLabel, final TextCallback cb,
                final java.util.function.Function<String, String> validator) {
        final EditText input = Ui.input(act, hint, false);
        input.setText(initial == null ? "" : initial);
        input.setSelection(input.getText().length());
        final TextView error = Ui.text(act, "", 13, Ui.ERROR, false);
        error.setVisibility(View.GONE);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        box.addView(error, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog d = Ui.show(Ui.dialog(act).setTitle(title).setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton(okLabel, null));
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = StudioStore.clean(input.getText().toString());
            String err = validator == null ? (text.isEmpty() ? "اكتب اسماً أولاً" : null) : validator.apply(text);
            if (err != null) {
                error.setText(err);
                error.setVisibility(View.VISIBLE);
                return;
            }
            d.dismiss();
            cb.onText(text);
        });
        input.requestFocus();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    void choose(String title, String[] labels, final IndexCallback cb) {
        Ui.show(Ui.dialog(act).setTitle(title).setItems(labels, (d, which) -> cb.onIndex(which)));
    }

    // ------------------------------------------------------------------ importing

    /**
     * Copies the picked Uris into private storage on a background thread with a progress dialog, then reports
     * how many files were saved and which ones failed. [onDone] runs on the UI thread afterwards.
     */
    void importPicked(final List<Uri> uris, final String scope, final String folderId, final String notebookId,
                      final String noteId, final String accept, final Runnable onDone) {
        if (uris == null || uris.isEmpty()) return;
        final AlertDialog dlg = Ui.show(Ui.dialog(act).setTitle("جارٍ الحفظ")
                .setMessage("0 / " + uris.size()).setCancelable(false));
        IMPORT.execute(() -> {
            final StudioStore.ImportResult res = store.importUris(uris, scope, folderId, notebookId, noteId, accept,
                    (done, total, name) -> act.runOnUiThread(() -> {
                        if (dlg.isShowing()) dlg.setMessage((done + 1 > total ? total : done + 1) + " / " + total
                                + (name.isEmpty() ? "" : "\n" + name));
                    }));
            act.runOnUiThread(() -> {
                if (dlg.isShowing()) dlg.dismiss();
                if (!res.added.isEmpty()) toast("تم حفظ " + res.added.size() + " ملف");
                if (!res.errors.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < res.errors.size() && i < 8; i++) sb.append("• ").append(res.errors.get(i)).append('\n');
                    if (res.errors.size() > 8) sb.append("… و").append(res.errors.size() - 8).append(" أخرى");
                    Ui.show(Ui.dialog(act).setTitle(res.added.isEmpty() ? "لم يُحفظ أي ملف" : "تعذّر حفظ بعض الملفات")
                            .setMessage(sb.toString().trim()).setPositiveButton("حسناً", null));
                }
                if (onDone != null) onDone.run();
            });
        });
    }

    // ------------------------------------------------------------------ viewer / external open

    void openViewer(List<StudioStore.Item> list, int index, boolean captions, Runnable onChanged) {
        MediaViewerScreen v = new MediaViewerScreen(this, list, index, captions, onChanged);
        host.push(v.build(), () -> {
            host.setBackInterceptor(null);
            v.onClosed();
        }, null);
        host.setBackInterceptor(v::onBackPressed);
    }

    /** Opens any stored file with another installed app through the app's private file provider. */
    void openExternal(StudioStore.Item it) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            String mime = it.mime.isEmpty() ? "*/*" : it.mime;
            i.setDataAndType(StudioFileProvider.uriFor(it), mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(i);
        } catch (Exception e) {
            toast("لا يوجد تطبيق على الهاتف يفتح هذا النوع من الملفات");
        }
    }

    // ------------------------------------------------------------------ common header

    /** Header row used by every full-screen Studio page: back button, title, optional trailing views. */
    LinearLayout header(String title, View.OnClickListener onBack, View... trailing) {
        return header(0, title, onBack, trailing);
    }

    /** Same, with a small vector icon before the title (pass 0 for none). */
    LinearLayout header(int iconRes, String title, View.OnClickListener onBack, View... trailing) {
        Context c = act;
        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(16), dp(12), dp(16), dp(10));
        FrameLayout back = Ui.iconButton(c, R.drawable.ic_back, "رجوع");
        back.setOnClickListener(onBack);
        h.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iLp.setMarginStart(dp(12));
        android.widget.ImageView ic = Ui.icon(c, iconRes == 0 ? R.drawable.ic_folder : iconRes, Ui.LAV, 22);
        ic.setTag(TAG_HEADER_ICON);
        if (iconRes == 0) ic.setVisibility(View.GONE);
        h.addView(ic, iLp);
        TextView t = Ui.text(c, title, 20, Ui.LAV, true);
        t.setTag(TAG_HEADER_TITLE);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, -2, 1f);
        tLp.setMarginStart(dp(iconRes == 0 ? 12 : 10));
        tLp.setMarginEnd(dp(8));
        h.addView(t, tLp);
        for (View v : trailing) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.setMarginStart(dp(6));
            h.addView(v, lp);
        }
        return h;
    }

    private static final String TAG_HEADER_TITLE = "hdr_title";
    private static final String TAG_HEADER_ICON = "hdr_icon";

    /** Updates the title (and icon, when [iconRes] != 0) of a header made by [header]. */
    static void setHeader(LinearLayout h, int iconRes, String title) {
        TextView t = h.findViewWithTag(TAG_HEADER_TITLE);
        if (t != null) {
            t.setText(title);
            Ui.applyFont(t, true);
        }
        if (iconRes != 0) {
            android.widget.ImageView ic = h.findViewWithTag(TAG_HEADER_ICON);
            if (ic != null) {
                ic.setImageResource(iconRes);
                ic.setVisibility(View.VISIBLE);
            }
        }
    }

    View iconActionButton(int drawable, String description, View.OnClickListener l) {
        FrameLayout f = Ui.iconButton(act, drawable, description);
        f.setOnClickListener(l);
        return f;
    }

    /** A new full-screen page root with the Arcanum background that swallows touches. */
    LinearLayout pageRoot() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setClickable(true);
        return root;
    }

    View hairline() {
        View v = new View(act);
        v.setBackgroundColor(Ui.BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return v;
    }

    static List<String> ids(List<StudioStore.Item> list) {
        List<String> out = new ArrayList<>();
        for (StudioStore.Item it : list) out.add(it.id);
        return out;
    }
}
