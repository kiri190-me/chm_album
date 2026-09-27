package com.chm.album;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Locale;

/** 격자 한 칸: 썸네일 위에 동영상 길이와 선택 표시를 그린다. */
public class MediaCellView extends SquareImageView {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Path tick = new Path();
    private final float density;

    private String duration;
    private boolean selectionMode;
    private boolean checked;
    private int shadeHeight = -1;

    public MediaCellView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(11.5f * density);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setShadowLayer(2 * density, 0, 0, 0x66000000);
        dimPaint.setColor(0x33000000);
    }

    /** 동영상이면 길이(ms), 사진이면 0. */
    public void setDuration(long ms, boolean video) {
        duration = video ? formatDuration(ms) : null;
        invalidate();
    }

    public void setSelection(boolean mode, boolean isChecked) {
        if (selectionMode == mode && checked == isChecked) return;
        selectionMode = mode;
        checked = isChecked;
        invalidate();
    }

    public static String formatDuration(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        if (duration != null) {
            int sh = (int) (28 * density);
            if (shadeHeight != h) {
                shadePaint.setShader(new LinearGradient(0, h - sh, 0, h, 0x00000000, 0x88000000, Shader.TileMode.CLAMP));
                shadeHeight = h;
            }
            canvas.drawRect(0, h - sh, w, h, shadePaint);
            canvas.drawText(duration, w - 6 * density, h - 6 * density, textPaint);
            // 작은 재생 삼각형
            float tx = 6 * density;
            float ty = h - 14.5f * density;
            tick.reset();
            tick.moveTo(tx, ty);
            tick.lineTo(tx + 7 * density, ty + 4.5f * density);
            tick.lineTo(tx, ty + 9 * density);
            tick.close();
            circlePaint.setStyle(Paint.Style.FILL);
            circlePaint.setColor(0xFFFFFFFF);
            canvas.drawPath(tick, circlePaint);
        }

        if (selectionMode) {
            if (checked) canvas.drawRect(0, 0, w, h, dimPaint);
            float r = 10 * density;
            float cx = 6 * density + r;
            float cy = 6 * density + r;
            circlePaint.setStyle(Paint.Style.FILL);
            circlePaint.setColor(checked ? Ui.ACCENT : 0x55000000);
            canvas.drawCircle(cx, cy, r, circlePaint);
            circlePaint.setStyle(Paint.Style.STROKE);
            circlePaint.setStrokeWidth(1.6f * density);
            circlePaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, r, circlePaint);
            if (checked) {
                tick.reset();
                tick.moveTo(cx - 4.5f * density, cy);
                tick.lineTo(cx - 1.2f * density, cy + 3.4f * density);
                tick.lineTo(cx + 4.8f * density, cy - 3.6f * density);
                circlePaint.setStrokeWidth(2f * density);
                circlePaint.setStrokeCap(Paint.Cap.ROUND);
                circlePaint.setStrokeJoin(Paint.Join.ROUND);
                canvas.drawPath(tick, circlePaint);
            }
        }
    }
}
