package com.chm.album;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ListView;

/**
 * 격자용 ListView.
 * - 두 손가락 핀치로 열 개수 변경
 * - 길게 누른 뒤 손을 떼지 않고 끌면 지나간 항목을 한꺼번에 선택 (가장자리에서는 자동 스크롤)
 */
public class PinchListView extends ListView {

    public interface OnPinchListener {
        /** @param zoomIn true 면 확대(열 감소), false 면 축소(열 증가) */
        void onPinch(boolean zoomIn);
    }

    /** 끌어서 선택할 때 손가락 아래 항목을 알려준다. */
    public interface DragSelectCallback {
        /** 목록 position 의 줄(child)에서 x 위치에 있는 항목 번호. 없으면 -1. */
        int itemIndexAt(int position, View row, float x);

        void onDragTo(int itemIndex);
    }

    private final ScaleGestureDetector detector;
    private OnPinchListener listener;
    private float accumulated = 1f;
    private boolean fired;

    private DragSelectCallback dragCallback;
    private boolean dragging;
    private float dragX;
    private float dragY;
    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (!dragging) return;
            int edge = Ui.dp(getContext(), 72);
            int speed = 0;
            if (dragY < edge) speed = -Math.round((edge - dragY) / edge * Ui.dp(getContext(), 18));
            else if (dragY > getHeight() - edge) speed = Math.round((dragY - (getHeight() - edge)) / edge * Ui.dp(getContext(), 18));
            if (speed != 0) {
                scrollListBy(speed);
                updateDrag();
            }
            postOnAnimation(this);
        }
    };

    public PinchListView(Context context) {
        super(context);
        detector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                accumulated = 1f;
                fired = false;
                return !dragging;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                accumulated *= d.getScaleFactor();
                if (!fired && listener != null) {
                    if (accumulated > 1.25f) {
                        fired = true;
                        listener.onPinch(true);
                    } else if (accumulated < 0.8f) {
                        fired = true;
                        listener.onPinch(false);
                    }
                }
                return true;
            }
        });
    }

    public void setOnPinchListener(OnPinchListener l) {
        listener = l;
    }

    public void setDragSelectCallback(DragSelectCallback cb) {
        dragCallback = cb;
    }

    /** 항목을 길게 눌렀을 때 호출: 이제부터 손가락 움직임은 스크롤이 아니라 선택이 된다. */
    public void startDragSelect() {
        if (dragCallback == null) return;
        dragging = true;
        // 목록과 눌린 칸이 진행 중이던 터치(스크롤, 클릭)를 잊도록 취소를 보낸다
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, dragX, dragY, 0);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        removeCallbacks(autoScroll);
        postOnAnimation(autoScroll);
    }

    private void updateDrag() {
        int pos = pointToPosition((int) dragX, (int) dragY);
        if (pos == INVALID_POSITION) return;
        View row = getChildAt(pos - getFirstVisiblePosition());
        if (row == null) return;
        int index = dragCallback.itemIndexAt(pos, row, dragX - row.getLeft());
        if (index >= 0) dragCallback.onDragTo(index);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || !dragging) {
            dragX = ev.getX();
            dragY = ev.getY();
        }
        if (dragging) {
            if (action == MotionEvent.ACTION_MOVE) {
                dragX = ev.getX();
                dragY = ev.getY();
                updateDrag();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                removeCallbacks(autoScroll);
            }
            return true;
        }

        detector.onTouchEvent(ev);
        if (detector.isInProgress() || ev.getPointerCount() > 1) {
            // 핀치 중에는 스크롤/클릭을 막는다
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }
}
