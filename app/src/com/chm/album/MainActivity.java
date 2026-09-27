package com.chm.album;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements TrashController.Callback {

    private static final int REQ_PERMISSION = 7;
    private static final int TAB_PHOTOS = 0;
    private static final int TAB_VIDEOS = 1;
    private static final int TAB_ALBUMS = 2;
    private static final String[] TAB_TITLES = {"사진", "동영상", "앨범"};
    private static final String STATE_TAB = "tab";

    private SortPrefs prefs;
    private TextView title;
    private TextView subtitle;
    private ImageView sortButton;
    private ImageView moreButton;
    private TrashController trash;
    private TextView selectAllButton;
    private ImageView closeSelectionButton;
    private PinchListView photosList;
    private PinchListView videosList;
    private GridView albumsGrid;
    private LinearLayout messageView;
    private TextView messageText;
    private Button messageButton;
    private LinearLayout tabBar;
    private View selectionBar;
    private final TextView[] tabViews = new TextView[3];

    private PhotoGridAdapter photoAdapter;
    private PhotoGridAdapter videoAdapter;
    private AlbumAdapter albumAdapter;

    private int currentTab = TAB_PHOTOS;
    private List<MediaItem> all = new ArrayList<>();
    private int videoCount;
    private List<MediaRepository.Album> albums = new ArrayList<>();
    private boolean permissionMissing;
    private boolean loaded;
    private boolean askedOnce;
    /** 마지막으로 적용한 정렬 설정 (다른 화면에서 바뀌었는지 확인용) */
    private String appliedSort = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reloadRunnable = new Runnable() {
        @Override
        public void run() {
            reload();
        }
    };
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(reloadRunnable);
            handler.postDelayed(reloadRunnable, 600);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new SortPrefs(this);
        trash = new TrashController(this, this);
        if (savedInstanceState != null) currentTab = savedInstanceState.getInt(STATE_TAB, TAB_PHOTOS);
        Ui.lightNavigationBar(this);
        setContentView(buildLayout());
        selectTab(currentTab);

        if (hasPermission()) {
            onPermissionReady();
        } else {
            showPermissionMessage();
            requestPermission();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 설정 화면에서 권한을 허용하고 돌아온 경우
        if (!loaded && hasPermission()) {
            onPermissionReady();
        } else if (loaded && !appliedSort.equals(prefs.label())) {
            applyData(); // 앨범 화면에서 정렬을 바꾼 경우
        }
        if (photoAdapter.getColumns() != prefs.columns()) {
            photoAdapter.setColumns(prefs.columns());
            videoAdapter.setColumns(prefs.columns());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            getContentResolver().unregisterContentObserver(observer);
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(reloadRunnable);
    }

    @Override
    public void onBackPressed() {
        PhotoGridAdapter a = currentGridAdapter();
        if (a != null && a.isSelectionMode()) {
            a.endSelection();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 레이아웃

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // 상단 제목 영역 (One UI 스타일의 큰 제목)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 36), Ui.dp(this, 8), Ui.dp(this, 12));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(this, "사진", 30, Ui.TEXT, true);
        title.setSingleLine(true);
        subtitle = Ui.text(this, "", 13, Ui.SUBTEXT, false);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setPadding(0, Ui.dp(this, 2), 0, 0);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sortButton = Ui.iconButton(this, R.drawable.ic_sort, Ui.TEXT);
        sortButton.setContentDescription("정렬");
        sortButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.showSortDialog(MainActivity.this, new Ui.SortListener() {
                    @Override
                    public void onSortChanged() {
                        applyData();
                    }
                });
            }
        });
        header.addView(sortButton, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        moreButton = Ui.iconButton(this, R.drawable.ic_more, Ui.TEXT);
        moreButton.setContentDescription("더보기");
        moreButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.showMenu(v, new String[]{"휴지통"}, new Runnable[]{new Runnable() {
                    @Override
                    public void run() {
                        TrashActivity.open(MainActivity.this);
                    }
                }});
            }
        });
        header.addView(moreButton, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        selectAllButton = Ui.pillButton(this, "전체 선택");
        selectAllButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PhotoGridAdapter a = currentGridAdapter();
                if (a != null) a.selectAll(!a.allSelected());
            }
        });
        LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        salp.bottomMargin = Ui.dp(this, 4);
        header.addView(selectAllButton, salp);

        closeSelectionButton = Ui.iconButton(this, R.drawable.ic_close, Ui.TEXT);
        closeSelectionButton.setContentDescription("선택 취소");
        closeSelectionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PhotoGridAdapter a = currentGridAdapter();
                if (a != null) a.endSelection();
            }
        });
        header.addView(closeSelectionButton, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        root.addView(header);

        // 본문
        FrameLayout content = new FrameLayout(this);

        PhotoGridAdapter.Listener gridListener = new PhotoGridAdapter.Listener() {
            @Override
            public void onItemClick(List<MediaItem> items, int index) {
                ViewerActivity.open(MainActivity.this, items, index);
            }

            @Override
            public void onSelectionChanged(boolean selectionMode, int count) {
                updateHeader();
            }
        };
        photoAdapter = new PhotoGridAdapter(this, true, prefs.columns(), gridListener);
        videoAdapter = new PhotoGridAdapter(this, true, prefs.columns(), gridListener);
        photosList = gridList(photoAdapter);
        videosList = gridList(videoAdapter);
        content.addView(photosList, match());
        content.addView(videosList, match());

        albumsGrid = new GridView(this);
        albumsGrid.setNumColumns(3);
        int side = Ui.dp(this, 16);
        albumsGrid.setPadding(side, Ui.dp(this, 4), side, Ui.dp(this, 8));
        albumsGrid.setClipToPadding(false);
        albumsGrid.setHorizontalSpacing(Ui.dp(this, 12));
        albumsGrid.setVerticalSpacing(Ui.dp(this, 8));
        albumsGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        albumsGrid.setSelector(android.R.color.transparent);
        albumAdapter = new AlbumAdapter(this);
        albumsGrid.setAdapter(albumAdapter);
        albumsGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                MediaRepository.Album a = albumAdapter.getItem(position);
                AlbumActivity.open(MainActivity.this, a.id, a.name);
            }
        });
        content.addView(albumsGrid, match());

        messageView = new LinearLayout(this);
        messageView.setOrientation(LinearLayout.VERTICAL);
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), Ui.dp(this, 48));
        messageText = Ui.text(this, "", 16, Ui.SUBTEXT, false);
        messageText.setGravity(Gravity.CENTER);
        messageView.addView(messageText);
        messageButton = new Button(this);
        messageButton.setAllCaps(false);
        messageButton.setTextColor(0xFFFFFFFF);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Ui.ACCENT);
        btnBg.setCornerRadius(Ui.dp(this, 24));
        messageButton.setBackground(btnBg);
        messageButton.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 46));
        blp.topMargin = Ui.dp(this, 20);
        messageView.addView(messageButton, blp);
        messageView.setVisibility(View.GONE);
        content.addView(messageView, match());

        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 하단 탭
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(Ui.BG);
        tabBar.setElevation(Ui.dp(this, 2));
        tabBar.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        for (int i = 0; i < TAB_TITLES.length; i++) {
            tabViews[i] = tab(TAB_TITLES[i], i);
            tabBar.addView(tabViews[i], new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        }
        root.addView(tabBar);

        // 선택 모드의 하단 동작 막대
        selectionBar = Ui.actionBar(this, Ui.TEXT, Ui.BG,
                new int[]{R.drawable.ic_share, R.drawable.ic_delete}, new String[]{"공유", "삭제"},
                new View.OnClickListener[]{new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PhotoGridAdapter a = currentGridAdapter();
                        if (a != null) ShareHelper.share(MainActivity.this, a.getSelectedItems());
                    }
                }, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PhotoGridAdapter a = currentGridAdapter();
                        if (a != null) trash.moveToTrash(a.getSelectedItems());
                    }
                }});
        selectionBar.setVisibility(View.GONE);
        root.addView(selectionBar);
        return root;
    }

    private PinchListView gridList(final PhotoGridAdapter adapter) {
        PinchListView list = new PinchListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setFastScrollEnabled(true);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(this, 8));
        list.setAdapter(adapter);
        list.setOnPinchListener(new PinchListView.OnPinchListener() {
            @Override
            public void onPinch(boolean zoomIn) {
                int cols = adapter.getColumns() + (zoomIn ? -1 : 1);
                cols = Math.max(2, Math.min(7, cols));
                if (cols != adapter.getColumns()) {
                    prefs.setColumns(cols);
                    photoAdapter.setColumns(cols);
                    videoAdapter.setColumns(cols);
                }
            }
        });
        return list;
    }

    private TextView tab(String label, final int index) {
        TextView tv = Ui.text(this, label, 15, Ui.TEXT, false);
        tv.setGravity(Gravity.CENTER);
        android.util.TypedValue out = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        tv.setBackgroundResource(out.resourceId);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentTab == index) {
                    scrollToTop();
                } else {
                    selectTab(index);
                }
            }
        });
        return tv;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private PhotoGridAdapter currentGridAdapter() {
        if (currentTab == TAB_PHOTOS) return photoAdapter;
        if (currentTab == TAB_VIDEOS) return videoAdapter;
        return null;
    }

    private void selectTab(int index) {
        PhotoGridAdapter before = currentGridAdapter();
        if (before != null && before.isSelectionMode()) before.endSelection();
        currentTab = index;
        for (int i = 0; i < tabViews.length; i++) styleTab(tabViews[i], i == index);
        updateVisibility();
        updateHeader();
    }

    private void styleTab(TextView tv, boolean selected) {
        tv.setTextColor(selected ? Ui.TEXT : Ui.SUBTEXT);
        tv.setTypeface(selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        if (selected) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.RECTANGLE);
            dot.setCornerRadius(Ui.dp(this, 2));
            dot.setColor(Ui.TEXT);
            dot.setSize(Ui.dp(this, 20), Ui.dp(this, 3));
            tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, dot);
            tv.setCompoundDrawablePadding(Ui.dp(this, 4));
        } else {
            tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        }
    }

    private void scrollToTop() {
        if (currentTab == TAB_PHOTOS) photosList.smoothScrollToPosition(0);
        else if (currentTab == TAB_VIDEOS) videosList.smoothScrollToPosition(0);
        else albumsGrid.smoothScrollToPosition(0);
    }

    /** 현재 탭에 보여줄 안내 문구. 없으면 null. */
    private String currentMessage() {
        if (permissionMissing) return "기기의 사진과 동영상을 보여주려면\n접근 권한이 필요합니다.";
        if (!loaded) return null;
        if (all.isEmpty()) return "표시할 사진이나 동영상이 없습니다.";
        if (currentTab == TAB_VIDEOS && videoCount == 0) return "동영상이 없습니다.";
        return null;
    }

    private void updateVisibility() {
        String message = currentMessage();
        boolean showMessage = message != null;
        messageView.setVisibility(showMessage ? View.VISIBLE : View.GONE);
        if (showMessage) {
            messageText.setText(message);
            messageButton.setVisibility(permissionMissing ? View.VISIBLE : View.GONE);
        }
        photosList.setVisibility(!showMessage && currentTab == TAB_PHOTOS ? View.VISIBLE : View.GONE);
        videosList.setVisibility(!showMessage && currentTab == TAB_VIDEOS ? View.VISIBLE : View.GONE);
        albumsGrid.setVisibility(!showMessage && currentTab == TAB_ALBUMS ? View.VISIBLE : View.GONE);
    }

    /** 제목/부제목과 오른쪽 위 버튼, 하단 막대를 현재 상태에 맞춘다. */
    private void updateHeader() {
        PhotoGridAdapter a = currentGridAdapter();
        boolean selecting = a != null && a.isSelectionMode();
        sortButton.setVisibility(selecting ? View.GONE : View.VISIBLE);
        moreButton.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectAllButton.setVisibility(selecting ? View.VISIBLE : View.GONE);
        closeSelectionButton.setVisibility(selecting ? View.VISIBLE : View.GONE);
        tabBar.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);

        if (selecting) {
            int n = a.getSelectedItems().size();
            title.setText(n == 0 ? "항목 선택" : n + "개 선택됨");
            subtitle.setText("공유하거나 삭제할 항목을 눌러 선택하세요");
            selectAllButton.setText(a.allSelected() ? "선택 해제" : "전체 선택");
            return;
        }
        title.setText(TAB_TITLES[currentTab]);
        if (!loaded) {
            subtitle.setText(prefs.label());
        } else if (currentTab == TAB_PHOTOS) {
            subtitle.setText("사진 " + Ui.count(all.size() - videoCount) + " · 동영상 "
                    + Ui.count(videoCount) + " · " + prefs.label());
        } else if (currentTab == TAB_VIDEOS) {
            subtitle.setText("동영상 " + Ui.count(videoCount) + "개 · " + prefs.label());
        } else {
            subtitle.setText("앨범 " + Ui.count(albums.size()) + "개 · " + prefs.label());
        }
    }

    // ---------------------------------------------------------------- 권한

    private String[] permissionNames() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasPermission() {
        for (String p : permissionNames()) {
            if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true;
        }
        // Android 14+ '일부 사진 및 동영상만 허용'
        return Build.VERSION.SDK_INT >= 34
                && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canAskAgain() {
        if (!askedOnce) return true;
        for (String p : permissionNames()) {
            if (shouldShowRequestPermissionRationale(p)) return true;
        }
        return false;
    }

    private void requestPermission() {
        askedOnce = true;
        requestPermissions(permissionNames(), REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (trash.onRequestPermissionsResult(requestCode, grantResults)) return;
        if (requestCode != REQ_PERMISSION) return;
        if (hasPermission()) {
            onPermissionReady();
        } else {
            showPermissionMessage();
        }
    }

    private void showPermissionMessage() {
        permissionMissing = true;
        messageButton.setText(canAskAgain() ? "권한 허용" : "설정에서 허용");
        messageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (canAskAgain()) {
                    requestPermission();
                } else {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    startActivity(i);
                }
            }
        });
        updateVisibility();
    }

    private void onPermissionReady() {
        loaded = true;
        permissionMissing = false;
        updateVisibility();
        getContentResolver().unregisterContentObserver(observer);
        getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer);
        reload();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!trash.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onTrashDone(int op, List<MediaItem> affected) {
        PhotoGridAdapter a = currentGridAdapter();
        if (a != null && a.isSelectionMode()) a.endSelection();
        reload();
    }

    // ---------------------------------------------------------------- 데이터

    private void reload() {
        MediaRepository.loadAsync(this, new MediaRepository.Callback() {
            @Override
            public void onLoaded(List<MediaItem> items) {
                if (isFinishing()) return;
                all = items;
                applyData();
            }
        });
    }

    /** 현재 정렬 설정으로 모든 탭을 다시 구성한다. */
    private void applyData() {
        appliedSort = prefs.label();
        int basis = prefs.basis();
        List<MediaItem> sorted = MediaRepository.sorted(all, basis, prefs.descending());
        List<MediaItem> videos = MediaRepository.videosOnly(sorted);
        videoCount = videos.size();
        photoAdapter.setItems(sorted, basis);
        videoAdapter.setItems(videos, basis);
        albums = MediaRepository.albums(sorted, basis);
        albumAdapter.setAlbums(albums);
        updateVisibility();
        updateHeader();
    }
}
