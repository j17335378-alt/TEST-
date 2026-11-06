package com.hajala.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;

/**
 * ARCANUM's own video player: a [VideoView] for decoding plus a custom control layer (no system MediaController).
 *
 * Control visibility rules:
 * - a touch on the screen shows the controls immediately; they fade out by themselves after [HIDE_DELAY_MS];
 * - the timer is frozen while a finger is down anywhere on the player and restarts when it lifts, so pressing a
 *   button or dragging the progress bar never makes the controls vanish under the finger;
 * - while the video is paused (or finished) the controls stay visible; pressing play starts the timer again.
 */
final class VideoPlayerView extends FrameLayout {
    /** Callbacks to the screen that hosts the player. */
    interface Listener {
        /** The controls faded in or out: the host fades its own top bar with them. */
        void onControlsVisibility(boolean visible);

        void onPrevious();

        void onNext();

        /** Full screen was entered or left: the host hides what is not needed while the picture fills the screen. */
        void onFullscreenChanged(boolean fullscreen);

        /** The video cannot be played inside the app. */
        void onVideoError();
    }

    /** How long the controls stay on screen after the last touch while the video is playing. */
    static final long HIDE_DELAY_MS = 2400;
    private static final long TICK_MS = 200;
    private static final long SCRUB_SEEK_GAP_MS = 120;

    /** Mute state survives from one video to the next within a session. */
    private static boolean muted;

    private final Activity act;
    private Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final VideoView video;
    private final ProgressBar spinner;
    private final LinearLayout centerCluster;
    private final FrameLayout playBtn;
    private final FrameLayout prevBtn;
    private final FrameLayout nextBtn;
    private final LinearLayout bottomPanel;
    private final ScrubBar scrub;
    private final TextView timeText;
    private final FrameLayout muteBtn;
    private final FrameLayout fullBtn;

    private MediaPlayer player;
    private boolean prepared;
    private boolean ended;
    private boolean controlsVisible = false;
    private boolean dragging;
    private boolean touching; // a finger is down somewhere on the player: the countdown is frozen
    private boolean resumeAfterScrub;
    private boolean fullscreen;
    private boolean released;
    private int videoW;
    private int videoH;
    private int durationMs;
    private int lastShownSec = -1;
    private int lastShownTotalSec = -1;
    private long lastScrubSeek;
    private int savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean compact;
    private boolean audioFocus;

    /** Another app started playing (call, music...): pause instead of talking over it. */
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (released) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            audioFocus = false;
            pauseForInterruption();
        }
    };

    private final Runnable hideRunnable = this::hideControlsNow;
    private final Runnable hideHostChromeRunnable = () -> {
        if (!released && listener != null) listener.onControlsVisibility(false);
    };
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (controlsVisible && !released) handler.postDelayed(this, TICK_MS);
        }
    };

    VideoPlayerView(Activity act, Listener listener) {
        super(act);
        this.act = act;
        this.listener = listener;
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // media time always runs left to right
        setBackgroundColor(0xFF000000);

        video = new VideoView(act);
        addView(video, new LayoutParams(-1, -1, Gravity.CENTER)); // VideoView keeps the aspect ratio and centres it

        spinner = new ProgressBar(act);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.LAV));
        addView(spinner, new LayoutParams(dp(36), dp(36), Gravity.CENTER));

        // transparent layer that receives touches on the picture itself
        View touch = new View(act);
        touch.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (!controlsVisible) {
                    showControls();
                    return true;
                }
                // tap on the picture while the controls are showing: hide them (even when paused)
                hideControlsForce();
                return true;
            }
            return false;
        });
        addView(touch, new LayoutParams(-1, -1));

        // ---- centre cluster: previous / play-pause / next
        centerCluster = new LinearLayout(act);
        centerCluster.setOrientation(LinearLayout.HORIZONTAL);
        centerCluster.setGravity(Gravity.CENTER);
        prevBtn = Ui.glassButton(act, R.drawable.ic_skip_prev, "الفيديو السابق", 44, 18, Ui.TEXT);
        prevBtn.setOnClickListener(v -> {
            if (this.listener != null) this.listener.onPrevious();
        });
        playBtn = bigPlayButton();
        playBtn.setOnClickListener(v -> togglePlay());
        nextBtn = Ui.glassButton(act, R.drawable.ic_skip_next, "الفيديو التالي", 44, 18, Ui.TEXT);
        nextBtn.setOnClickListener(v -> {
            if (this.listener != null) this.listener.onNext();
        });
        centerCluster.addView(prevBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(58), dp(58));
        pl.setMarginStart(dp(14));
        pl.setMarginEnd(dp(14));
        centerCluster.addView(playBtn, pl);
        centerCluster.addView(nextBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        addView(centerCluster, new LayoutParams(-2, -2, Gravity.CENTER));

        // ---- bottom panel: progress bar, time, volume, full screen
        bottomPanel = new LinearLayout(act);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(18), dp(34), dp(18), dp(12));
        bottomPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xF2100B13, 0xB3100B13, 0x00100B13}));

        scrub = new ScrubBar(act);
        scrub.setListener(new ScrubBar.Listener() {
            @Override
            public void onStart() {
                dragging = true;
                handler.removeCallbacks(hideRunnable);
                resumeAfterScrub = video.isPlaying();
                if (resumeAfterScrub) video.pause();
            }

            @Override
            public void onProgress(float fraction) {
                int pos = (int) (fraction * durationMs);
                setTimeText(pos, durationMs);
                long now = SystemClock.uptimeMillis();
                if (prepared && now - lastScrubSeek >= SCRUB_SEEK_GAP_MS) {
                    lastScrubSeek = now;
                    video.seekTo(pos);
                }
            }

            @Override
            public void onStop(float fraction) {
                dragging = false;
                if (prepared) {
                    int pos = (int) (fraction * durationMs);
                    seekExact(pos);
                    ended = false;
                    if (resumeAfterScrub) video.start();
                }
                resumeAfterScrub = false;
                refreshPlayIcon();
                scheduleHide();
            }
        });
        bottomPanel.addView(scrub, new LinearLayout.LayoutParams(-1, dp(44))); // a generous touch target for the thumb

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        timeText = Ui.text(act, "0:00 / 0:00", 13, Ui.TEXT, true);
        timeText.setFontFeatureSettings("tnum");
        timeText.setIncludeFontPadding(false);
        timeText.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        row.addView(timeText, new LinearLayout.LayoutParams(0, -2, 1f));
        muteBtn = Ui.glassButton(act, R.drawable.ic_volume, "كتم الصوت", 44, 20, Ui.TEXT);
        muteBtn.setOnClickListener(v -> setMuted(!muted));
        row.addView(muteBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        fullBtn = Ui.glassButton(act, R.drawable.ic_fullscreen, "ملء الشاشة", 44, 20, Ui.TEXT);
        fullBtn.setOnClickListener(v -> setFullscreen(!fullscreen));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(dp(44), dp(44));
        fl.setMarginStart(dp(10));
        row.addView(fullBtn, fl);
        bottomPanel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        addView(bottomPanel, new LayoutParams(-1, -2, Gravity.BOTTOM));

        video.setOnPreparedListener(this::onPrepared);
        video.setOnCompletionListener(mp -> onCompleted());
        video.setOnErrorListener((mp, what, extra) -> {
            if (this.listener != null) this.listener.onVideoError();
            return true;
        });
        refreshMuteIcon();
        setNavigation(false, false);
    }

    private int dp(float v) {
        return Ui.dp(act, v);
    }

    /** On a short screen (phone held sideways) the controls shrink so they never overlap or leave the screen. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        boolean c = h > 0 && h < dp(400);
        if (c == compact) return;
        compact = c;
        int big = dp(c ? 50 : 58);
        int side = dp(c ? 38 : 44);
        int gap = dp(c ? 10 : 14);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(big, big);
        pl.setMarginStart(gap);
        pl.setMarginEnd(gap);
        playBtn.setLayoutParams(pl);
        prevBtn.setLayoutParams(new LinearLayout.LayoutParams(side, side));
        nextBtn.setLayoutParams(new LinearLayout.LayoutParams(side, side));
        bottomPanel.setPadding(dp(c ? 24 : 18), dp(c ? 18 : 34), dp(c ? 24 : 18), dp(c ? 8 : 12));
    }

    /** A compact central play/pause button that leaves more of the video unobstructed. */
    private FrameLayout bigPlayButton() {
        FrameLayout f = Ui.glassButton(act, R.drawable.ic_play, "تشغيل", 58, 26, Ui.TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xD9351343);
        bg.setStroke(Math.max(1, dp(1.25f)), 0xCCC39AB8);
        f.setBackground(Ui.ripple(act, bg, 29));
        Ui.glow(f, 6, Ui.ACCENT);
        return f;
    }

    // ------------------------------------------------------------------ public api

    private int startAtMs;

    void setSource(File f) {
        video.setVideoPath(f.getAbsolutePath());
    }

    /** Resume point: playback jumps here once the video is prepared (call before or right after setSource). */
    void setStartPosition(int ms) {
        startAtMs = Math.max(0, ms);
    }

    /** Current position in ms (0 when not ready) and total length, for saving "continue watching". */
    int positionMs() {
        if (!prepared || released) return 0;
        try {
            return ended ? durationMs : video.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    int totalMs() {
        return durationMs;
    }

    /** Shows previous/next buttons only when the host has another item on that side. */
    void setNavigation(boolean hasPrev, boolean hasNext) {
        prevBtn.setVisibility(hasPrev ? View.VISIBLE : View.INVISIBLE);
        nextBtn.setVisibility(hasNext ? View.VISIBLE : View.INVISIBLE);
        centerCluster.setVisibility(View.VISIBLE);
    }

    boolean controlsVisible() {
        return controlsVisible;
    }

    /** Leaves full screen if it is on. Returns true when it did, so the host can use "back" for that first. */
    boolean exitFullscreen() {
        if (!fullscreen) return false;
        setFullscreen(false);
        return true;
    }

    /** Stops playback and gives the screen back (orientation, system bars). Safe to call more than once. */
    void release() {
        if (released) return;
        released = true;
        handler.removeCallbacksAndMessages(null);
        abandonFocus();
        if (fullscreen) setFullscreen(false);
        try {
            video.setKeepScreenOn(false);
            video.stopPlayback();
        } catch (Exception ignored) {
            // already released
        }
    }

    // ------------------------------------------------------------------ playback

    /** Lands exactly on the chosen frame when the finger lifts (API 26+); older phones use the normal seek. */
    private void seekExact(int posMs) {
        if (Build.VERSION.SDK_INT >= 26 && player != null) {
            try {
                player.seekTo(posMs, MediaPlayer.SEEK_CLOSEST);
                return;
            } catch (IllegalStateException ignored) {
                // fall back below
            }
        }
        video.seekTo(posMs);
    }

    @SuppressWarnings("deprecation")
    private void requestAudioFocusNow() {
        if (audioFocus) return;
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        audioFocus = am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.abandonAudioFocus(focusListener);
        audioFocus = false;
    }

    private void pauseForInterruption() {
        if (!prepared || !video.isPlaying()) return;
        video.pause();
        video.setKeepScreenOn(false);
        refreshPlayIcon();
        showControls();
    }

    private void onPrepared(MediaPlayer mp) {
        if (released) return;
        player = mp;
        prepared = true;
        ended = false;
        durationMs = Math.max(0, video.getDuration());
        videoW = mp.getVideoWidth();
        videoH = mp.getVideoHeight();
        spinner.setVisibility(View.GONE);
        // Video playback opens in immersive full screen; the button can still restore normal bars.
        setFullscreen(true);
        applyVolume();
        requestAudioFocusNow();
        video.setKeepScreenOn(true);
        if (startAtMs > 3000 && startAtMs < durationMs - 3000) video.seekTo(startAtMs);
        startAtMs = 0;
        video.start();
        refreshPlayIcon();
        updateProgress();
        showControls(); // visible at the start, then fades after HIDE_DELAY_MS
    }

    private void onCompleted() {
        if (released) return;
        ended = true;
        video.setKeepScreenOn(false);
        scrub.setProgress(1f);
        setTimeText(durationMs, durationMs);
        refreshPlayIcon();
        showControls(); // playback controls stay on screen so the user can replay
        hideHostChrome(); // the studio toolbar should not cover the finished video
    }

    private void togglePlay() {
        if (!prepared) return;
        if (video.isPlaying()) {
            video.pause();
            video.setKeepScreenOn(false);
            handler.removeCallbacks(hideRunnable); // paused: playback controls stay
            hideHostChrome();
        } else {
            if (ended) {
                video.seekTo(0);
                ended = false;
            }
            requestAudioFocusNow();
            video.setKeepScreenOn(true);
            video.start();
            showHostChrome();
            scheduleHide(); // playing again: start the countdown
        }
        refreshPlayIcon();
        pulse(playBtn);
    }

    private void refreshPlayIcon() {
        int res;
        String desc;
        if (ended) {
            res = R.drawable.ic_replay;
            desc = "إعادة التشغيل";
        } else if (video.isPlaying()) {
            res = R.drawable.ic_pause;
            desc = "إيقاف مؤقت";
        } else {
            res = R.drawable.ic_play;
            desc = "تشغيل";
        }
        Ui.setButtonIcon(playBtn, res);
        playBtn.setContentDescription(desc);
    }

    /** A small bounce so the swap between play and pause is felt. */
    private void pulse(View v) {
        v.animate().cancel();
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
    }

    // ------------------------------------------------------------------ progress and time

    private void updateProgress() {
        if (!prepared || dragging) return;
        int pos = ended ? durationMs : Math.min(video.getCurrentPosition(), durationMs);
        scrub.setProgress(durationMs <= 0 ? 0f : (float) pos / durationMs);
        setTimeText(pos, durationMs);
    }

    private void setTimeText(int posMs, int totalMs) {
        int sec = posMs / 1000;
        int total = totalMs / 1000;
        if (sec == lastShownSec && total == lastShownTotalSec) return;
        lastShownSec = sec;
        lastShownTotalSec = total;
        String cur = StudioGrid.formatDuration(posMs);
        String all = StudioGrid.formatDuration(totalMs);
        SpannableStringBuilder sb = new SpannableStringBuilder(cur);
        int start = sb.length();
        sb.append("  /  ").append(all);
        sb.setSpan(new ForegroundColorSpan(Ui.MUTED), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        timeText.setText(sb);
    }

    // ------------------------------------------------------------------ controls visibility

    private void showControls() {
        handler.removeCallbacks(hideRunnable);
        if (!controlsVisible) {
            controlsVisible = true;
            fade(bottomPanel, true, 16);
            fade(centerCluster, true, 0);
        } else {
            // make sure an interrupted fade-out cannot leave them half transparent
            bottomPanel.animate().cancel();
            centerCluster.animate().cancel();
            bottomPanel.setVisibility(View.VISIBLE);
            centerCluster.setVisibility(View.VISIBLE);
            bottomPanel.setAlpha(1f);
            bottomPanel.setTranslationY(0f);
            centerCluster.setAlpha(1f);
        }
        showHostChrome();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        scheduleHide();
    }

    /** The studio actions share the control visibility rhythm, but also hide while playback is paused/finished. */
    private void showHostChrome() {
        if (listener != null) listener.onControlsVisibility(true);
        handler.removeCallbacks(hideHostChromeRunnable);
        handler.postDelayed(hideHostChromeRunnable, HIDE_DELAY_MS);
    }

    private void hideHostChrome() {
        handler.removeCallbacks(hideHostChromeRunnable);
        if (listener != null) listener.onControlsVisibility(false);
    }

    /** Starts (or restarts) the countdown, only when it makes sense to hide at all. */
    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        if (controlsVisible && prepared && !ended && !dragging && !touching && video.isPlaying()) {
            handler.postDelayed(hideRunnable, HIDE_DELAY_MS);
        }
    }

    private void hideControlsForce() {
        if (released || !controlsVisible) return;
        handler.removeCallbacks(hideRunnable);
        controlsVisible = false;
        fade(bottomPanel, false, 16);
        fade(centerCluster, false, 0);
        hideHostChrome();
        handler.removeCallbacks(ticker);
    }

    private void hideControlsNow() {
        if (released || !controlsVisible) return;
        if (!prepared || ended || dragging || touching || !video.isPlaying()) return; // paused, finished, touched or scrubbed: stay
        controlsVisible = false;
        fade(bottomPanel, false, 16);
        fade(centerCluster, false, 0);
        hideHostChrome();
        handler.removeCallbacks(ticker);
    }

    private void fade(View v, boolean show, float slideDp) {
        if (slideDp == 0f) {
            v.animate().cancel();
            if (show) {
                if (v.getVisibility() != View.VISIBLE) {
                    v.setAlpha(0f);
                    v.setScaleX(0.92f);
                    v.setScaleY(0.92f);
                    v.setVisibility(View.VISIBLE);
                }
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui.ANIM_BASE)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
                v.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(Ui.ANIM_FAST)
                        .withEndAction(() -> {
                            v.setVisibility(View.GONE);
                            v.setScaleX(1f);
                            v.setScaleY(1f);
                        }).start();
            }
        } else {
            Ui.fadeVisible(v, show, slideDp);
        }
    }

    /** Every touch anywhere on the player pauses the countdown; lifting the finger restarts it. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                handler.removeCallbacks(hideRunnable);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                if (controlsVisible) scheduleHide();
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE && prepared && !released && video.isPlaying()) {
            video.pause(); // the app went to the background
            video.setKeepScreenOn(false);
            refreshPlayIcon();
            showControls();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ volume

    private void setMuted(boolean m) {
        muted = m;
        applyVolume();
        refreshMuteIcon();
    }

    private void applyVolume() {
        if (player == null) return;
        try {
            float v = muted ? 0f : 1f;
            player.setVolume(v, v);
        } catch (IllegalStateException ignored) {
            // the player is being torn down
        }
    }

    private void refreshMuteIcon() {
        Ui.setButtonIcon(muteBtn, muted ? R.drawable.ic_volume_off : R.drawable.ic_volume);
        muteBtn.setContentDescription(muted ? "تشغيل الصوت" : "كتم الصوت");
    }

    // ------------------------------------------------------------------ full screen

    /**
     * Full screen hides the system bars. A landscape video also rotates the phone to landscape, a portrait video
     * stays upright and simply uses the whole screen.
     */
    private void setFullscreen(boolean on) {
        if (on == fullscreen) return;
        fullscreen = on;
        if (on) {
            savedOrientation = act.getRequestedOrientation();
            if (videoW > videoH && videoH > 0) {
                act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
        } else {
            act.setRequestedOrientation(savedOrientation);
        }
        setSystemBars(!on);
        Ui.setButtonIcon(fullBtn, on ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        fullBtn.setContentDescription(on ? "إنهاء ملء الشاشة" : "ملء الشاشة");
        if (listener != null) listener.onFullscreenChanged(on);
    }

    @SuppressWarnings("deprecation")
    private void setSystemBars(boolean visible) {
        Window w = act.getWindow();
        if (w == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (visible) {
                c.show(WindowInsets.Type.systemBars());
            } else {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        } else {
            View d = w.getDecorView();
            d.setSystemUiVisibility(visible ? View.SYSTEM_UI_FLAG_VISIBLE
                    : View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ------------------------------------------------------------------ progress bar

    /** A slim, draggable progress bar: gradient fill, glowing thumb that grows while it is held. */
    static final class ScrubBar extends View {
        interface Listener {
            void onStart();

            void onProgress(float fraction);

            void onStop(float fraction);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float trackH;
        private final float thumbIdle;
        private final float thumbHeld;
        private final float side;
        private float progress;
        private float held; // 0 = idle thumb, 1 = held thumb
        private boolean touching;
        private ValueAnimator anim;
        private Listener listener;

        ScrubBar(Context c) {
            super(c);
            trackH = Ui.dp(c, 4);
            thumbIdle = Ui.dp(c, 6);
            thumbHeld = Ui.dp(c, 10);
            side = Ui.dp(c, 12);
            setContentDescription("شريط التقدم");
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void setListener(Listener l) {
            listener = l;
        }

        void setProgress(float p) {
            if (touching) return;
            progress = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        private float fractionAt(float x) {
            float w = getWidth() - 2 * side;
            if (w <= 0) return 0f;
            return Math.max(0f, Math.min(1f, (x - side) / w));
        }

        private void animateHeld(boolean on) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(held, on ? 1f : 0f);
            anim.setDuration(140);
            anim.addUpdateListener(a -> {
                held = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    touching = true;
                    progress = fractionAt(e.getX());
                    animateHeld(true);
                    invalidate();
                    if (listener != null) {
                        listener.onStart();
                        listener.onProgress(progress);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!touching) return false;
                    progress = fractionAt(e.getX());
                    invalidate();
                    if (listener != null) listener.onProgress(progress);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!touching) return false;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) progress = fractionAt(e.getX());
                    touching = false;
                    animateHeld(false);
                    invalidate();
                    if (listener != null) listener.onStop(progress);
                    return true;
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cy = getHeight() / 2f;
            float left = side;
            float right = getWidth() - side;
            float x = left + (right - left) * progress;
            // track
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x40E8DDE8);
            rect.set(left, cy - trackH / 2f, right, cy + trackH / 2f);
            cv.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);
            // fill
            if (x > left) {
                paint.setShader(new LinearGradient(left, 0, Math.max(x, left + 1), 0, Ui.ACCENT, Ui.LAV, Shader.TileMode.CLAMP));
                rect.set(left, cy - trackH / 2f, x, cy + trackH / 2f);
                cv.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);
                paint.setShader(null);
            }
            // thumb with halo
            float r = thumbIdle + (thumbHeld - thumbIdle) * held;
            paint.setColor(0x33C39AB8);
            cv.drawCircle(x, cy, r + Ui.dp(getContext(), 5) * held, paint);
            paint.setColor(Ui.LAV);
            cv.drawCircle(x, cy, r, paint);
        }

        // --- accessibility: TalkBack can move the position in 5% steps
        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.SeekBar");
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT,
                    0f, 100f, progress * 100f));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }

        @Override
        public boolean performAccessibilityAction(int action, android.os.Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                float step = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 0.05f : -0.05f;
                progress = Math.max(0f, Math.min(1f, progress + step));
                invalidate();
                if (listener != null) {
                    listener.onStart();
                    listener.onProgress(progress);
                    listener.onStop(progress);
                }
                return true;
            }
            return super.performAccessibilityAction(action, args);
        }
    }
}
