package com.hajala.app;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Settings page: check for updates, download + install, and full backup / restore. */
final class UpdateScreen {
    interface Host {
        void close();

        /** Opens the system "save as" picker; the callback gets the chosen document Uri (or nothing if cancelled). */
        void pickSaveLocation(String fileName, StudioContext.UriCallback cb);

        void pickBackupFile(StudioContext.UriCallback cb);

        /** Writes any unsaved note text to disk (called before a backup). */
        void flushPending();

        /** Reload every screen/store after a restore. */
        void restart();
    }

    private final Activity act;
    private final Host host;
    private LinearLayout body;
    private TextView status;
    private LinearLayout updateBox;
    private LinearLayout autoList;
    private LinearLayout checkBtn;
    private LinearLayout installBtn;
    private TextView progressText;
    private AppUpdater.Info latest;
    private boolean backupFirst = true;
    private boolean busy;
    private AtomicBoolean cancel = new AtomicBoolean(false);

    UpdateScreen(Activity act, Host host) {
        this.act = act;
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

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(20), dp(10));
        FrameLayout back = Ui.iconButton(c, R.drawable.ic_back, "رجوع");
        back.setOnClickListener(v -> host.close());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = Ui.text(c, "التحديثات والنسخ الاحتياطي", 21, Ui.LAV, true);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, -2, 1f);
        tLp.setMarginStart(dp(12));
        header.addView(title, tLp);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(c);
        line.setBackgroundColor(Ui.BORDER);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(14), dp(20), dp(24));
        sv.addView(body, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- version card
        LinearLayout card = card();
        card.addView(Ui.text(c, "الإصدار الحالي", 14, Ui.MUTED, false));
        card.addView(Ui.text(c, AppUpdater.currentName(c) + "  (" + AppUpdater.currentCode(c) + ")", 22, Ui.TEXT, true));
        TextView schema = Ui.text(c, "إصدار بنية البيانات: " + AppMigrations.storedSchema(c), 13, Ui.MUTED, false);
        schema.setPadding(0, dp(4), 0, 0);
        card.addView(schema);
        String migErr = AppMigrations.lastError(c);
        if (migErr != null && !migErr.isEmpty()) {
            TextView e = Ui.text(c, "تعذّر ترحيل البيانات في المرة الماضية، وتمت استعادة بياناتك كما كانت.", 13, Ui.ERROR, false);
            e.setPadding(0, dp(6), 0, 0);
            card.addView(e);
        }
        status = Ui.text(c, "", 14, Ui.MUTED, false);
        status.setPadding(0, dp(10), 0, dp(10));
        card.addView(status);
        checkBtn = Ui.button(c, "التحقق من التحديثات", 0, true);
        checkBtn.setOnClickListener(v -> checkNow());
        card.addView(checkBtn, new LinearLayout.LayoutParams(-1, dp(50)));
        body.addView(card, bottom(14));

        // ---- update card (hidden until an update is found)
        updateBox = card();
        updateBox.setVisibility(View.GONE);
        body.addView(updateBox, bottom(14));

        // ---- backup
        body.addView(Ui.sectionTitle(c, "النسخ الاحتياطي"), bottom(10));
        LinearLayout bk = card();
        TextView hint = Ui.text(c, "النسخة الكاملة تحفظ الدفاتر والصفحات والملاحظات والمهام والمجلدات والمفضلة وسجل القراءة "
                + "والإعدادات والصور. احفظها في مكان خارج التطبيق (التنزيلات أو Drive).", 13, Ui.MUTED, false);
        hint.setPadding(0, 0, 0, dp(10));
        bk.addView(hint);
        LinearLayout exp = Ui.button(c, "تصدير نسخة احتياطية", 0, false);
        exp.setOnClickListener(v -> askExport());
        bk.addView(exp, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout res = Ui.button(c, "استعادة من ملف", 0, false);
        res.setOnClickListener(v -> host.pickBackupFile(uris -> {
            if (uris != null && !uris.isEmpty()) confirmRestoreUri(uris.get(0));
        }));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, dp(48));
        rLp.topMargin = dp(10);
        bk.addView(res, rLp);
        body.addView(bk, bottom(14));

        body.addView(Ui.sectionTitle(c, "نسخ تلقائية داخل التطبيق"), bottom(10));
        autoList = card();
        body.addView(autoList, bottom(14));
        refreshAuto();

        AppUpdater.Info known = AppUpdater.lastKnown(c);
        if (known != null && AppUpdater.isNewer(c, known)) showUpdate(known);
        else status.setText("لم يتم التحقق في هذه الجلسة");
        return root;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(15), dp(16), dp(15));
        l.setBackground(Ui.rounded(act, Ui.CARD, 0x6675416F, 1, 20));
        return l;
    }

    // ------------------------------------------------------------------ check

    private void checkNow() {
        if (busy) return;
        status.setTextColor(Ui.MUTED);
        status.setText("جارٍ التحقق...");
        AppUpdater.check(act, (info, error) -> {
            if (error != null) {
                status.setTextColor(Ui.ERROR);
                status.setText(error);
                return;
            }
            if (AppUpdater.isNewer(act, info)) {
                showUpdate(info);
            } else {
                status.setTextColor(Ui.MUTED);
                status.setText("أنت تستخدم أحدث إصدار ✓");
                updateBox.setVisibility(View.GONE);
            }
        });
    }

    private void showUpdate(AppUpdater.Info info) {
        latest = info;
        status.setTextColor(Ui.LAV);
        status.setText("يتوفر إصدار جديد");
        updateBox.removeAllViews();
        updateBox.setVisibility(View.VISIBLE);
        Context c = act;
        updateBox.addView(Ui.text(c, "تحديث متوفر", 18, Ui.LAV, true));
        TextView ver = Ui.text(c, AppUpdater.currentName(c) + "  ←  " + info.versionName
                + (info.size > 0 ? "   ·   " + VersionUtil.formatBytes(info.size) : ""), 15, Ui.TEXT, true);
        ver.setPadding(0, dp(6), 0, dp(8));
        updateBox.addView(ver);
        if (!info.notes.isEmpty()) {
            updateBox.addView(Ui.text(c, "سجل التغييرات", 13, Ui.MUTED, false));
            TextView notes = Ui.text(c, info.notes, 14, Ui.TEXT, false);
            notes.setTextIsSelectable(true);
            notes.setPadding(0, dp(4), 0, dp(10));
            updateBox.addView(notes);
        }
        final TextView chip = Ui.chip(c, "حفظ نسخة احتياطية تلقائية قبل التحديث", backupFirst);
        chip.setOnClickListener(v -> {
            backupFirst = !backupFirst;
            Ui.styleChip(act, chip, backupFirst);
        });
        updateBox.addView(chip, bottom(10));
        progressText = Ui.text(c, "", 13, Ui.MUTED, false);
        progressText.setPadding(0, 0, 0, dp(8));
        updateBox.addView(progressText);
        installBtn = Ui.button(c, "تحميل وتثبيت", 0, true);
        installBtn.setOnClickListener(v -> startUpdate());
        updateBox.addView(installBtn, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView safe = Ui.text(c, "التثبيت يتم فوق النسخة الحالية، ولا يحذف بياناتك.", 12, Ui.MUTED, false);
        safe.setPadding(0, dp(8), 0, 0);
        updateBox.addView(safe);
    }

    // ------------------------------------------------------------------ update

    private void startUpdate() {
        if (busy || latest == null) return;
        final AppUpdater.Info info = latest;
        busy = true;
        cancel = new AtomicBoolean(false);
        setInstallLabel("إلغاء");
        installBtn.setOnClickListener(v -> cancel.set(true));
        host.flushPending();
        if (!backupFirst) {
            downloadAndInstall(info);
            return;
        }
        progressText.setTextColor(Ui.MUTED);
        progressText.setText("جارٍ حفظ نسخة احتياطية...");
        Ui.IO.execute(() -> {
            String err = null;
            try {
                FullBackup.autoSnapshot(act, "before-update-" + info.versionName);
            } catch (Exception e) {
                err = "تعذّر حفظ النسخة الاحتياطية، فتم إيقاف التحديث حفاظاً على بياناتك";
            }
            final String fe = err;
            act.runOnUiThread(() -> {
                if (fe != null) {
                    failUpdate(fe);
                    return;
                }
                refreshAuto();
                downloadAndInstall(info);
            });
        });
    }

    private void downloadAndInstall(final AppUpdater.Info info) {
        progressText.setTextColor(Ui.MUTED);
        progressText.setText("جارٍ التحميل...");
        AppUpdater.download(act, info, cancel, (done, total) -> {
            if (total > 0) {
                progressText.setText("جارٍ التحميل... " + (int) (done * 100 / total) + "%  ("
                        + VersionUtil.formatBytes(done) + " / " + VersionUtil.formatBytes(total) + ")");
            } else {
                progressText.setText("جارٍ التحميل... " + VersionUtil.formatBytes(done));
            }
        }, (file, error) -> {
            if (error != null) {
                failUpdate(error);
                return;
            }
            busy = false;
            setInstallLabel("تثبيت");
            installBtn.setOnClickListener(v -> installNow(file));
            progressText.setTextColor(Ui.LAV);
            progressText.setText("تم التحميل والتحقق من الملف ✓");
            installNow(file);
        });
    }

    private void installNow(File file) {
        String msg = AppUpdater.install(act, file);
        if (msg != null) {
            progressText.setTextColor(Ui.ERROR);
            progressText.setText(msg);
        } else {
            progressText.setTextColor(Ui.LAV);
            progressText.setText("أكمل التثبيت في نافذة النظام. ستجد كل بياناتك كما هي.");
        }
    }

    private void failUpdate(String msg) {
        busy = false;
        progressText.setTextColor(Ui.ERROR);
        progressText.setText(msg);
        setInstallLabel("تحميل وتثبيت");
        installBtn.setOnClickListener(v -> startUpdate());
    }

    private void setInstallLabel(String label) {
        if (installBtn == null) return;
        for (int i = 0; i < installBtn.getChildCount(); i++) {
            View ch = installBtn.getChildAt(i);
            if (ch instanceof TextView) ((TextView) ch).setText(label);
        }
    }

    // ------------------------------------------------------------------ backup / restore

    private void askExport() {
        Ui.show(Ui.dialog(act).setTitle("تصدير نسخة احتياطية")
                .setMessage("«كاملة» تتضمن كل الصور والملفات والفيديو وقد تكون كبيرة. «خفيفة» تحفظ الدفاتر والنصوص والإعدادات فقط.")
                .setPositiveButton("كاملة", (d, w) -> export(true))
                .setNeutralButton("خفيفة", (d, w) -> export(false))
                .setNegativeButton("إلغاء", null));
    }

    private void export(final boolean media) {
        host.flushPending();
        host.pickSaveLocation(FullBackup.suggestedName(), uris -> {
            if (uris == null || uris.isEmpty()) return;
            final Uri uri = uris.get(0);
            Ui.toast(act, "جارٍ إنشاء النسخة...");
            Ui.IO.execute(() -> {
                String err = null;
                try (OutputStream os = act.getContentResolver().openOutputStream(uri, "wt")) {
                    if (os == null) throw new Exception("open");
                    FullBackup.export(act, os, media, null);
                } catch (Exception e) {
                    err = "فشل إنشاء النسخة الاحتياطية";
                }
                final String fe = err;
                act.runOnUiThread(() -> Ui.toast(act, fe == null ? "تم حفظ النسخة الاحتياطية ✓" : fe));
            });
        });
    }

    private void confirmRestoreUri(final Uri uri) {
        Ui.show(Ui.dialog(act).setTitle("استعادة نسخة احتياطية")
                .setMessage("ستُدمج بيانات النسخة مع بياناتك الحالية. لن يُحذف أي ملف، وتُحفظ نسخة أمان تلقائية قبل الاستعادة.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("استعادة", (d, w) -> {
                    Ui.toast(act, "جارٍ الاستعادة...");
                    Ui.IO.execute(() -> {
                        String err = null;
                        try {
                            File f = FullBackup.stage(act, uri);
                            FullBackup.restore(act, f);
                        } catch (Exception e) {
                            err = e.getMessage() == null || e.getMessage().isEmpty() ? "فشلت الاستعادة" : e.getMessage();
                        }
                        finishRestore(err);
                    });
                }));
    }

    private void confirmRestoreFile(final File f) {
        Ui.show(Ui.dialog(act).setTitle("استعادة النسخة التلقائية")
                .setMessage("ستُدمج بيانات هذه النسخة مع بياناتك الحالية. لن يُحذف أي ملف.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("استعادة", (d, w) -> {
                    Ui.toast(act, "جارٍ الاستعادة...");
                    Ui.IO.execute(() -> {
                        String err = null;
                        try {
                            FullBackup.restore(act, f);
                        } catch (Exception e) {
                            err = e.getMessage() == null || e.getMessage().isEmpty() ? "فشلت الاستعادة" : e.getMessage();
                        }
                        finishRestore(err);
                    });
                }));
    }

    private void finishRestore(final String err) {
        act.runOnUiThread(() -> {
            if (err != null) {
                Ui.toast(act, err);
                return;
            }
            Ui.toast(act, "تمت الاستعادة ✓");
            host.restart();
        });
    }

    private void refreshAuto() {
        autoList.removeAllViews();
        List<File> list = FullBackup.autoBackups(act);
        if (list.isEmpty()) {
            autoList.addView(Ui.text(act, "لا توجد نسخ تلقائية بعد. تُنشأ قبل كل تحديث وقبل أي استعادة.", 13, Ui.MUTED, false));
            return;
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (final File f : list) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView t = Ui.text(act, df.format(new Date(f.lastModified())) + "  ·  " + VersionUtil.formatBytes(f.length()),
                    13, Ui.TEXT, false);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView r = Ui.pill(act, "استعادة", Ui.LAV, Ui.SURFACE, Ui.BORDER);
            r.setOnClickListener(v -> confirmRestoreFile(f));
            row.addView(r, new LinearLayout.LayoutParams(-2, dp(34)));
            autoList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
