package com.chm.album;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.OverScroller;

/**
 * 확대/축소가 되는 이미지 보기.
 * 두 손가락으로 벌려 확대, 두 번 탭해 확대/원래대로, 확대한 상태에서 끌어 이동.
 * 확대하지 않은 상태의 좌우/아래 밀기와 한 번 탭은 Listener 로 넘긴다.
 */
public class ZoomImageView extends ImageView {

    public interface Listener {
        void onSingleTap();

        /** @param direction 1 = 다음(왼쪽으로 밈), -1 = 이전 */
        void onSwipe(int direction);

        void onSwipeDown();
    }

    private static final float MAX_ZOOM = 6f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final RectF rect = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;
    private final float density;

    private Listener listener;
    private boolean zoomEnabled = true;
    /** 화면에 꼭 맞출 때의 배율 */
    private float fitScale = 1f;
    private int drawableW;
    private int drawableH;
    private ValueAnimator animator;
    private boolean scaling;

    public ZoomImageView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        super.setScaleType(ScaleType.MATRIX);
        scroller = new OverScroller(context);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                if (!zoomEnabled || getDrawable() == null) return false;
                stopMotion();
                scaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float zoom = zoom();
                float target = Math.max(0.7f, Math.min(MAX_ZOOM * 1.2f, zoom * d.getScaleFactor()));
                float f = target / zoom;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                fixTranslation();
                setImageMatrix(matrix);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                // 너무 작거나 크게 놓으면 허용 범위로 되돌린다
                float zoom = zoom();
                if (zoom < 1f) animateZoom(1f, getWidth() / 2f, getHeight() / 2f);
                else if (zoom > MAX_ZOOM) animateZoom(MAX_ZOOM, d.getFocusX(), d.getFocusY());
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                stopMotion();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!zoomEnabled || getDrawable() == null) return false;
                if (isZoomed()) animateZoom(1f, getWidth() / 2f, getHeight() / 2f);
                else animateZoom(DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaling || !isZoomed()) return false;
                matrix.postTranslate(-dx, -dy);
                fixTranslation();
                setImageMatrix(matrix);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scaling) return false;
                if (isZoomed()) {
                    startFling(vx, vy);
                    return true;
                }
                if (e1 == null || listener == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 50 * density) {
                    listener.onSwipe(dx < 0 ? 1 : -1);
                    return true;
                }
                if (dy > 120 * density && Math.abs(dy) > Math.abs(dx) * 2) {
                    listener.onSwipeDown();
                    return true;
                }
                return false;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** 동영상 첫 화면처럼 확대가 필요 없을 때 끈다. */
    public void setZoomEnabled(boolean enabled) {
        zoomEnabled = enabled;
        if (!enabled) resetZoom();
    }

    public boolean isZoomed() {
        return zoom() > 1.01f;
    }

    public void resetZoom() {
        stopMotion();
        fitToView();
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        // 항상 MATRIX 를 쓴다
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        Drawable old = getDrawable();
        int oldW = drawableW;
        boolean keepZoom = old != null && drawable != null && oldW > 0 && isZoomed();
        super.setImageDrawable(drawable);
        drawableW = drawable == null ? 0 : drawable.getIntrinsicWidth();
        drawableH = drawable == null ? 0 : drawable.getIntrinsicHeight();
        if (keepZoom && drawableW > 0) {
            // 같은 사진의 더 선명한 버전으로 바뀐 경우: 보이는 부분을 그대로 유지한다
            float k = oldW / (float) drawableW;
            matrix.preScale(k, k);
            computeFitScale();
            setImageMatrix(matrix);
        } else {
            fitToView();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToView();
    }

    private void computeFitScale() {
        if (drawableW <= 0 || drawableH <= 0 || getWidth() == 0 || getHeight() == 0) {
            fitScale = 1f;
            return;
        }
        fitScale = Math.min(getWidth() / (float) drawableW, getHeight() / (float) drawableH);
    }

    private void fitToView() {
        computeFitScale();
        matrix.reset();
        if (drawableW > 0 && drawableH > 0) {
            matrix.postScale(fitScale, fitScale);
            matrix.postTranslate((getWidth() - drawableW * fitScale) / 2f, (getHeight() - drawableH * fitScale) / 2f);
        }
        setImageMatrix(matrix);
    }

    /** 화면 맞춤 대비 현재 배율 */
    private float zoom() {
        matrix.getValues(values);
        return fitScale <= 0 ? 1f : values[Matrix.MSCALE_X] / fitScale;
    }

    private RectF imageRect() {
        rect.set(0, 0, drawableW, drawableH);
        matrix.mapRect(rect);
        return rect;
    }

    /** 사진이 화면보다 작으면 가운데로, 크면 가장자리에 빈틈이 생기지 않게 옮긴다. */
    private void fixTranslation() {
        RectF r = imageRect();
        float w = getWidth();
        float h = getHeight();
        float dx = 0;
        float dy = 0;
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < h) dy = h - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    private void animateZoom(final float targetZoom, final float fx, final float fy) {
        stopMotion();
        final float start = zoom();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                float z = start + (targetZoom - start) * t;
                float f = z / zoom();
                matrix.postScale(f, f, fx, fy);
                fixTranslation();
                setImageMatrix(matrix);
            }
        });
        animator.start();
    }

    private void startFling(float vx, float vy) {
        RectF r = imageRect();
        int startX = Math.round(-r.left);
        int startY = Math.round(-r.top);
        int maxX = Math.max(0, Math.round(r.width() - getWidth()));
        int maxY = Math.max(0, Math.round(r.height() - getHeight()));
        scroller.fling(startX, startY, Math.round(-vx), Math.round(-vy), 0, maxX, 0, maxY);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (scroller.isFinished() || !scroller.computeScrollOffset()) return;
        RectF r = imageRect();
        matrix.postTranslate(-scroller.getCurrX() - r.left, -scroller.getCurrY() - r.top);
        fixTranslation();
        setImageMatrix(matrix);
        postInvalidateOnAnimation();
    }

    private void stopMotion() {
        if (animator != null) animator.cancel();
        scroller.forceFinished(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) scaling = false;
        return true;
    }
}
