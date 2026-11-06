package com.hajala.app;

import android.app.Activity;
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.Executor;

/**
 * PIN pad and optional fingerprint prompt. Fingerprint uses the platform BiometricPrompt (API 28+);
 * every older device, and every failed fingerprint, falls back to the PIN. Nothing leaves the device.
 */
final class LockUi {
    private LockUi() {}

    static boolean fingerprintAvailable(Context c) {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            return c.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT);
        } catch (Exception e) {
            return false;
        }
    }

    /** Full-screen lock. [onSuccess] runs on the UI thread; the caller removes the view. */
    static View lockScreen(Activity act, StudioLock lock, String title, Runnable onSuccess) {
        return buildPad(act, lock, title, "أدخل الرمز السري", true, onSuccess);
    }

    /** Dialog used to confirm the PIN before opening a locked album or changing the lock. */
    static void verify(StudioContext ctx, String title, Runnable onSuccess) {
        Activity act = ctx.act;
        StudioLock lock = ctx.lock;
        if (!lock.enabled()) {
            onSuccess.run();
            return;
        }
        final EditText input = Ui.input(act, "الرمز السري", false);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(StudioLock.MAX_PIN)});
        final TextView error = Ui.text(act, "", 13, Ui.ERROR, false);
        error.setVisibility(View.GONE);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        box.addView(error, new LinearLayout.LayoutParams(-1, -2));
        final android.app.AlertDialog d = Ui.show(Ui.dialog(act).setTitle(title).setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("فتح", null));
        if (lock.biometric() && fingerprintAvailable(act)) {
            d.setButton(android.app.AlertDialog.BUTTON_NEUTRAL, "البصمة", (di, w) -> { });
            d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    fingerprint(act, title, () -> {
                        if (d.isShowing()) d.dismiss();
                        onSuccess.run();
                    }));
        }
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = input.getText().toString().trim();
            if (lock.verify(pin)) {
                d.dismiss();
                onSuccess.run();
            } else {
                error.setText("الرمز غير صحيح");
                error.setVisibility(View.VISIBLE);
            }
        });
        if (lock.biometric() && fingerprintAvailable(act)) {
            input.post(() -> fingerprint(act, title, () -> {
                if (d.isShowing()) d.dismiss();
                onSuccess.run();
            }));
        }
    }

    private static View buildPad(Activity act, StudioLock lock, String title, String hint, boolean offerBio,
                                 Runnable onSuccess) {
        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(Ui.BG);
        root.setClickable(true);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(act, 24), dp(act, 28), dp(act, 24), dp(act, 20));

        col.addView(Ui.icon(act, R.drawable.ic_lock, Ui.LAV, 36), lp(act, 36, 36));
        TextView h = Ui.centered(Ui.text(act, title, 22, Ui.LAV, true));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(act, 12);
        col.addView(h, hp);
        TextView sub = Ui.centered(Ui.text(act, hint, 14, Ui.MUTED, false));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(act, 6);
        sp.bottomMargin = dp(act, 16);
        col.addView(sub, sp);

        final StringBuilder pin = new StringBuilder();
        final TextView dots = Ui.centered(Ui.text(act, "", 28, Ui.TEXT, true));
        dots.setLetterSpacing(0.4f);
        col.addView(dots, new LinearLayout.LayoutParams(-1, dp(act, 48)));
        final TextView error = Ui.centered(Ui.text(act, "", 13, Ui.ERROR, false));
        col.addView(error, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout pad = new LinearLayout(act);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"", "0", "del"}};
        for (String[] row : keys) {
            LinearLayout line = new LinearLayout(act);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (String key : row) {
                View cell;
                if (key.isEmpty()) {
                    cell = new View(act);
                } else if ("del".equals(key)) {
                    FrameLayout b = Ui.iconButton(act, R.drawable.ic_back, "حذف رقم");
                    b.setOnClickListener(v -> {
                        if (pin.length() > 0) pin.deleteCharAt(pin.length() - 1);
                        paint(dots, pin.length());
                        error.setText("");
                    });
                    cell = b;
                } else {
                    final String digit = key;
                    TextView b = Ui.centered(Ui.text(act, digit, 22, Ui.TEXT, true));
                    b.setBackground(Ui.ripple(act, Ui.rounded(act, Ui.SURFACE, Ui.BORDER, 1, 28), 28));
                    b.setClickable(true);
                    b.setOnClickListener(v -> {
                        if (pin.length() >= StudioLock.MAX_PIN) return;
                        pin.append(digit);
                        paint(dots, pin.length());
                        error.setText("");
                        if (lock.enabled() && pin.length() == lock.pinLength()) {
                            if (lock.verify(pin.toString())) onSuccess.run();
                            else {
                                pin.setLength(0);
                                paint(dots, 0);
                                error.setText("الرمز غير صحيح");
                            }
                        }
                    });
                    Ui.press(b);
                    cell = b;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(act, 72), dp(act, 56));
                lp.setMargins(dp(act, 8), dp(act, 8), dp(act, 8), dp(act, 8));
                line.addView(cell, lp);
            }
            pad.addView(line, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.topMargin = dp(act, 8);
        col.addView(pad, pp);

        if (offerBio && lock.biometric() && fingerprintAvailable(act)) {
            LinearLayout bio = Ui.button(act, "استخدام البصمة", 0, false);
            bio.setOnClickListener(v -> fingerprint(act, title, onSuccess));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(act, 48));
            bp.topMargin = dp(act, 16);
            col.addView(bio, bp);
            col.post(() -> fingerprint(act, title, onSuccess));
        }

        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        return root;
    }

    /** First-time or change-PIN pad. Calls [onPin] with a confirmed 4–6 digit PIN. */
    static View enrollScreen(Activity act, String title, java.util.function.Consumer<String> onPin) {
        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(Ui.BG);
        root.setClickable(true);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(act, 24), dp(act, 24), dp(act, 24), dp(act, 16));
        col.addView(Ui.centered(Ui.text(act, title, 20, Ui.LAV, true)), new LinearLayout.LayoutParams(-1, -2));
        TextView hint = Ui.centered(Ui.text(act, "اختر رمزاً من 4 إلى 6 أرقام", 14, Ui.MUTED, false));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(act, 8);
        hp.bottomMargin = dp(act, 12);
        col.addView(hint, hp);
        final StringBuilder pin = new StringBuilder();
        final String[] first = {null};
        final TextView dots = Ui.centered(Ui.text(act, "", 28, Ui.TEXT, true));
        col.addView(dots, new LinearLayout.LayoutParams(-1, dp(act, 48)));
        final TextView error = Ui.centered(Ui.text(act, "", 13, Ui.ERROR, false));
        col.addView(error, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout pad = new LinearLayout(act);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"ok", "0", "del"}};
        for (String[] row : keys) {
            LinearLayout line = new LinearLayout(act);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (String key : row) {
                View cell;
                if ("del".equals(key)) {
                    FrameLayout b = Ui.iconButton(act, R.drawable.ic_back, "حذف");
                    b.setOnClickListener(v -> {
                        if (pin.length() > 0) pin.deleteCharAt(pin.length() - 1);
                        paint(dots, pin.length());
                    });
                    cell = b;
                } else if ("ok".equals(key)) {
                    TextView b = Ui.centered(Ui.text(act, "تم", 16, Ui.LAV, true));
                    b.setBackground(Ui.ripple(act, Ui.rounded(act, Ui.CARD, Ui.LAV, 1, 28), 28));
                    b.setClickable(true);
                    b.setOnClickListener(v -> {
                        String cur = pin.toString();
                        if (!StudioLock.validPin(cur)) {
                            error.setText("الرمز من 4 إلى 6 أرقام");
                            return;
                        }
                        if (first[0] == null) {
                            first[0] = cur;
                            pin.setLength(0);
                            paint(dots, 0);
                            hint.setText("أعد إدخال الرمز للتأكيد");
                            error.setText("");
                        } else if (!first[0].equals(cur)) {
                            error.setText("الرمزان غير متطابقين");
                            pin.setLength(0);
                            paint(dots, 0);
                        } else {
                            onPin.accept(cur);
                        }
                    });
                    Ui.press(b);
                    cell = b;
                } else {
                    final String digit = key;
                    TextView b = Ui.centered(Ui.text(act, digit, 22, Ui.TEXT, true));
                    b.setBackground(Ui.ripple(act, Ui.rounded(act, Ui.SURFACE, Ui.BORDER, 1, 28), 28));
                    b.setClickable(true);
                    b.setOnClickListener(v -> {
                        if (pin.length() >= StudioLock.MAX_PIN) return;
                        pin.append(digit);
                        paint(dots, pin.length());
                        error.setText("");
                    });
                    Ui.press(b);
                    cell = b;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(act, 72), dp(act, 56));
                lp.setMargins(dp(act, 8), dp(act, 8), dp(act, 8), dp(act, 8));
                line.addView(cell, lp);
            }
            pad.addView(line, new LinearLayout.LayoutParams(-2, -2));
        }
        col.addView(pad, new LinearLayout.LayoutParams(-2, -2));
        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        return root;
    }

    private static void paint(TextView dots, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("●");
        dots.setText(sb.toString());
    }

    private static void fingerprint(Activity act, String title, Runnable onSuccess) {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            Executor ex = act.getMainExecutor();
            BiometricPrompt prompt = new BiometricPrompt.Builder(act)
                    .setTitle(title)
                    .setSubtitle("أو استخدم الرمز السري")
                    .setNegativeButton("الرمز", ex, (d, w) -> { })
                    .build();
            prompt.authenticate(new CancellationSignal(), ex, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    act.runOnUiThread(onSuccess);
                }
            });
        } catch (Exception ignored) {
            // PIN pad stays available
        }
    }

    private static int dp(Context c, float v) {
        return Ui.dp(c, v);
    }

    private static LinearLayout.LayoutParams lp(Context c, int w, int h) {
        return new LinearLayout.LayoutParams(dp(c, w), dp(c, h));
    }
}
