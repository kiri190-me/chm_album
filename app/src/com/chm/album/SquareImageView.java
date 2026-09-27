package com.chm.album;

import android.content.Context;
import android.widget.ImageView;

/** 가로 폭과 같은 높이를 갖는 ImageView. */
public class SquareImageView extends ImageView {
    public SquareImageView(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
