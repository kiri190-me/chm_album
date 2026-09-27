package com.chm.album;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 휴지통: 지운 사진/동영상을 30일 동안 보관한다.
 * 항목을 눌러 선택한 뒤 복원하거나 완전히 삭제하고, "비우기"로 전부 삭제한다.
 */
public class TrashActivity extends Activity implements TrashController.Callback {

    public static void open(Context ctx) {
        ctx.startActivity(new Intent(ctx, TrashActivity.class));
    }

    private static final long DAY = 24L * 60 * 60 * 1000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TrashController trash;
    private PhotoGridAdapter adapter;
    private TextView title;
    private TextView subtitle;
    private ImageView back;
    private TextView emptyButton;
    private TextView selectAll;
    private TextView emptyText;
    private PinchListView list;
    private View actions;
    private List<MediaItem> items = new ArrayList<>();

    private final Runnable reload = new Runnable() {
        @Override
        public void run() {
            load();
        }
    };
    private final ContentObserver observer = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            main.removeCallbacks(reload);
            main.postDelayed(reload, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        trash = new TrashController(this, this);
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
        title = Ui.text(this, "휴지통", 20, Ui.TEXT, true);
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
                if (!adapter.isSelectionMode()) adapter.startSelection();
                adapter.selectAll(!adapter.allSelected());
            }
        });
        header.addView(selectAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36)));

        emptyButton = Ui.pillButton(this, "비우기");
        emptyButton.setTextColor(0xFFE0243C);
        emptyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trash.deleteForever(new ArrayList<>(items));
            }
        });
        header.addView(emptyButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36)));
        root.addView(header);

        FrameLayout content = new FrameLayout(this);
        list = new PinchListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        adapter = new PhotoGridAdapter(this, false, 4, new PhotoGridAdapter.Listener() {
            @Override
            public void onItemClick(List<MediaItem> all, int index) {
                // 휴지통에서는 눌러서 바로 선택한다
                adapter.toggleItem(all.get(index));
            }

            @Override
            public void onSelectionChanged(boolean selectionMode, int count) {
                updateHeader();
            }
        });
        adapter.setBadger(new PhotoGridAdapter.Badger() {
            @Override
            public String badge(MediaItem m) {
                if (m.expiresMs <= 0) return null;
                long days = Math.max(0, (m.expiresMs - System.currentTimeMillis() + DAY - 1) / DAY);
                return days + "일";
            }
        });
        list.setAdapter(adapter);
        content.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        emptyText = Ui.text(this, "휴지통이 비어 있습니다", 16, Ui.SUBTEXT, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(View.GONE);
        content.addView(emptyText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        actions = Ui.actionBar(this, Ui.TEXT, Ui.BG,
                new int[]{R.drawable.ic_restore, R.drawable.ic_delete},
                new String[]{"복원", "삭제"},
                new View.OnClickListener[]{
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                trash.restore(adapter.getSelectedItems());
                            }
                        },
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                trash.deleteForever(adapter.getSelectedItems());
                            }
                        }});
        root.addView(actions);
        setContentView(root);

        if (TrashController.usesSystemTrash()) {
            getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
            getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer);
        }
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(observer);
        main.removeCallbacksAndMessages(null);
        io.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (adapter.isSelectionMode()) {
            adapter.endSelection();
            return;
        }
        super.onBackPressed();
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
        load();
    }

    private void load() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<MediaItem> loaded = TrashController.usesSystemTrash()
                        ? MediaRepository.queryTrashed(getContentResolver())
                        : LegacyTrash.list(TrashActivity.this);
                // 곧 지워질 항목이 먼저 오도록
                Collections.sort(loaded, new Comparator<MediaItem>() {
                    @Override
                    public int compare(MediaItem a, MediaItem b) {
                        return Long.compare(a.expiresMs, b.expiresMs);
                    }
                });
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        items = loaded;
                        adapter.setItems(loaded, SortPrefs.BASIS_ADDED);
                        emptyText.setVisibility(loaded.isEmpty() ? View.VISIBLE : View.GONE);
                        list.setVisibility(loaded.isEmpty() ? View.GONE : View.VISIBLE);
                        updateHeader();
                    }
                });
            }
        });
    }

    private void updateHeader() {
        boolean selecting = adapter.isSelectionMode();
        int n = selecting ? adapter.getSelectedItems().size() : 0;
        back.setImageResource(selecting ? R.drawable.ic_close : R.drawable.ic_back);
        back.setContentDescription(selecting ? "선택 취소" : "뒤로");
        title.setText(selecting ? (n == 0 ? "항목 선택" : n + "개 선택됨") : "휴지통");
        subtitle.setText(items.isEmpty() ? "지운 항목은 30일 동안 여기에 보관됩니다"
                : "항목 " + Ui.count(items.size()) + "개 · 30일이 지나면 완전히 삭제됩니다");
        selectAll.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectAll.setText(adapter.allSelected() ? "선택 해제" : "전체 선택");
        emptyButton.setVisibility(!selecting && !items.isEmpty() ? View.VISIBLE : View.GONE);
        actions.setVisibility(selecting ? View.VISIBLE : View.GONE);
    }
}
