package com.chm.album;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * ListView 한 줄에 사진 N장을 배치하는 격자 어댑터.
 * grouped 이면 정렬 기준 날짜로 하루 단위 헤더를 끼워 넣는다.
 */
public class PhotoGridAdapter extends BaseAdapter {

    public interface OnPhotoClick {
        void onPhotoClick(List<MediaItem> items, int index);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ROW = 1;

    private static final class Row {
        final int type;
        final String title;
        final int start;
        final int end;

        Row(int type, String title, int start, int end) {
            this.type = type;
            this.title = title;
            this.start = start;
            this.end = end;
        }
    }

    private final Context ctx;
    private final ThumbnailLoader loader;
    private final boolean grouped;
    private final OnPhotoClick click;
    private final int gap;
    private List<MediaItem> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private int basis;
    private int columns;

    public PhotoGridAdapter(Context ctx, boolean grouped, int columns, OnPhotoClick click) {
        this.ctx = ctx;
        this.loader = ThumbnailLoader.get(ctx);
        this.grouped = grouped;
        this.columns = columns;
        this.click = click;
        this.gap = Math.max(1, Ui.dp(ctx, 1.5f));
    }

    /** items 는 이미 basis 기준으로 정렬되어 있어야 한다. */
    public void setItems(List<MediaItem> items, int basis) {
        this.items = items;
        this.basis = basis;
        rebuild();
    }

    public void setColumns(int columns) {
        this.columns = columns;
        rebuild();
    }

    public int getColumns() {
        return columns;
    }

    private void rebuild() {
        rows.clear();
        int i = 0;
        int n = items.size();
        while (i < n) {
            int groupEnd = n;
            if (grouped) {
                long key = Ui.dayKey(items.get(i).time(basis));
                groupEnd = i + 1;
                while (groupEnd < n && Ui.dayKey(items.get(groupEnd).time(basis)) == key) groupEnd++;
                rows.add(new Row(TYPE_HEADER, Ui.dayLabel(items.get(i).time(basis)), i, groupEnd));
            }
            for (int s = i; s < groupEnd; s += columns) {
                rows.add(new Row(TYPE_ROW, null, s, Math.min(s + columns, groupEnd)));
            }
            i = groupEnd;
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Object getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @Override
    public boolean isEnabled(int position) {
        return false; // 클릭은 각 셀이 직접 처리
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Row row = rows.get(position);
        if (row.type == TYPE_HEADER) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = Ui.text(ctx, "", 15, Ui.TEXT, true);
                tv.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 18), Ui.dp(ctx, 16), Ui.dp(ctx, 10));
            }
            tv.setText(row.title);
            return tv;
        }

        LinearLayout line = (LinearLayout) convertView;
        if (line == null || line.getChildCount() != columns) {
            line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            line.setPadding(0, 0, 0, gap);
            for (int c = 0; c < columns; c++) {
                SquareImageView iv = new SquareImageView(ctx);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFFE6E6E6);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c < columns - 1) lp.rightMargin = gap;
                line.addView(iv, lp);
            }
        }

        int cellPx = Math.max(64, ctx.getResources().getDisplayMetrics().widthPixels / columns);
        for (int c = 0; c < columns; c++) {
            SquareImageView iv = (SquareImageView) line.getChildAt(c);
            final int index = row.start + c;
            if (index < row.end) {
                iv.setVisibility(View.VISIBLE);
                loader.load(items.get(index), iv, cellPx);
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        click.onPhotoClick(items, index);
                    }
                });
            } else {
                iv.setVisibility(View.INVISIBLE);
                iv.setOnClickListener(null);
                iv.setImageDrawable(null);
            }
        }
        return line;
    }
}
