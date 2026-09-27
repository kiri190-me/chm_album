package com.chm.album;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 사진 편집 화면의 미리보기.
 * 90° 회전, 좌우 반전, 기울기(-45°~45°), 자르기 영역을 보여주고 모서리를 끌어 자를 영역을 정한다.
 *
 * 좌표계: "프레임"은 90° 회전까지 적용한 사진 크기(W×H)의 사각형이다. 기울이면 빈 곳이
 * 생기지 않도록 사진을 확대해 프레임을 채운다. 자르기 영역은 프레임 기준 0~1 비율로 저장해
 * 해상도와 상관없이 같은 결과가 나오도록 한다.
 */
public class CropView extends View {

    public interface Listener {
        void onCropChanged();
    }

    private static final int NONE = 0;
    private static final int MOVE = 1;
    private static final int TL = 2;
    private static final int TR = 3;
    private static final int BL = 4;
    private static final int BR = 5;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private Bitmap bitmap;
    private int rot90;
    private boolean flip;
    private float angle;
    /** 가로/세로 비율 (픽셀 기준). 0 이면 자유. */
    private float ratio;
    private final RectF crop = new RectF(0, 0, 1, 1);
    private Listener listener;

    // 화면 배치
    private float scale;
    private float offX;
    private float offY;

    // 터치
    private int mode = NONE;
    private float lastX;
    private float lastY;

    public CropView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        dimPaint.setColor(0xAA000000);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStrokeWidth(1.5f * density);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(0x88FFFFFF);
        gridPaint.setStrokeWidth(1f);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStrokeWidth(3.5f * density);
        handlePaint.setStrokeCap(Paint.Cap.SQUARE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setBitmap(Bitmap bm) {
        bitmap = bm;
        requestLayout();
        invalidate();
    }

    public int getRot90() {
        return rot90;
    }

    public boolean isFlipped() {
        return flip;
    }

    public float getAngle() {
        return angle;
    }

    public RectF getCrop() {
        return new RectF(crop);
    }

    public void rotate90() {
        rot90 = (rot90 + 3) % 4; // 왼쪽(반시계)으로 90°
        if (ratio > 0 && Math.abs(ratio - 1f) > 0.001f) ratio = 1f / ratio;
        resetCrop();
    }

    public void toggleFlip() {
        flip = !flip;
        // 좌우 반전하면 자르기 영역도 좌우로 뒤집는다
        crop.set(1 - crop.right, crop.top, 1 - crop.left, crop.bottom);
        changed();
    }

    public void setAngle(float degrees) {
        angle = Math.max(-45f, Math.min(45f, degrees));
        changed();
    }

    /** @param r 가로/세로 비율 (0 = 자유, -1 = 원본 비율) */
    public void setRatio(float r) {
        if (r < 0) r = frameW() / frameH();
        ratio = r;
        resetCrop();
    }

    public void reset() {
        rot90 = 0;
        flip = false;
        angle = 0;
        ratio = 0;
        resetCrop();
    }

    private void resetCrop() {
        crop.set(0, 0, 1, 1);
        if (ratio > 0 && bitmap != null) {
            float rn = ratio * frameH() / frameW(); // 정규화 좌표에서의 가로/세로
            float w = 1f;
            float h = w / rn;
            if (h > 1f) {
                h = 1f;
                w = rn;
            }
            crop.set((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2);
        }
        changed();
    }

    private void changed() {
        invalidate();
        if (listener != null) listener.onCropChanged();
    }

    private float frameW() {
        if (bitmap == null) return 1;
        return rot90 % 2 == 0 ? bitmap.getWidth() : bitmap.getHeight();
    }

    private float frameH() {
        if (bitmap == null) return 1;
        return rot90 % 2 == 0 ? bitmap.getHeight() : bitmap.getWidth();
    }

    /** 기울어진 사진이 W×H 프레임을 빈틈없이 덮기 위한 확대 배율. */
    public static float coverScale(float w, float h, float degrees) {
        double a = Math.toRadians(Math.abs(degrees));
        double c = Math.cos(a);
        double s = Math.sin(a);
        return (float) Math.max((w * c + h * s) / w, (w * s + h * c) / h);
    }

    /**
     * 원본 비트맵(srcW×srcH) 좌표를 프레임 좌표(왼쪽 위 0,0 ~ W,H)로 옮기는 행렬.
     * 저장할 때도 같은 함수를 쓴다.
     */
    public static Matrix frameMatrix(int srcW, int srcH, int rot90, boolean flip, float angle) {
        float w = rot90 % 2 == 0 ? srcW : srcH;
        float h = rot90 % 2 == 0 ? srcH : srcW;
        Matrix m = new Matrix();
        m.postTranslate(-srcW / 2f, -srcH / 2f);
        m.postRotate(rot90 * 90);
        if (flip) m.postScale(-1, 1);
        float s = coverScale(w, h, angle);
        m.postRotate(angle);
        m.postScale(s, s);
        m.postTranslate(w / 2f, h / 2f);
        return m;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidate();
    }

    private void layoutFrame() {
        float pad = 24 * density;
        float vw = getWidth() - 2 * pad;
        float vh = getHeight() - 2 * pad;
        scale = Math.min(vw / frameW(), vh / frameH());
        offX = (getWidth() - frameW() * scale) / 2f;
        offY = (getHeight() - frameH() * scale) / 2f;
    }

    private RectF cropOnScreen() {
        float fw = frameW() * scale;
        float fh = frameH() * scale;
        return new RectF(offX + crop.left * fw, offY + crop.top * fh, offX + crop.right * fw, offY + crop.bottom * fh);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null) return;
        layoutFrame();

        canvas.save();
        canvas.translate(offX, offY);
        canvas.scale(scale, scale);
        canvas.clipRect(0, 0, frameW(), frameH());
        canvas.drawBitmap(bitmap, frameMatrix(bitmap.getWidth(), bitmap.getHeight(), rot90, flip, angle), bitmapPaint);
        canvas.restore();

        RectF r = cropOnScreen();
        float left = offX;
        float top = offY;
        float right = offX + frameW() * scale;
        float bottom = offY + frameH() * scale;
        canvas.drawRect(left, top, right, r.top, dimPaint);
        canvas.drawRect(left, r.bottom, right, bottom, dimPaint);
        canvas.drawRect(left, r.top, r.left, r.bottom, dimPaint);
        canvas.drawRect(r.right, r.top, right, r.bottom, dimPaint);

        // 3분할 격자 (기울기 조정이나 드래그 중에 특히 유용)
        for (int i = 1; i < 3; i++) {
            float x = r.left + r.width() * i / 3f;
            float y = r.top + r.height() * i / 3f;
            canvas.drawLine(x, r.top, x, r.bottom, gridPaint);
            canvas.drawLine(r.left, y, r.right, y, gridPaint);
        }
        canvas.drawRect(r, borderPaint);

        float len = 18 * density;
        float o = handlePaint.getStrokeWidth() / 2f;
        drawCorner(canvas, r.left - o, r.top - o, len, len);
        drawCorner(canvas, r.right + o, r.top - o, -len, len);
        drawCorner(canvas, r.left - o, r.bottom + o, len, -len);
        drawCorner(canvas, r.right + o, r.bottom + o, -len, -len);
    }

    private void drawCorner(Canvas c, float x, float y, float dx, float dy) {
        c.drawLine(x, y, x + dx, y, handlePaint);
        c.drawLine(x, y, x, y + dy, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                RectF r = cropOnScreen();
                float x = e.getX();
                float y = e.getY();
                float touch = 32 * density;
                if (near(x, y, r.left, r.top, touch)) mode = TL;
                else if (near(x, y, r.right, r.top, touch)) mode = TR;
                else if (near(x, y, r.left, r.bottom, touch)) mode = BL;
                else if (near(x, y, r.right, r.bottom, touch)) mode = BR;
                else if (r.contains(x, y)) mode = MOVE;
                else mode = NONE;
                lastX = x;
                lastY = y;
                if (mode != NONE) getParent().requestDisallowInterceptTouchEvent(true);
                return mode != NONE;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = (e.getX() - lastX) / (frameW() * scale);
                float dy = (e.getY() - lastY) / (frameH() * scale);
                lastX = e.getX();
                lastY = e.getY();
                drag(dx, dy);
                changed();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = NONE;
                return true;
            default:
                return true;
        }
    }

    private static boolean near(float x, float y, float px, float py, float d) {
        return Math.abs(x - px) < d && Math.abs(y - py) < d;
    }

    private void drag(float dx, float dy) {
        float minW = 48 * density / (frameW() * scale);
        float minH = 48 * density / (frameH() * scale);
        if (mode == MOVE) {
            float ox = Math.max(-crop.left, Math.min(1 - crop.right, dx));
            float oy = Math.max(-crop.top, Math.min(1 - crop.bottom, dy));
            crop.offset(ox, oy);
            return;
        }
        boolean left = mode == TL || mode == BL;
        boolean top = mode == TL || mode == TR;
        float l = crop.left;
        float t = crop.top;
        float r = crop.right;
        float b = crop.bottom;
        if (left) l = clamp(l + dx, 0, r - minW);
        else r = clamp(r + dx, l + minW, 1);
        if (top) t = clamp(t + dy, 0, b - minH);
        else b = clamp(b + dy, t + minH, 1);

        if (ratio > 0) {
            // 비율 고정: 가로 폭을 기준으로 세로를 맞추고, 넘치면 줄인다
            float rn = ratio * frameH() / frameW();
            float w = r - l;
            float h = w / rn;
            float maxH = top ? b : 1 - t;
            if (h > maxH) {
                h = maxH;
                w = h * rn;
            }
            if (left) l = r - w;
            else r = l + w;
            if (top) t = b - h;
            else b = t + h;
        }
        crop.set(l, t, r, b);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
