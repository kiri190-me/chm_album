package com.chm.album;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MediaStore 조회(사진 + 동영상), 정렬, 폴더(앨범) 묶기. */
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

    /** MediaStore.MediaColumns.RELATIVE_PATH (API 29) */
    static final String COL_RELATIVE_PATH = "relative_path";

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
        queryTable(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out, false);
        queryTable(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out, false);
        return out;
    }

    /** Android 11+ 시스템 휴지통에 있는 사진/동영상 (완전 삭제까지 남은 순서로 정렬 전). */
    public static List<MediaItem> queryTrashed(ContentResolver cr) {
        List<MediaItem> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 30) return out;
        queryTable(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out, true);
        queryTable(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out, true);
        return out;
    }

    /** MediaStore.QUERY_ARG_MATCH_TRASHED / MATCH_ONLY / DATE_EXPIRES (API 30) */
    private static final String ARG_MATCH_TRASHED = "android:query-arg-match-trashed";
    private static final int MATCH_ONLY = 3;
    private static final String COL_DATE_EXPIRES = "date_expires";

    private static Cursor queryTrashedCursor(ContentResolver cr, Uri base, String[] cols) throws Exception {
        Bundle args = new Bundle();
        args.putInt(ARG_MATCH_TRASHED, MATCH_ONLY);
        Method q = ContentResolver.class.getMethod("query",
                Uri.class, String[].class, Bundle.class, CancellationSignal.class);
        return (Cursor) q.invoke(cr, base, cols, args, null);
    }

    /** 사진/동영상 테이블 하나를 읽는다. 권한이 없는 테이블은 건너뛴다. */
    private static void queryTable(ContentResolver cr, Uri base, boolean video, List<MediaItem> out,
                                   boolean trashed) {
        List<String> cols = new ArrayList<>(Arrays.asList(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.Images.ImageColumns.BUCKET_ID,
                MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATA));
        cols.add(video ? MediaStore.Video.VideoColumns.DURATION : MediaStore.Images.ImageColumns.ORIENTATION);
        if (Build.VERSION.SDK_INT >= 29) cols.add(COL_RELATIVE_PATH);
        if (trashed) cols.add(COL_DATE_EXPIRES);

        Cursor c = null;
        try {
            String[] projection = cols.toArray(new String[0]);
            c = trashed ? queryTrashedCursor(cr, base, projection) : cr.query(base, projection, null, null, null);
            if (c == null) return;
            int iId = c.getColumnIndex(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int iBucket = c.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID);
            int iBucketName = c.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME);
            int iTaken = c.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN);
            int iAdded = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
            int iModified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int iW = c.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            int iH = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
            int iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int iData = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int iDur = video ? c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) : -1;
            int iOri = video ? -1 : c.getColumnIndex(MediaStore.Images.ImageColumns.ORIENTATION);
            int iRel = c.getColumnIndex(COL_RELATIVE_PATH);
            int iExp = c.getColumnIndex(COL_DATE_EXPIRES);
            while (c.moveToNext()) {
                long id = c.getLong(iId);
                String bucketName = str(c, iBucketName);
                String bucketId = str(c, iBucket);
                MediaItem item = new MediaItem(
                        id,
                        ContentUris.withAppendedId(base, id),
                        video,
                        orDefault(str(c, iMime), video ? "video/mp4" : "image/jpeg"),
                        orDefault(str(c, iName), ""),
                        bucketId == null ? "_" : bucketId,
                        prettyBucketName(bucketName),
                        lng(c, iTaken),
                        lng(c, iAdded) * 1000L,
                        lng(c, iModified) * 1000L,
                        (int) lng(c, iW),
                        (int) lng(c, iH),
                        (int) lng(c, iOri),
                        lng(c, iSize),
                        lng(c, iDur),
                        str(c, iRel),
                        str(c, iData));
                item.expiresMs = lng(c, iExp) * 1000L;
                out.add(item);
            }
        } catch (Exception e) {
            // 권한이 없거나 저장소를 읽을 수 없는 경우 건너뜀
        } finally {
            if (c != null) c.close();
        }
    }

    private static String str(Cursor c, int i) {
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long lng(Cursor c, int i) {
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private static String orDefault(String s, String def) {
        return s == null || s.isEmpty() ? def : s;
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
                if (r == 0) r = Long.compare(a.key(), b.key());
                return desc ? -r : r;
            }
        });
        return list;
    }

    public static List<MediaItem> videosOnly(List<MediaItem> src) {
        List<MediaItem> list = new ArrayList<>();
        for (MediaItem m : src) {
            if (m.isVideo) list.add(m);
        }
        return list;
    }

    public static int countVideos(List<MediaItem> src) {
        int n = 0;
        for (MediaItem m : src) {
            if (m.isVideo) n++;
        }
        return n;
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
