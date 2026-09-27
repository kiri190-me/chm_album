package com.chm.album;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 썸네일 비동기 로딩 + 메모리 캐시. 최근 요청을 먼저 처리(LIFO)해 스크롤이 빠르게 반응한다. */
public final class ThumbnailLoader {

    private static ThumbnailLoader sInstance;

    public static synchronized ThumbnailLoader get(Context ctx) {
        if (sInstance == null) sInstance = new ThumbnailLoader(ctx.getApplicationContext());
        return sInstance;
    }

    private final ContentResolver cr;
    private final LruCache<String, Bitmap> cache;
    private final Map<ImageView, String> targets =
            Collections.synchronizedMap(new WeakHashMap<ImageView, String>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool;

    private ThumbnailLoader(Context ctx) {
        cr = ctx.getContentResolver();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 6);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        LinkedBlockingDeque<Runnable> lifo = new LinkedBlockingDeque<Runnable>() {
            @Override
            public boolean offer(Runnable r) {
                return super.offerFirst(r);
            }
        };
        pool = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, lifo);
    }

    public void load(final MediaItem item, final ImageView iv, final int sizePx) {
        final String key = item.id + "@" + sizePx;
        targets.put(iv, key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                if (!key.equals(targets.get(iv))) return; // 이미 다른 사진으로 재사용됨
                Bitmap bm = cache.get(key);
                if (bm == null) {
                    bm = decodeThumbnail(item, sizePx);
                    if (bm != null) cache.put(key, bm);
                }
                final Bitmap result = bm;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null && key.equals(targets.get(iv))) iv.setImageBitmap(result);
                    }
                });
            }
        });
    }

    /** 이 ImageView 에 대기 중인 썸네일이 나중에 덮어쓰지 않도록 한다. */
    public void cancel(ImageView iv) {
        targets.remove(iv);
    }

    private Bitmap decodeThumbnail(MediaItem item, int sizePx) {
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+ : 시스템 썸네일 캐시 사용 (회전 보정 포함)
            try {
                Method m = ContentResolver.class.getMethod("loadThumbnail",
                        Uri.class, Size.class, CancellationSignal.class);
                return (Bitmap) m.invoke(cr, item.uri, new Size(sizePx, sizePx), null);
            } catch (Throwable ignored) {
                // 아래 수동 디코딩으로 대체
            }
        }
        return decodeSampled(cr, item.uri, item.orientation, sizePx);
    }

    /** 짧은 변이 target 이상으로 남도록 샘플링하고 회전을 보정한다. */
    public static Bitmap decodeSampled(ContentResolver cr, Uri uri, int orientation, int target) {
        InputStream in = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            in = cr.openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            closeQuietly(in);
            in = null;
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            int sample = 1;
            int shortSide = Math.min(o.outWidth, o.outHeight);
            while (shortSide / (sample * 2) >= target) sample *= 2;

            return decodeWithSample(cr, uri, orientation, sample);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 전체 화면용: 긴 변이 maxSide 를 크게 넘지 않도록 디코딩. */
    public static Bitmap decodeForScreen(ContentResolver cr, Uri uri, int orientation, int maxSide) {
        InputStream in = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            in = cr.openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            closeQuietly(in);
            in = null;
            int longSide = Math.max(o.outWidth, o.outHeight);
            if (longSide <= 0) return null;
            int sample = 1;
            while (longSide / (sample * 2) >= maxSide) sample *= 2;
            return decodeWithSample(cr, uri, orientation, sample);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static Bitmap decodeWithSample(ContentResolver cr, Uri uri, int orientation, int sample) {
        InputStream in = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = cr.openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(in, null, o);
            if (bm == null) return null;
            if (orientation % 360 != 0) {
                Matrix mx = new Matrix();
                mx.postRotate(orientation);
                Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), mx, true);
                if (rotated != bm) bm.recycle();
                bm = rotated;
            }
            return bm;
        } catch (OutOfMemoryError oom) {
            return sample < 16 ? decodeWithSample(cr, uri, orientation, sample * 2) : null;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Exception ignored) {
        }
    }
}
