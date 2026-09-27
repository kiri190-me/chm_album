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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ListView 한 줄에 사진 N장을 배치하는 격자 어댑터.
 * grouped 이면 정렬 기준 날짜로 하루 단위 헤더를 끼워 넣는다.
 * 길게 누르면 선택 모드가 되어 여러 항목을 골라 공유할 수 있다.
 */
public class PhotoGridAdapter extends BaseAdapter {

    public interface Listener {
        /** 선택 모드가 아닐 때 항목을 누름 */
        void onItemClick(List<MediaItem> items, int index);

        /** 선택 모드 진입/해제 또는 선택 개수 변경 */
        void onSelectionChanged(boolean selectionMode, int count);
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
    private final Listener listener;
    private final Set<Long> selected = new HashSet<>();
    private boolean selectionMode;
    private final int gap;
    private List<MediaItem> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private int basis;
    private int columns;

    public PhotoGridAdapter(Context ctx, boolean grouped, int columns, Listener listener) {
        this.ctx = ctx;
        this.loader = ThumbnailLoader.get(ctx);
        this.grouped = grouped;
        this.columns = columns;
        this.listener = listener;
        this.gap = Math.max(1, Ui.dp(ctx, 1.5f));
    }

    /** items 는 이미 basis 기준으로 정렬되어 있어야 한다. */
    public void setItems(List<MediaItem> items, int basis) {
        this.items = items;
        this.basis = basis;
        if (!selected.isEmpty()) {
            // 사라진 항목은 선택에서 뺀다
            Set<Long> alive = new HashSet<>();
            for (MediaItem m : items) alive.add(m.key());
            selected.retainAll(alive);
            listener.onSelectionChanged(selectionMode, selected.size());
        }
        rebuild();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void startSelection() {
        selectionMode = true;
        notifyDataSetChanged();
        listener.onSelectionChanged(true, selected.size());
    }

    public void endSelection() {
        selectionMode = false;
        selected.clear();
        notifyDataSetChanged();
        listener.onSelectionChanged(false, 0);
    }

    public boolean allSelected() {
        return !items.isEmpty() && selected.size() == items.size();
    }

    public void selectAll(boolean all) {
        selected.clear();
        if (all) {
            for (MediaItem m : items) selected.add(m.key());
        }
        notifyDataSetChanged();
        listener.onSelectionChanged(selectionMode, selected.size());
    }

    public List<MediaItem> getSelectedItems() {
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem m : items) {
            if (selected.contains(m.key())) out.add(m);
        }
        return out;
    }

    private void toggle(MediaItem m) {
        if (!selected.remove(m.key())) selected.add(m.key());
        notifyDataSetChanged();
        listener.onSelectionChanged(selectionMode, selected.size());
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
                MediaCellView iv = new MediaCellView(ctx);
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
            MediaCellView iv = (MediaCellView) line.getChildAt(c);
            final int index = row.start + c;
            if (index < row.end) {
                final MediaItem m = items.get(index);
                iv.setVisibility(View.VISIBLE);
                iv.setDuration(m.durationMs, m.isVideo);
                iv.setSelection(selectionMode, selected.contains(m.key()));
                iv.setContentDescription((m.isVideo ? "동영상 " : "사진 ") + m.name);
                loader.load(m, iv, cellPx);
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (selectionMode) toggle(m);
                        else listener.onItemClick(items, index);
                    }
                });
                iv.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (!selectionMode) {
                            selectionMode = true;
                            selected.add(m.key());
                            notifyDataSetChanged();
                            listener.onSelectionChanged(true, selected.size());
                        } else {
                            toggle(m);
                        }
                        return true;
                    }
                });
            } else {
                iv.setVisibility(View.INVISIBLE);
                iv.setOnClickListener(null);
                iv.setOnLongClickListener(null);
                iv.setImageDrawable(null);
            }
        }
        return line;
    }
}
