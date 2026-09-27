package com.chm.album;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Android 10 이하용 앱 자체 휴지통. (Android 11+ 는 시스템 휴지통을 쓴다.)
 * 지운 파일을 앱 전용 폴더에 옮겨 두고, 원래 위치와 날짜 등은 trash.json 에 기록한다.
 * 30일이 지난 항목은 목록을 읽을 때 완전히 지운다.
 */
final class LegacyTrash {
    static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;
    private static final String INDEX = "trash.json";

    private LegacyTrash() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "trash");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static JSONArray read(Context c) {
        File f = new File(dir(c), INDEX);
        if (!f.exists()) return new JSONArray();
        try {
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return new JSONArray(new String(buf, 0, off, "UTF-8"));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void write(Context c, JSONArray a) throws IOException {
        File tmp = new File(dir(c), INDEX + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(a.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(new File(dir(c), INDEX))) throw new IOException("휴지통 기록을 저장할 수 없습니다");
    }

    /** 원본을 휴지통 폴더에 복사한다. 원본 삭제는 호출한 쪽에서 한다. */
    static synchronized File copyIn(Context c, MediaItem m) throws IOException {
        File dest = new File(dir(c), System.currentTimeMillis() + "_" + m.id + "_" + m.name.replace('/', '_'));
        InputStream in = c.getContentResolver().openInputStream(m.uri);
        if (in == null) throw new IOException("파일을 열 수 없습니다");
        OutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
        return dest;
    }

    /** 원본 삭제까지 끝난 항목을 목록에 올린다. */
    static synchronized void commit(Context c, MediaItem m, File file) throws IOException {
        JSONArray a = read(c);
        try {
            JSONObject o = new JSONObject();
            o.put("file", file.getName());
            o.put("name", m.name);
            o.put("mime", m.mime);
            o.put("video", m.isVideo);
            o.put("bucketId", m.bucketId);
            o.put("bucketName", m.bucketName);
            o.put("taken", m.dateTakenMs);
            o.put("added", m.dateAddedMs);
            o.put("modified", m.dateModifiedMs);
            o.put("w", m.width);
            o.put("h", m.height);
            o.put("orientation", m.orientation);
            o.put("size", m.size);
            o.put("duration", m.durationMs);
            if (m.relativePath != null) o.put("relativePath", m.relativePath);
            if (m.dataPath != null) o.put("dataPath", m.dataPath);
            o.put("trashedAt", System.currentTimeMillis());
            a.put(o);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        write(c, a);
    }

    /** 원본을 지우지 못해 휴지통 이동을 취소할 때 복사본을 버린다. */
    static void discardCopy(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** 휴지통 목록. 30일이 지난 항목은 이때 완전히 삭제한다. */
    static synchronized List<MediaItem> list(Context c) {
        JSONArray a = read(c);
        JSONArray keep = new JSONArray();
        List<MediaItem> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        File d = dir(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            File f = new File(d, o.optString("file"));
            long expires = o.optLong("trashedAt") + KEEP_MS;
            if (!f.exists()) continue;
            if (expires <= now) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            keep.put(o);
            MediaItem m = new MediaItem(
                    (f.getName().hashCode() & 0x7fffffffL) + (1L << 40),
                    Uri.fromFile(f),
                    o.optBoolean("video"),
                    o.optString("mime", "image/jpeg"),
                    o.optString("name"),
                    o.optString("bucketId", "_"),
                    o.optString("bucketName", "기타"),
                    o.optLong("taken"),
                    o.optLong("added"),
                    o.optLong("modified"),
                    o.optInt("w"),
                    o.optInt("h"),
                    o.optInt("orientation"),
                    o.optLong("size"),
                    o.optLong("duration"),
                    o.has("relativePath") ? o.optString("relativePath") : null,
                    o.has("dataPath") ? o.optString("dataPath") : null);
            m.expiresMs = expires;
            out.add(m);
        }
        if (keep.length() != a.length()) {
            try {
                write(c, keep);
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    /** 휴지통 파일을 원래 폴더로 되돌린다. */
    static void restore(Context c, MediaItem m) throws IOException {
        File f = new File(m.uri.getPath());
        MediaSaver.save(c, f, m, m.name, m.mime);
        remove(c, m);
    }

    /** 휴지통에서 완전히 지운다. */
    static synchronized void remove(Context c, MediaItem m) {
        File f = new File(m.uri.getPath());
        String name = f.getName();
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        JSONArray a = read(c);
        JSONArray keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !name.equals(o.optString("file"))) keep.put(o);
        }
        try {
            write(c, keep);
        } catch (IOException ignored) {
        }
    }
}
