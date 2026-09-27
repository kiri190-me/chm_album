package com.chm.album;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 편집 결과를 원본과 같은 폴더에 새 파일(사본)로 저장한다.
 * 원본 폴더에 쓸 수 없으면 Pictures/Edited 또는 Movies/Edited 에 저장한다.
 */
public final class MediaSaver {
    private static final String COL_IS_PENDING = "is_pending"; // MediaStore.MediaColumns.IS_PENDING (API 29)

    private MediaSaver() {
    }

    /** Android 9 이하에서는 저장소 쓰기 권한이 필요하다. */
    public static boolean needsWritePermission() {
        return Build.VERSION.SDK_INT < 29;
    }

    /** 원본 이름에 접미사를 붙인 새 파일 이름. 예: 20260927_101010.jpg → 20260927_101010_edit.jpg */
    public static String derivedName(MediaItem src, String suffix, String ext) {
        String base = src.name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.isEmpty()) base = String.valueOf(System.currentTimeMillis());
        return base + "_" + suffix + "." + ext;
    }

    public static void save(Context ctx, File tmp, MediaItem src, String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            saveToMediaStore(ctx, tmp, src, name, mime);
        } else {
            saveToFile(ctx, tmp, src, name, mime);
        }
    }

    private static void saveToMediaStore(Context ctx, File tmp, MediaItem src, String name, String mime)
            throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri collection = src.isVideo
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String fallback = (src.isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Edited";

        Uri out = null;
        String[] folders = src.relativePath != null
                ? new String[]{src.relativePath, fallback}
                : new String[]{fallback};
        for (String folder : folders) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaRepository.COL_RELATIVE_PATH, folder);
            v.put(COL_IS_PENDING, 1);
            long taken = src.time(SortPrefs.BASIS_TAKEN);
            if (taken > 0) v.put(MediaStore.Images.ImageColumns.DATE_TAKEN, taken);
            try {
                out = cr.insert(collection, v);
            } catch (Exception e) {
                out = null; // 이 폴더에는 이 형식의 파일을 둘 수 없음
            }
            if (out != null) break;
        }
        if (out == null) throw new IOException("저장 위치를 만들 수 없습니다");

        try {
            OutputStream os = cr.openOutputStream(out);
            if (os == null) throw new IOException("파일을 열 수 없습니다");
            try {
                copy(new FileInputStream(tmp), os);
            } finally {
                os.close();
            }
            ContentValues done = new ContentValues();
            done.put(COL_IS_PENDING, 0);
            cr.update(out, done, null, null);
        } catch (IOException e) {
            cr.delete(out, null, null);
            throw e;
        }
    }

    private static void saveToFile(Context ctx, File tmp, MediaItem src, String name, String mime)
            throws IOException {
        File dir = null;
        if (src.dataPath != null) {
            File parent = new File(src.dataPath).getParentFile();
            if (parent != null && parent.canWrite()) dir = parent;
        }
        if (dir == null) {
            dir = new File(Environment.getExternalStoragePublicDirectory(
                    src.isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "Edited");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("폴더를 만들 수 없습니다");
        File dest = new File(dir, name);
        int dot = name.lastIndexOf('.');
        for (int n = 1; dest.exists(); n++) {
            // Android 10+ 의 MediaStore 처럼 "이름 (1).jpg" 형태로 겹치지 않게 한다
            dest = new File(dir, name.substring(0, dot) + " (" + n + ")" + name.substring(dot));
        }
        OutputStream os = new FileOutputStream(dest);
        try {
            copy(new FileInputStream(tmp), os);
        } finally {
            os.close();
        }
        MediaScannerConnection.scanFile(ctx.getApplicationContext(),
                new String[]{dest.getAbsolutePath()}, new String[]{mime}, null);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }
}
