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

/** 폴더 하나의 사진/동영상 목록. */
public class AlbumActivity extends Activity implements TrashController.Callback {

    private static final String EXTRA_ID = "bucket_id";
    private static final String EXTRA_NAME = "bucket_name";

    public static void open(Context ctx, String bucketId, String name) {
        Intent i = new Intent(ctx, AlbumActivity.class);
        i.putExtra(EXTRA_ID, bucketId);
        i.putExtra(EXTRA_NAME, name);
        ctx.startActivity(i);
    }

    private SortPrefs prefs;
    private TrashController trash;
    private String bucketId;
    private String albumName;
    private TextView title;
    private TextView subtitle;
    private ImageView back;
    private ImageView sort;
    private TextView selectAll;
    private View selectionBar;
    private PhotoGridAdapter adapter;
    private List<MediaItem> items;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new SortPrefs(this);
        trash = new TrashController(this, this);
        bucketId = getIntent().getStringExtra(EXTRA_ID);
        albumName = getIntent().getStringExtra(EXTRA_NAME);
        Ui.lightNavigationBar(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));

        back = Ui.iconButton(this, R.drawable.ic_back, Ui.TEXT);
        back.setContentDescription("뒤로");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 4), 0, 0, 0);
        title = Ui.text(this, albumName, 20, Ui.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle = Ui.text(this, "", 12, Ui.SUBTEXT, false);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        selectAll = Ui.pillButton(this, "전체 선택");
        selectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adapter.selectAll(!adapter.allSelected());
            }
        });
        selectAll.setVisibility(View.GONE);
        header.addView(selectAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36)));

        sort = Ui.iconButton(this, R.drawable.ic_sort, Ui.TEXT);
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
        adapter = new PhotoGridAdapter(this, true, prefs.columns(), new PhotoGridAdapter.Listener() {
            @Override
            public void onItemClick(List<MediaItem> items, int index) {
                ViewerActivity.open(AlbumActivity.this, items, index);
            }

            @Override
            public void onSelectionChanged(boolean selectionMode, int count) {
                updateHeader();
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

        selectionBar = Ui.actionBar(this, Ui.TEXT, Ui.BG,
                new int[]{R.drawable.ic_share, R.drawable.ic_delete}, new String[]{"공유", "삭제"},
                new View.OnClickListener[]{new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ShareHelper.share(AlbumActivity.this, adapter.getSelectedItems());
                    }
                }, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        trash.moveToTrash(adapter.getSelectedItems());
                    }
                }});
        selectionBar.setVisibility(View.GONE);
        root.addView(selectionBar);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!trash.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        trash.onRequestPermissionsResult(requestCode, grantResults);
    }

    @Override
    public void onTrashDone(int op, List<MediaItem> affected) {
        if (adapter.isSelectionMode()) adapter.endSelection();
        reloadItems();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // 편집한 사본이 저장되었거나 항목이 지워졌을 수 있으므로 다시 읽는다
        reloadItems();
    }

    private void reloadItems() {
        MediaRepository.loadAsync(this, new MediaRepository.Callback() {
            @Override
            public void onLoaded(List<MediaItem> all) {
                if (isFinishing()) return;
                items = MediaRepository.inBucket(all, bucketId);
                apply();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (adapter.isSelectionMode()) {
            adapter.endSelection();
            return;
        }
        super.onBackPressed();
    }

    private void apply() {
        if (items == null) return;
        int basis = prefs.basis();
        adapter.setItems(MediaRepository.sorted(items, basis, prefs.descending()), basis);
        updateHeader();
    }

    private void updateHeader() {
        boolean selecting = adapter.isSelectionMode();
        sort.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectAll.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        back.setImageResource(selecting ? R.drawable.ic_close : R.drawable.ic_back);
        back.setContentDescription(selecting ? "선택 취소" : "뒤로");
        if (selecting) {
            int n = adapter.getSelectedItems().size();
            title.setText(n == 0 ? "항목 선택" : n + "개 선택됨");
            subtitle.setText(albumName);
            selectAll.setText(adapter.allSelected() ? "선택 해제" : "전체 선택");
        } else if (items != null) {
            title.setText(albumName);
            int videos = MediaRepository.countVideos(items);
            String counts = videos == 0
                    ? "사진 " + Ui.count(items.size())
                    : "사진 " + Ui.count(items.size() - videos) + " · 동영상 " + Ui.count(videos);
            subtitle.setText(counts + " · " + prefs.label());
        }
    }
}
