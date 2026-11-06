package com.hajala.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Full-screen viewer: zoomable photos, in-app video playback, other files, and captions for medical images. */
final class MediaViewerScreen {
    private final StudioContext ctx;
    private final List<StudioStore.Item> list;
    private final boolean captions;
    private final Runnable onChanged;
    private int index;
    private boolean changed;
    private boolean closed;

    private FrameLayout stage;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView counter;
    private TextView title;
    private EditText caption;
    private TextWatcher captionWatcher;
    private VideoPlayerView video;
    private int loadToken;
    private LinearLayout prevBtn;
    private LinearLayout nextBtn;
    private boolean barsOn = true;
    private View openBtn;
    private View deleteBtn;
    private View favBtn;
    private View shareBtn;
    private StudioStore.Item videoItem;

    MediaViewerScreen(StudioContext ctx, List<StudioStore.Item> list, int index, boolean captions, Runnable onChanged) {
        this.ctx = ctx;
        this.list = new ArrayList<>(list);
        this.index = Math.max(0, Math.min(index, list.size() - 1));
        this.captions = captions;
        this.onChanged = onChanged;
    }

    private int dp(float v) {
        return ctx.dp(v);
    }

    View build() {
        Context c = ctx.act;
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(0xFF000000);
        root.setClickable(true);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        stage = new FrameLayout(c);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        // top bar
        topBar = new LinearLayout(c);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(10), dp(12), dp(18));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xE6100B13, 0x00100B13}));
        topBar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        View back = Ui.glassButton(c, R.drawable.ic_back, "رجوع", 44, 22, Ui.LAV);
        back.setOnClickListener(v -> ctx.host.pop());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(c, "", 15, Ui.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        counter = Ui.text(c, "", 12, Ui.MUTED, false);
        titles.addView(title);
        titles.addView(counter);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginStart(dp(12));
        topBar.addView(titles, tl);
        View fav = Ui.glassButton(c, R.drawable.ic_heart, "المفضلة", 44, 20, Ui.LAV);
        favBtn = fav;
        fav.setOnClickListener(v -> toggleFavorite());
        topBar.addView(fav, new LinearLayout.LayoutParams(dp(44), dp(44)));
        View share = Ui.glassButton(c, R.drawable.ic_upload, "مشاركة", 44, 20, Ui.LAV);
        shareBtn = share;
        share.setOnClickListener(v -> shareCurrent());
        LinearLayout.LayoutParams shl = new LinearLayout.LayoutParams(dp(44), dp(44));
        shl.setMarginStart(dp(8));
        topBar.addView(share, shl);
        View open = Ui.glassButton(c, R.drawable.ic_external, "فتح بتطبيق آخر", 44, 20, Ui.LAV);
        openBtn = open;
        open.setOnClickListener(v -> ctx.openExternal(list.get(index)));
        LinearLayout.LayoutParams opl = new LinearLayout.LayoutParams(dp(44), dp(44));
        opl.setMarginStart(dp(8));
        topBar.addView(open, opl);
        View del = Ui.glassButton(c, R.drawable.ic_trash, "حذف", 44, 20, Ui.LAV);
        deleteBtn = del;
        del.setOnClickListener(v -> confirmDelete());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(44), dp(44));
        dl.setMarginStart(dp(8));
        topBar.addView(del, dl);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // bottom bar: previous / next and (medical) caption
        bottomBar = new LinearLayout(c);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(14), dp(22), dp(14), dp(14));
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00100B13, 0xF2100B13}));
        bottomBar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (captions) {
            caption = Ui.input(c, "اكتب وصفاً أو تعليقاً لهذه الصورة…", true);
            caption.setMinLines(1);
            caption.setMaxLines(4);
            caption.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.bottomMargin = dp(10);
            bottomBar.addView(caption, cl);
        }
        LinearLayout nav = new LinearLayout(c);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        // the bar is RTL: "previous" sits on the right and points right, "next" sits on the left and points left
        prevBtn = Ui.button(c, "السابق", R.drawable.ic_chevron_right, false);
        prevBtn.setOnClickListener(v -> go(-1));
        nextBtn = Ui.button(c, "التالي", R.drawable.ic_chevron_left, false);
        nextBtn.setOnClickListener(v -> go(1));
        nav.addView(prevBtn, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, dp(46), 1f);
        nl.setMarginStart(dp(10));
        nav.addView(nextBtn, nl);
        bottomBar.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        show();
        return root;
    }

    // ------------------------------------------------------------------ paging

    private void go(int delta) {
        saveCaption();
        int n = index + delta;
        if (n < 0 || n >= list.size()) return;
        index = n;
        show();
    }

    private void show() {
        stopVideo();
        stage.removeAllViews();
        if (list.isEmpty()) {
            ctx.host.pop();
            return;
        }
        final StudioStore.Item it = list.get(index);
        title.setText(it.name);
        Ui.applyFont(title, true);
        counter.setText((index + 1) + " / " + list.size() + "  ·  " + dateLabel(it.createdAt));
        paintFavorite(it);
        if (!captions) ctx.store.markOpened(it.id);
        bindCaption(it);
        prevBtn.setAlpha(index > 0 ? 1f : 0.4f);
        nextBtn.setAlpha(index < list.size() - 1 ? 1f : 0.4f);
        // the video player brings its own bottom controls (with previous/next), so the photo bar is only for photos
        setBars(true, true);
        if (StudioStore.KIND_VIDEO.equals(it.kind)) bottomBar.setVisibility(View.GONE);

        File f = ctx.store.fileOf(it);
        if (!f.exists()) {
            stage.addView(message("الملف غير موجود على الجهاز", "ربما حُذف خارج التطبيق. يمكنك حذف هذا العنصر من القائمة."));
            return;
        }
        if (StudioStore.KIND_IMAGE.equals(it.kind)) showImage(f);
        else if (StudioStore.KIND_VIDEO.equals(it.kind)) showVideo(f, it);
        else showFile(it);
    }

    private void showImage(final File f) {
        Context c = ctx.act;
        final ZoomImageView zoom = new ZoomImageView(c);
        zoom.setListener(new ZoomImageView.Listener() {
            @Override
            public void onSwipe(boolean next) {
                go(next ? 1 : -1);
            }

            @Override
            public void onTap() {
                setBars(!barsOn, false);
            }
        });
        stage.addView(zoom, new FrameLayout.LayoutParams(-1, -1));
        final int token = ++loadToken;
        final int sw = ctx.act.getResources().getDisplayMetrics().widthPixels;
        final int sh = ctx.act.getResources().getDisplayMetrics().heightPixels;
        Ui.IO.execute(() -> {
            final Bitmap b = StudioMedia.decodeFull(f, sw, sh);
            ctx.act.runOnUiThread(() -> {
                if (closed || token != loadToken) return;
                if (b != null) zoom.setBitmap(b);
                else {
                    stage.removeAllViews();
                    stage.addView(message("تعذّر عرض الصورة", "قد يكون الملف تالفاً أو بصيغة غير مدعومة."));
                }
            });
        });
    }

    private void showVideo(File f, final StudioStore.Item it) {
        // Start the video with the picture unobstructed. The player briefly reveals this toolbar
        // together with its controls when the user touches the screen.
        topBar.animate().cancel();
        topBar.setVisibility(View.GONE);
        topBar.setAlpha(0f);
        video = new VideoPlayerView(ctx.act, new VideoPlayerView.Listener() {
            @Override
            public void onControlsVisibility(boolean visible) {
                barsOn = visible;
                Ui.fadeVisible(topBar, visible, -12);
            }

            @Override
            public void onPrevious() {
                go(-1);
            }

            @Override
            public void onNext() {
                go(1);
            }

            @Override
            public void onFullscreenChanged(boolean fullscreen) {
                // full screen keeps only "back" and the title: opening elsewhere and deleting are not needed there
                int vis = fullscreen ? View.GONE : View.VISIBLE;
                openBtn.setVisibility(vis);
                deleteBtn.setVisibility(vis);
                favBtn.setVisibility(vis);
                shareBtn.setVisibility(vis);
                counter.setVisibility(vis);
            }

            @Override
            public void onVideoError() {
                stopVideo();
                stage.removeAllViews();
                setBars(true, true);
                LinearLayout err = message("تعذّر تشغيل الفيديو داخل التطبيق", "قد تكون الصيغة غير مدعومة على هذا الهاتف.");
                View ext = Ui.button(ctx.act, "فتح بتطبيق آخر", R.drawable.ic_external, true);
                ext.setOnClickListener(v -> ctx.openExternal(it));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(48));
                lp.topMargin = dp(16);
                err.addView(ext, lp);
                stage.addView(err);
            }
        });
        video.setNavigation(index > 0, index < list.size() - 1);
        stage.addView(video, new FrameLayout.LayoutParams(-1, -1));
        videoItem = it;
        video.setStartPosition((int) Math.min(Integer.MAX_VALUE, it.lastPos));
        video.setSource(f);
    }

    /** Shows or hides the top (and, for photos, bottom) bars with a soft fade. [instant] skips the animation. */
    private void setBars(boolean show, boolean instant) {
        barsOn = show;
        boolean isVideo = !list.isEmpty() && index < list.size() && StudioStore.KIND_VIDEO.equals(list.get(index).kind);
        if (instant) {
            topBar.animate().cancel();
            bottomBar.animate().cancel();
            topBar.setAlpha(1f);
            topBar.setTranslationY(0f);
            bottomBar.setAlpha(1f);
            bottomBar.setTranslationY(0f);
            topBar.setVisibility(show ? View.VISIBLE : View.GONE);
            bottomBar.setVisibility(show && !isVideo ? View.VISIBLE : View.GONE);
            return;
        }
        Ui.fadeVisible(topBar, show, -12);
        if (!isVideo) Ui.fadeVisible(bottomBar, show, 12);
    }

    private void showFile(final StudioStore.Item it) {
        LinearLayout box = message(it.name, StudioStore.sizeLabel(it.size) + (it.mime.isEmpty() ? "" : "  ·  " + it.mime));
        View open = Ui.button(ctx.act, "فتح الملف", 0, true);
        open.setOnClickListener(v -> ctx.openExternal(it));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(48));
        lp.topMargin = dp(16);
        box.addView(open, lp);
        stage.addView(box);
    }

    private LinearLayout message(String head, String sub) {
        Context c = ctx.act;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(80), dp(28), dp(80));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setLayoutParams(new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        box.addView(Ui.crow(c, 84));
        TextView h = Ui.centered(Ui.text(c, head, 17, Ui.TEXT, true));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = dp(10);
        box.addView(h, hl);
        TextView s = Ui.centered(Ui.text(c, sub, 13, Ui.MUTED, false));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = dp(6);
        box.addView(s, sl);
        return box;
    }

    // ------------------------------------------------------------------ captions

    private void bindCaption(StudioStore.Item it) {
        if (caption == null) return;
        if (captionWatcher != null) caption.removeTextChangedListener(captionWatcher);
        caption.setText(it.caption);
        caption.setSelection(caption.getText().length());
        captionWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                caption.removeCallbacks(captionSave);
                caption.postDelayed(captionSave, 600);
            }
        };
        caption.addTextChangedListener(captionWatcher);
    }

    private final Runnable captionSave = this::saveCaption;

    private void saveCaption() {
        if (caption == null || list.isEmpty() || index >= list.size()) return;
        caption.removeCallbacks(captionSave);
        StudioStore.Item it = list.get(index);
        String text = caption.getText().toString();
        if (!text.equals(it.caption)) {
            it.caption = text;
            ctx.store.setCaption(it.id, text);
            changed = true;
        }
    }

    // ------------------------------------------------------------------ favourite / share / date

    private static String dateLabel(long ms) {
        if (ms <= 0) return "";
        return new java.text.SimpleDateFormat("d MMM yyyy", new java.util.Locale("ar")).format(new java.util.Date(ms));
    }

    private void paintFavorite(StudioStore.Item it) {
        boolean on = it.favorite;
        if (favBtn instanceof FrameLayout && ((FrameLayout) favBtn).getChildCount() > 0) {
            View iv = ((FrameLayout) favBtn).getChildAt(0);
            if (iv instanceof ImageView) ((ImageView) iv).setColorFilter(on ? 0xFFFF6B8A : Ui.LAV);
        }
        favBtn.setContentDescription(on ? "إزالة من المفضلة" : "إضافة إلى المفضلة");
        favBtn.setVisibility(captions ? View.GONE : View.VISIBLE);
    }

    private void toggleFavorite() {
        if (list.isEmpty()) return;
        StudioStore.Item it = list.get(index);
        it.favorite = !it.favorite;
        ArrayList<String> ids = new ArrayList<>();
        ids.add(it.id);
        ctx.store.setFavorite(ids, it.favorite);
        changed = true;
        paintFavorite(it);
        ctx.toast(it.favorite ? "أُضيف إلى المفضلة" : "أُزيل من المفضلة");
    }

    private void shareCurrent() {
        if (list.isEmpty()) return;
        StudioStore.Item it = list.get(index);
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
            i.setType(it.mime.isEmpty() ? "*/*" : it.mime);
            i.putExtra(android.content.Intent.EXTRA_STREAM, StudioFileProvider.uriFor(it));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.act.startActivity(android.content.Intent.createChooser(i, "مشاركة"));
        } catch (Exception e) {
            ctx.toast("تعذّرت المشاركة");
        }
    }

    // ------------------------------------------------------------------ delete / close

    private void confirmDelete() {
        final StudioStore.Item it = list.get(index);
        final boolean toTrash = !captions;
        ctx.confirm(toTrash ? "نقل إلى المحذوفات" : "حذف الملف",
                toTrash ? "سيُنقل «" + it.name + "» إلى سلة المحذوفات ويمكنك استعادته خلال " + StudioStore.TRASH_DAYS + " يوماً."
                        : "سيُحذف «" + it.name + "» نهائياً من التطبيق.",
                toTrash ? "نقل" : "حذف", () -> {
            stopVideo();
            ArrayList<String> ids = new ArrayList<>();
            ids.add(it.id);
            if (toTrash) ctx.store.trashItems(ids);
            else ctx.store.deleteItems(ids);
            changed = true;
            list.remove(index);
            if (list.isEmpty()) {
                ctx.host.pop();
                return;
            }
            if (index >= list.size()) index = list.size() - 1;
            show();
        });
    }

    private void stopVideo() {
        if (video != null) {
            if (videoItem != null) {
                int total = video.totalMs();
                int pos = video.positionMs();
                if (total > 0) {
                    // finished (or nearly): next time start from the beginning
                    long save = pos > total - 3000 ? 0 : pos;
                    videoItem.lastPos = save;
                    ctx.store.setLastPos(videoItem.id, save);
                }
            }
            videoItem = null;
            video.release();
            video = null;
        }
    }

    /** Back while a video is in full screen first returns to the normal player. True when it handled the press. */
    boolean onBackPressed() {
        return video != null && video.exitFullscreen();
    }

    /** Called by the host when the page is popped. */
    void onClosed() {
        saveCaption();
        closed = true;
        stopVideo();
        if (changed && onChanged != null) onChanged.run();
    }

    // ------------------------------------------------------------------ zoomable image

    /** ImageView with pinch zoom, double-tap zoom, panning and horizontal swipe to change image. */
    static final class ZoomImageView extends ImageView {
        interface Listener {
            void onSwipe(boolean next);

            void onTap();
        }

        private final Matrix m = new Matrix();
        private final float[] vals = new float[9];
        private final RectF rect = new RectF();
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;
        private Listener listener;
        private Bitmap bmp;
        private float minScale = 1f;
        private float maxScale = 6f;

        ZoomImageView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float cur = scale();
                    float f = d.getScaleFactor();
                    float target = Math.max(minScale * 0.98f, Math.min(maxScale, cur * f));
                    f = target / cur;
                    m.postScale(f, f, d.getFocusX(), d.getFocusY());
                    clamp();
                    setImageMatrix(m);
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (scaler.isInProgress() || bmp == null) return false;
                    if (scale() > minScale * 1.02f) {
                        m.postTranslate(-dx, -dy);
                        clamp();
                        setImageMatrix(m);
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (scale() <= minScale * 1.02f && Math.abs(vx) > 900 && Math.abs(vx) > Math.abs(vy) * 1.5f) {
                        if (listener != null) listener.onSwipe(vx < 0);
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (bmp == null) return true;
                    if (scale() > minScale * 1.02f) {
                        fit();
                    } else {
                        float f = Math.min(maxScale, minScale * 2.5f) / scale();
                        m.postScale(f, f, e.getX(), e.getY());
                        clamp();
                        setImageMatrix(m);
                    }
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (listener != null) listener.onTap();
                    return true;
                }
            });
        }

        void setListener(Listener l) {
            listener = l;
        }

        void setBitmap(Bitmap b) {
            bmp = b;
            setImageBitmap(b);
            fit();
        }

        private float scale() {
            m.getValues(vals);
            return vals[Matrix.MSCALE_X];
        }

        private void fit() {
            if (bmp == null || getWidth() == 0 || getHeight() == 0) return;
            float s = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
            minScale = s;
            maxScale = s * 6f;
            m.reset();
            m.postScale(s, s);
            m.postTranslate((getWidth() - bmp.getWidth() * s) / 2f, (getHeight() - bmp.getHeight() * s) / 2f);
            setImageMatrix(m);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fit();
        }

        private void clamp() {
            if (bmp == null) return;
            rect.set(0, 0, bmp.getWidth(), bmp.getHeight());
            m.mapRect(rect);
            float dx = 0;
            float dy = 0;
            if (rect.width() <= getWidth()) dx = (getWidth() - rect.width()) / 2f - rect.left;
            else if (rect.left > 0) dx = -rect.left;
            else if (rect.right < getWidth()) dx = getWidth() - rect.right;
            if (rect.height() <= getHeight()) dy = (getHeight() - rect.height()) / 2f - rect.top;
            else if (rect.top > 0) dy = -rect.top;
            else if (rect.bottom < getHeight()) dy = getHeight() - rect.bottom;
            m.postTranslate(dx, dy);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            gestures.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP && scale() < minScale) fit();
            return true;
        }
    }
}
