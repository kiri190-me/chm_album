package com.chm.album;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MediaStore 조회, 정렬, 폴더(앨범) 묶기. */
public final class MediaRepository {

    public interface Callback {
        void onLoaded(List<MediaItem> items);
    }

    /** 폴더 하나. items 는 현재 정렬 기준으로 정렬되어 있다. */
    public static class Album {
        public final String id;
        public final String name;
        public final List<MediaItem> items = new ArrayList<>();

        Album(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 마지막으로 읽은 전체 목록 (다른 화면에서 재사용). */
    private static volatile List<MediaItem> sLast;

    private MediaRepository() {
    }

    public static List<MediaItem> last() {
        return sLast;
    }

    public static void loadAsync(Context ctx, final Callback cb) {
        final ContentResolver cr = ctx.getApplicationContext().getContentResolver();
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final List<MediaItem> items = query(cr);
                sLast = items;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onLoaded(items);
                    }
                });
            }
        });
    }

    private static List<MediaItem> query(ContentResolver cr) {
        List<MediaItem> out = new ArrayList<>();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.ORIENTATION,
                MediaStore.Images.Media.SIZE,
        };
        Cursor c = null;
        try {
            c = cr.query(base, projection, null, null, null);
            if (c == null) return out;
            int iId = c.getColumnIndex(MediaStore.Images.Media._ID);
            int iName = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int iBucket = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
            int iBucketName = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int iTaken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
            int iAdded = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
            int iModified = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED);
            int iW = c.getColumnIndex(MediaStore.Images.Media.WIDTH);
            int iH = c.getColumnIndex(MediaStore.Images.Media.HEIGHT);
            int iOri = c.getColumnIndex(MediaStore.Images.Media.ORIENTATION);
            int iSize = c.getColumnIndex(MediaStore.Images.Media.SIZE);
            while (c.moveToNext()) {
                long id = c.getLong(iId);
                String bucketId = c.isNull(iBucket) ? "_" : c.getString(iBucket);
                String bucketName = c.isNull(iBucketName) ? null : c.getString(iBucketName);
                out.add(new MediaItem(
                        id,
                        ContentUris.withAppendedId(base, id),
                        c.isNull(iName) ? "" : c.getString(iName),
                        bucketId,
                        prettyBucketName(bucketName),
                        c.isNull(iTaken) ? 0 : c.getLong(iTaken),
                        c.isNull(iAdded) ? 0 : c.getLong(iAdded) * 1000L,
                        c.isNull(iModified) ? 0 : c.getLong(iModified) * 1000L,
                        c.isNull(iW) ? 0 : c.getInt(iW),
                        c.isNull(iH) ? 0 : c.getInt(iH),
                        c.isNull(iOri) ? 0 : c.getInt(iOri),
                        c.isNull(iSize) ? 0 : c.getLong(iSize)));
            }
        } catch (Exception e) {
            // 권한이 없거나 저장소를 읽을 수 없는 경우 빈 목록
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private static String prettyBucketName(String raw) {
        if (raw == null || raw.isEmpty()) return "기타";
        switch (raw) {
            case "Camera":
                return "카메라";
            case "Screenshots":
                return "스크린샷";
            case "Download":
            case "Downloads":
                return "다운로드";
            case "Pictures":
                return "사진";
            default:
                return raw;
        }
    }

    public static List<MediaItem> sorted(List<MediaItem> src, final int basis, final boolean desc) {
        List<MediaItem> list = new ArrayList<>(src);
        Collections.sort(list, new Comparator<MediaItem>() {
            @Override
            public int compare(MediaItem a, MediaItem b) {
                int r = Long.compare(a.time(basis), b.time(basis));
                if (r == 0) r = Long.compare(a.id, b.id);
                return desc ? -r : r;
            }
        });
        return list;
    }

    public static List<MediaItem> inBucket(List<MediaItem> src, String bucketId) {
        List<MediaItem> list = new ArrayList<>();
        for (MediaItem m : src) {
            if (m.bucketId.equals(bucketId)) list.add(m);
        }
        return list;
    }

    /**
     * 폴더별로 묶는다. 카메라 폴더를 맨 앞에 두고, 나머지는 가장 최근 사진이
     * 있는 폴더 순으로 정렬한다.
     */
    public static List<Album> albums(List<MediaItem> sortedItems, final int basis) {
        Map<String, Album> map = new HashMap<>();
        List<Album> list = new ArrayList<>();
        for (MediaItem m : sortedItems) {
            Album a = map.get(m.bucketId);
            if (a == null) {
                a = new Album(m.bucketId, m.bucketName);
                map.put(m.bucketId, a);
                list.add(a);
            }
            a.items.add(m);
        }
        Collections.sort(list, new Comparator<Album>() {
            @Override
            public int compare(Album a, Album b) {
                boolean ca = "카메라".equals(a.name);
                boolean cb = "카메라".equals(b.name);
                if (ca != cb) return ca ? -1 : 1;
                return Long.compare(latest(b, basis), latest(a, basis));
            }
        });
        return list;
    }

    private static long latest(Album a, int basis) {
        long t = 0;
        for (MediaItem m : a.items) t = Math.max(t, m.time(basis));
        return t;
    }
}
