package com.chm.album;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ListView;

/** 두 손가락 핀치로 격자 열 개수를 바꿀 수 있는 ListView. */
public class PinchListView extends ListView {

    public interface OnPinchListener {
        /** @param zoomIn true 면 확대(열 감소), false 면 축소(열 증가) */
        void onPinch(boolean zoomIn);
    }

    private final ScaleGestureDetector detector;
    private OnPinchListener listener;
    private float accumulated = 1f;
    private boolean fired;

    public PinchListView(Context context) {
        super(context);
        detector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                accumulated = 1f;
                fired = false;
                return true;
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

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
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
