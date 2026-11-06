package com.hajala.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thumbnails for the Studio: decoded off the UI thread, cached in memory and as small JPEGs on disk. */
final class StudioMedia {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            Math.max(8 * 1024, (int) (Runtime.getRuntime().maxMemory() / 1024 / 10))) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount() / 1024;
        }
    };

    private StudioMedia() {}

    /** Loads a square-ish thumbnail for [it] into [iv]. [px] is the target edge in pixels. */
    static void load(final StudioStore store, final StudioStore.Item it, final ImageView iv, final int px,
                     final Runnable onBroken) {
        final String key = it.id + "@" + px;
        iv.setTag(key);
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        iv.setImageDrawable(null);
        POOL.execute(() -> {
            final Bitmap bmp = build(store, it, px);
            if (bmp != null) CACHE.put(key, bmp);
            MAIN.post(() -> {
                if (!key.equals(iv.getTag())) return; // the view was recycled for another item
                if (bmp != null) iv.setImageBitmap(bmp);
                else if (onBroken != null) onBroken.run();
            });
        });
    }

    private static Bitmap build(StudioStore store, StudioStore.Item it, int px) {
        File src = store.fileOf(it);
        if (!src.exists()) return null;
        File cached = store.thumbOf(it);
        if (cached.exists()) {
            Bitmap b = BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (b != null) return b;
            //noinspection ResultOfMethodCallIgnored
            cached.delete();
        }
        Bitmap bmp = null;
        try {
            if (StudioStore.KIND_IMAGE.equals(it.kind)) {
                bmp = decodeSampled(src, px, px);
            } else if (StudioStore.KIND_VIDEO.equals(it.kind)) {
                bmp = videoFrame(store, it, src, px);
            }
            if (bmp != null) {
                Bitmap scaled = centerCrop(bmp, px);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
                try (FileOutputStream os = new FileOutputStream(cached)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, os);
                } catch (Exception ignored) {
                    // the thumbnail is only a cache
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
        return bmp;
    }

    private static Bitmap videoFrame(StudioStore store, StudioStore.Item it, File src, int px) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(src.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                try {
                    store.setDuration(it.id, Long.parseLong(d));
                } catch (NumberFormatException ignored) {
                    // unknown duration
                }
            }
            Bitmap b = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) b = r.getFrameAtTime(0);
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // nothing to release
            }
        }
    }

    /** Decodes a file close to the requested size without loading the full bitmap. */
    static Bitmap decodeSampled(File f, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    /** Full-screen decode: capped at roughly 12 million pixels so very large photos cannot exhaust memory. */
    static Bitmap decodeFull(File f, int screenW, int screenH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while ((o.outWidth / sample) * (long) (o.outHeight / sample) > 12_000_000L
                    || (o.outWidth / (sample * 2) >= screenW * 2 && o.outHeight / (sample * 2) >= screenH * 2)) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap centerCrop(Bitmap b, int px) {
        int w = b.getWidth();
        int h = b.getHeight();
        int side = Math.min(w, h);
        if (side <= 0) return b;
        Bitmap sq = Bitmap.createBitmap(b, (w - side) / 2, (h - side) / 2, side, side);
        if (side <= px) return sq;
        Bitmap out = Bitmap.createScaledBitmap(sq, px, px, true);
        if (out != sq) sq.recycle();
        return out;
    }

    static void forget(String itemId) {
        CACHE.evictAll();
    }
}
