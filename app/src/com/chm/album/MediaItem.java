package com.chm.album;

import android.net.Uri;

/** MediaStore 에서 읽어온 사진 또는 동영상 하나. */
public class MediaItem {
    public final long id;
    public final Uri uri;
    public final boolean isVideo;
    public final String mime;
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
    /** 동영상 길이(ms). 사진은 0. */
    public final long durationMs;
    /** Android 10+ 의 저장 폴더 (예: "DCIM/Camera/"). 없으면 null. */
    public final String relativePath;
    /** 파일 경로 (Android 9 이하에서 저장 위치를 정할 때 사용). 없으면 null. */
    public final String dataPath;
    /** 휴지통 항목이 완전히 삭제될 시각(ms). 휴지통 밖의 항목은 0. */
    public long expiresMs;

    public MediaItem(long id, Uri uri, boolean isVideo, String mime, String name,
                     String bucketId, String bucketName,
                     long dateTakenMs, long dateAddedMs, long dateModifiedMs,
                     int width, int height, int orientation, long size, long durationMs,
                     String relativePath, String dataPath) {
        this.id = id;
        this.uri = uri;
        this.isVideo = isVideo;
        this.mime = mime;
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
        this.durationMs = durationMs;
        this.relativePath = relativePath;
        this.dataPath = dataPath;
    }

    /** 사진과 동영상은 id 공간이 달라서 목록 안에서 구분하는 키. */
    public long key() {
        return isVideo ? -id : id;
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
