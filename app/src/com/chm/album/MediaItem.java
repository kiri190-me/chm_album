package com.chm.album;

import android.net.Uri;

/** MediaStore 에서 읽어온 사진 한 장. */
public class MediaItem {
    public final long id;
    public final Uri uri;
    public final String name;
    public final String bucketId;
    public final String bucketName;
    /** 사진 메타데이터(EXIF)상의 촬영 시각(ms). 없으면 0. */
    public final long dateTakenMs;
    /** 기기(미디어 저장소)에 추가된 시각(ms). */
    public final long dateAddedMs;
    /** 파일 수정 시각(ms). */
    public final long dateModifiedMs;
    public final int width;
    public final int height;
    public final int orientation;
    public final long size;

    public MediaItem(long id, Uri uri, String name, String bucketId, String bucketName,
                     long dateTakenMs, long dateAddedMs, long dateModifiedMs,
                     int width, int height, int orientation, long size) {
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.bucketId = bucketId;
        this.bucketName = bucketName;
        this.dateTakenMs = dateTakenMs;
        this.dateAddedMs = dateAddedMs;
        this.dateModifiedMs = dateModifiedMs;
        this.width = width;
        this.height = height;
        this.orientation = orientation;
        this.size = size;
    }

    /** 정렬 기준에 해당하는 시각(ms). 메타데이터가 없으면 파일 시각으로 대체한다. */
    public long time(int basis) {
        if (basis == SortPrefs.BASIS_TAKEN) {
            if (dateTakenMs > 0) return dateTakenMs;
            if (dateModifiedMs > 0) return dateModifiedMs;
            return dateAddedMs;
        }
        if (dateAddedMs > 0) return dateAddedMs;
        return dateModifiedMs;
    }
}
