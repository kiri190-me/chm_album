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
        /** 선택 모드가 아닐 때 항목을 누름. cell 은 눌린 칸 (열기 애니메이션의 시작 위치). */
        void onItemClick(List<MediaItem> items, int index, View cell);

        /** 선택 모드 진입/해제 또는 선택 개수 변경 */
        void onSelectionChanged(boolean selectionMode, int count);
    }

    /** 칸 오른쪽 위에 붙일 짧은 표시 */
    public interface Badger {
        String badge(MediaItem m);
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
    private Badger badger;

    // 길게 눌러 끌기 선택
    private PinchListView host;
    private int dragAnchor = -1;
    private boolean dragSelects;
    private Set<Long> dragBaseline;
    private int dragLast = -1;
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

    /** 목록에 연결: 길게 누른 뒤 끌어서 여러 항목 선택, 보기 화면과 위치 맞추기에 쓴다. */
    public void attachTo(PinchListView list) {
        host = list;
        list.setAdapter(this);
        list.setDragSelectCallback(new PinchListView.DragSelectCallback() {
            @Override
            public int itemIndexAt(int position, View row, float x) {
                if (position < 0 || position >= rows.size()) return -1;
                Row r = rows.get(position);
                if (r.type != TYPE_ROW) return -1;
                int col = (int) (x / Math.max(1f, row.getWidth() / (float) columns));
                return Math.max(r.start, Math.min(r.end - 1, r.start + col));
            }

            @Override
            public void onDragTo(int index) {
                dragTo(index);
            }
        });
    }

    /** 길게 누른 항목부터 끌기 선택을 시작한다. 처음 항목이 선택되면 끌린 범위도 선택, 해제되면 범위도 해제. */
    private void beginDrag(int anchor) {
        MediaItem m = items.get(anchor);
        dragSelects = !selected.contains(m.key());
        dragBaseline = new HashSet<>(selected);
        dragAnchor = anchor;
        dragLast = -1;
        selectionMode = true;
        dragTo(anchor);
        if (host != null) host.startDragSelect();
    }

    private void dragTo(int index) {
        if (dragAnchor < 0 || index < 0 || index >= items.size() || index == dragLast) return;
        dragLast = index;
        selected.clear();
        selected.addAll(dragBaseline);
        int lo = Math.min(dragAnchor, index);
        int hi = Math.max(dragAnchor, index);
        for (int i = lo; i <= hi; i++) {
            long k = items.get(i).key();
            if (dragSelects) selected.add(k);
            else selected.remove(k);
        }
        notifyDataSetChanged();
        listener.onSelectionChanged(true, selected.size());
    }

    /** 항목이 들어 있는 목록 줄 위치와 열. 없으면 null. */
    private int[] locate(long key) {
        for (int p = 0; p < rows.size(); p++) {
            Row r = rows.get(p);
            if (r.type != TYPE_ROW) continue;
            for (int i = r.start; i < r.end; i++) {
                if (items.get(i).key() == key) return new int[]{p, i - r.start};
            }
        }
        return null;
    }

    /** 보기 화면에서 넘긴 사진이 목록에서도 보이도록 스크롤한다. */
    public void reveal(long key) {
        if (host == null) return;
        int[] at = locate(key);
        if (at == null) return;
        int first = host.getFirstVisiblePosition();
        int last = host.getLastVisiblePosition();
        View firstChild = host.getChildAt(0);
        View lastChild = host.getChildAt(host.getChildCount() - 1);
        boolean fullyVisible = at[0] > first && at[0] < last
                || at[0] == first && firstChild != null && firstChild.getTop() >= 0
                || at[0] == last && lastChild != null && lastChild.getBottom() <= host.getHeight();
        if (!fullyVisible) {
            int rowH = firstChild != null ? firstChild.getHeight() : 0;
            host.setSelectionFromTop(at[0], Math.max(0, (host.getHeight() - rowH) / 2));
        }
    }

    /** 항목 칸의 화면 좌표. 화면에 없으면 null. */
    public android.graphics.Rect cellRectOnScreen(long key) {
        if (host == null || !host.isShown()) return null;
        int[] at = locate(key);
        if (at == null) return null;
        View row = host.getChildAt(at[0] - host.getFirstVisiblePosition());
        if (!(row instanceof ViewGroup)) return null;
        View cell = ((ViewGroup) row).getChildAt(at[1]);
        if (cell == null || cell.getVisibility() != View.VISIBLE) return null;
        return Ui.screenRect(cell);
    }

    public void setBadger(Badger b) {
        badger = b;
        notifyDataSetChanged();
    }

    /** 선택 모드로 들어가며 이 항목을 선택/해제한다. */
    public void toggleItem(MediaItem m) {
        selectionMode = true;
        toggle(m);
    }

    public int getItemTotal() {
        return items.size();
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
                iv.setBadge(badger == null ? null : badger.badge(m));
                iv.setSelection(selectionMode, selected.contains(m.key()));
                iv.setContentDescription((m.isVideo ? "동영상 " : "사진 ") + m.name);
                loader.load(m, iv, cellPx);
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (selectionMode) toggle(m);
                        else listener.onItemClick(items, index, v);
                    }
                });
                iv.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        beginDrag(index);
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
