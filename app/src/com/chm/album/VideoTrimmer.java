package com.chm.album;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 동영상을 다시 인코딩하지 않고 구간만 잘라 MP4 로 저장한다 (화질 손실 없음, 빠름).
 * 영상은 키프레임에서만 시작할 수 있으므로 시작점이 바로 앞 키프레임으로 약간 당겨질 수 있다.
 */
public final class VideoTrimmer {

    public interface Progress {
        /** 0 ~ 1 */
        void onProgress(float fraction);
    }

    private VideoTrimmer() {
    }

    public static void trim(Context ctx, Uri src, long startMs, long endMs, File out, Progress progress)
            throws IOException {
        long startUs = startMs * 1000L;
        long endUs = endMs * 1000L;

        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ex.setDataSource(ctx, src, null);
            int count = ex.getTrackCount();
            int[] map = new int[count];
            int videoTrack = -1;
            int bufSize = 1024 * 1024;

            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for (int i = 0; i < count; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                map[i] = -1;
                if (mime == null) continue;
                boolean isVideo = mime.startsWith("video/");
                if (!isVideo && !mime.startsWith("audio/")) continue;
                try {
                    map[i] = mux.addTrack(f);
                } catch (Exception e) {
                    continue; // MP4 에 담을 수 없는 트랙은 건너뜀
                }
                if (isVideo && videoTrack < 0) videoTrack = i;
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufSize = Math.max(bufSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
            mux.setOrientationHint(rotation(ctx, src));

            // 1) 영상 트랙만 골라 시작점 직전 키프레임의 시각을 구한다
            long baseUs = startUs;
            if (videoTrack >= 0) {
                ex.selectTrack(videoTrack);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long t = ex.getSampleTime();
                if (t >= 0) baseUs = t;
            }
            // 2) 나머지 트랙도 선택하고 그 시각부터 읽는다
            int selected = 0;
            for (int i = 0; i < count; i++) {
                if (map[i] < 0) continue;
                if (i != videoTrack) ex.selectTrack(i);
                selected++;
            }
            if (selected == 0) throw new IOException("지원하지 않는 동영상 형식입니다");
            ex.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            mux.start();
            ByteBuffer buf = ByteBuffer.allocate(bufSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean[] done = new boolean[count];
            int remaining = selected;
            long span = Math.max(1, endUs - baseUs);

            while (remaining > 0) {
                int track = ex.getSampleTrackIndex();
                if (track < 0) break;
                long t = ex.getSampleTime();
                if (map[track] < 0 || done[track]) {
                    ex.advance();
                    continue;
                }
                if (t > endUs) {
                    done[track] = true;
                    remaining--;
                    ex.unselectTrack(track);
                    continue;
                }
                if (t < baseUs) { // 영상 시작보다 앞선 소리 등은 버린다
                    ex.advance();
                    continue;
                }
                info.offset = 0;
                info.size = ex.readSampleData(buf, 0);
                if (info.size < 0) break;
                info.presentationTimeUs = t - baseUs;
                info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                mux.writeSampleData(map[track], buf, info);
                if (progress != null) progress.onProgress(Math.min(1f, (t - baseUs) / (float) span));
                ex.advance();
            }
            try {
                mux.stop();
            } catch (IllegalStateException e) {
                throw new IOException("잘라낸 구간이 비어 있습니다");
            }
        } finally {
            ex.release();
            if (mux != null) mux.release();
        }
        if (out.length() == 0) throw new IOException("잘라낸 구간이 비어 있습니다");
    }

    private static int rotation(Context ctx, Uri src) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, src);
            String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }
}
