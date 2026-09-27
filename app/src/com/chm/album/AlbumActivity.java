package com.chm.album;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** 폴더 하나의 사진 목록. */
public class AlbumActivity extends Activity {

    private static final String EXTRA_ID = "bucket_id";
    private static final String EXTRA_NAME = "bucket_name";

    public static void open(Context ctx, String bucketId, String name) {
        Intent i = new Intent(ctx, AlbumActivity.class);
        i.putExtra(EXTRA_ID, bucketId);
        i.putExtra(EXTRA_NAME, name);
        ctx.startActivity(i);
    }

    private SortPrefs prefs;
    private String bucketId;
    private TextView subtitle;
    private PhotoGridAdapter adapter;
    private List<MediaItem> items;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new SortPrefs(this);
        bucketId = getIntent().getStringExtra(EXTRA_ID);
        Ui.lightNavigationBar(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));

        ImageView back = Ui.iconButton(this, R.drawable.ic_back, Ui.TEXT);
        back.setContentDescription("뒤로");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 4), 0, 0, 0);
        TextView title = Ui.text(this, getIntent().getStringExtra(EXTRA_NAME), 20, Ui.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle = Ui.text(this, "", 12, Ui.SUBTEXT, false);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView sort = Ui.iconButton(this, R.drawable.ic_sort, Ui.TEXT);
        sort.setContentDescription("정렬");
        sort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.showSortDialog(AlbumActivity.this, new Ui.SortListener() {
                    @Override
                    public void onSortChanged() {
                        apply();
                    }
                });
            }
        });
        header.addView(sort, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        root.addView(header);

        final PinchListView list = new PinchListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setFastScrollEnabled(true);
        adapter = new PhotoGridAdapter(this, true, prefs.columns(), new PhotoGridAdapter.OnPhotoClick() {
            @Override
            public void onPhotoClick(List<MediaItem> items, int index) {
                ViewerActivity.open(AlbumActivity.this, items, index);
            }
        });
        list.setAdapter(adapter);
        list.setOnPinchListener(new PinchListView.OnPinchListener() {
            @Override
            public void onPinch(boolean zoomIn) {
                int cols = Math.max(2, Math.min(7, adapter.getColumns() + (zoomIn ? -1 : 1)));
                if (cols != adapter.getColumns()) {
                    prefs.setColumns(cols);
                    adapter.setColumns(cols);
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        List<MediaItem> cached = MediaRepository.last();
        if (cached != null) {
            items = MediaRepository.inBucket(cached, bucketId);
            apply();
        } else {
            // 프로세스가 재시작된 경우 다시 읽는다
            MediaRepository.loadAsync(this, new MediaRepository.Callback() {
                @Override
                public void onLoaded(List<MediaItem> all) {
                    items = MediaRepository.inBucket(all, bucketId);
                    apply();
                }
            });
        }
    }

    private void apply() {
        if (items == null) return;
        int basis = prefs.basis();
        adapter.setItems(MediaRepository.sorted(items, basis, prefs.descending()), basis);
        subtitle.setText("사진 " + Ui.count(items.size()) + "장 · " + prefs.label());
    }
}
