package com.chm.album;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** 동영상 자르기 구간 선택: 프레임 띠 위에 시작/끝 손잡이와 재생 위치를 그린다. */
public class RangeTrimView extends View {

    public interface Listener {
        /** 손잡이를 움직이는 중. movingStart 가 true 면 시작 손잡이. */
        void onRangeChanged(long startMs, long endMs, boolean movingStart);

        /** 구간 안을 눌러 재생 위치를 옮김 */
        void onSeek(long positionMs);
    }

    private static final int NONE = 0;
    private static final int START = 1;
    private static final int END = 2;
    private static final int SEEK = 3;
    /** 최소 구간 길이 */
    private static final long MIN_MS = 1000;

    private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint();
    private final float density;

    private Bitmap[] frames = new Bitmap[0];
    private long durationMs = 1;
    private long startMs;
    private long endMs = 1;
    private long positionMs;
    private int mode = NONE;
    private Listener listener;

    public RangeTrimView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        dimPaint.setColor(0xB0000000);
        handlePaint.setColor(0xFFFFC928);
        gripPaint.setColor(0xFF3A2A00);
        gripPaint.setStrokeWidth(1.5f * density);
        gripPaint.setStrokeCap(Paint.Cap.ROUND);
        headPaint.setColor(0xFFFFFFFF);
        emptyPaint.setColor(0xFF2A2A2A);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDuration(long ms) {
        durationMs = Math.max(1, ms);
        startMs = 0;
        endMs = durationMs;
        invalidate();
    }

    public void setFrames(Bitmap[] f) {
        frames = f;
        invalidate();
    }

    public void setPosition(long ms) {
        positionMs = ms;
        invalidate();
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    private float handleW() {
        return 16 * density;
    }

    /** 띠(프레임 영역)의 왼쪽/오른쪽 x */
    private float stripLeft() {
        return handleW();
    }

    private float stripRight() {
        return getWidth() - handleW();
    }

    private float xOf(long ms) {
        return stripLeft() + (stripRight() - stripLeft()) * ms / (float) durationMs;
    }

    private long msOf(float x) {
        float t = (x - stripLeft()) / (stripRight() - stripLeft());
        return Math.round(Math.max(0, Math.min(1, t)) * durationMs);
    }

    @Override
    protected void onDraw(Canvas c) {
        float top = 6 * density;
        float bottom = getHeight() - 6 * density;
        float l = stripLeft();
        float r = stripRight();

        // 프레임 띠
        c.drawRect(l, top, r, bottom, emptyPaint);
        if (frames.length > 0) {
            float cell = (r - l) / frames.length;
            RectF dst = new RectF();
            Rect srcRect = new Rect();
            for (int i = 0; i < frames.length; i++) {
                Bitmap b = frames[i];
                if (b == null) continue;
                dst.set(l + i * cell, top, l + (i + 1) * cell, bottom);
                // 가운데를 잘라 칸을 채운다
                float want = dst.width() / dst.height();
                float have = b.getWidth() / (float) b.getHeight();
                if (have > want) {
                    int w = Math.round(b.getHeight() * want);
                    srcRect.set((b.getWidth() - w) / 2, 0, (b.getWidth() + w) / 2, b.getHeight());
                } else {
                    int h = Math.round(b.getWidth() / want);
                    srcRect.set(0, (b.getHeight() - h) / 2, b.getWidth(), (b.getHeight() + h) / 2);
                }
                c.drawBitmap(b, srcRect, dst, framePaint);
            }
        }

        float sx = xOf(startMs);
        float ex = xOf(endMs);
        c.drawRect(l, top, sx, bottom, dimPaint);
        c.drawRect(ex, top, r, bottom, dimPaint);

        // 선택 구간 테두리와 손잡이
        float edge = 3 * density;
        c.drawRect(sx, top, ex, top + edge, handlePaint);
        c.drawRect(sx, bottom - edge, ex, bottom, handlePaint);
        float hw = handleW();
        float rad = 4 * density;
        c.drawRoundRect(new RectF(sx - hw, top, sx, bottom), rad, rad, handlePaint);
        c.drawRoundRect(new RectF(ex, top, ex + hw, bottom), rad, rad, handlePaint);
        float midY = (top + bottom) / 2;
        float g = 7 * density;
        c.drawLine(sx - hw / 2, midY - g, sx - hw / 2, midY + g, gripPaint);
        c.drawLine(ex + hw / 2, midY - g, ex + hw / 2, midY + g, gripPaint);

        // 재생 위치
        if (positionMs >= startMs && positionMs <= endMs) {
            float px = xOf(positionMs);
            c.drawRoundRect(new RectF(px - 1.5f * density, 0, px + 1.5f * density, getHeight()),
                    density, density, headPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float sx = xOf(startMs) - handleW() / 2;
                float ex = xOf(endMs) + handleW() / 2;
                float touch = 24 * density;
                float ds = Math.abs(x - sx);
                float de = Math.abs(x - ex);
                if (ds < touch && ds <= de) mode = START;
                else if (de < touch) mode = END;
                else mode = SEEK;
                getParent().requestDisallowInterceptTouchEvent(true);
                handleMove(x);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                handleMove(x);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = NONE;
                return true;
            default:
                return true;
        }
    }

    private void handleMove(float x) {
        if (mode == START) {
            startMs = Math.max(0, Math.min(msOf(x + handleW() / 2), endMs - MIN_MS));
            if (listener != null) listener.onRangeChanged(startMs, endMs, true);
        } else if (mode == END) {
            endMs = Math.min(durationMs, Math.max(msOf(x - handleW() / 2), startMs + MIN_MS));
            if (listener != null) listener.onRangeChanged(startMs, endMs, false);
        } else if (mode == SEEK) {
            positionMs = Math.max(startMs, Math.min(endMs, msOf(x)));
            if (listener != null) listener.onSeek(positionMs);
        }
        invalidate();
    }
}
