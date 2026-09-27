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

public class MainActivity extends Activity {

    private static final int REQ_PERMISSION = 7;
    private static final int TAB_PHOTOS = 0;
    private static final int TAB_ALBUMS = 1;
    private static final String STATE_TAB = "tab";

    private SortPrefs prefs;
    private TextView title;
    private TextView subtitle;
    private ImageView sortButton;
    private PinchListView photosList;
    private GridView albumsGrid;
    private LinearLayout messageView;
    private TextView messageText;
    private Button messageButton;
    private TextView tabPhotos;
    private TextView tabAlbums;

    private PhotoGridAdapter photoAdapter;
    private AlbumAdapter albumAdapter;

    private int currentTab = TAB_PHOTOS;
    private List<MediaItem> all = new ArrayList<>();
    private List<MediaRepository.Album> albums = new ArrayList<>();
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
        if (photoAdapter.getColumns() != prefs.columns()) photoAdapter.setColumns(prefs.columns());
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
        subtitle = Ui.text(this, "", 13, Ui.SUBTEXT, false);
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
        root.addView(header);

        // 본문
        FrameLayout content = new FrameLayout(this);

        photosList = new PinchListView(this);
        photosList.setDivider(null);
        photosList.setSelector(android.R.color.transparent);
        photosList.setFastScrollEnabled(true);
        photosList.setClipToPadding(false);
        photosList.setPadding(0, 0, 0, Ui.dp(this, 8));
        photoAdapter = new PhotoGridAdapter(this, true, prefs.columns(), new PhotoGridAdapter.OnPhotoClick() {
            @Override
            public void onPhotoClick(List<MediaItem> items, int index) {
                ViewerActivity.open(MainActivity.this, items, index);
            }
        });
        photosList.setAdapter(photoAdapter);
        photosList.setOnPinchListener(new PinchListView.OnPinchListener() {
            @Override
            public void onPinch(boolean zoomIn) {
                int cols = photoAdapter.getColumns() + (zoomIn ? -1 : 1);
                cols = Math.max(2, Math.min(7, cols));
                if (cols != photoAdapter.getColumns()) {
                    prefs.setColumns(cols);
                    photoAdapter.setColumns(cols);
                }
            }
        });
        content.addView(photosList, match());

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
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(Ui.BG);
        tabs.setElevation(Ui.dp(this, 2));
        tabs.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        tabPhotos = tab("사진", TAB_PHOTOS);
        tabAlbums = tab("앨범", TAB_ALBUMS);
        tabs.addView(tabPhotos, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        tabs.addView(tabAlbums, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        root.addView(tabs);
        return root;
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

    private void selectTab(int index) {
        currentTab = index;
        styleTab(tabPhotos, index == TAB_PHOTOS);
        styleTab(tabAlbums, index == TAB_ALBUMS);
        title.setText(index == TAB_PHOTOS ? "사진" : "앨범");
        updateVisibility();
        updateSubtitle();
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
        else albumsGrid.smoothScrollToPosition(0);
    }

    private void updateVisibility() {
        boolean showMessage = messageView.getVisibility() == View.VISIBLE;
        photosList.setVisibility(!showMessage && currentTab == TAB_PHOTOS ? View.VISIBLE : View.GONE);
        albumsGrid.setVisibility(!showMessage && currentTab == TAB_ALBUMS ? View.VISIBLE : View.GONE);
    }

    private void updateSubtitle() {
        if (!loaded) {
            subtitle.setText(prefs.label());
            return;
        }
        if (currentTab == TAB_PHOTOS) {
            subtitle.setText("사진 " + Ui.count(all.size()) + "장 · " + prefs.label());
        } else {
            subtitle.setText("앨범 " + Ui.count(albums.size()) + "개 · " + prefs.label());
        }
    }

    // ---------------------------------------------------------------- 권한

    private String permissionName() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPermission() {
        if (checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED) return true;
        // Android 14+ '일부 사진만 허용'
        return Build.VERSION.SDK_INT >= 34
                && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        askedOnce = true;
        requestPermissions(new String[]{permissionName()}, REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERMISSION) return;
        if (hasPermission()) {
            onPermissionReady();
        } else {
            showPermissionMessage();
        }
    }

    private void showPermissionMessage() {
        messageText.setText("기기의 사진을 보여주려면\n사진 접근 권한이 필요합니다.");
        messageButton.setVisibility(View.VISIBLE);
        final boolean canAsk = !askedOnce || shouldShowRequestPermissionRationale(permissionName());
        messageButton.setText(canAsk ? "권한 허용" : "설정에서 허용");
        messageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!askedOnce || shouldShowRequestPermissionRationale(permissionName())) {
                    requestPermission();
                } else {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    startActivity(i);
                }
            }
        });
        messageView.setVisibility(View.VISIBLE);
        updateVisibility();
    }

    private void onPermissionReady() {
        loaded = true;
        messageView.setVisibility(View.GONE);
        updateVisibility();
        getContentResolver().unregisterContentObserver(observer);
        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
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

    /** 현재 정렬 설정으로 두 탭을 다시 구성한다. */
    private void applyData() {
        appliedSort = prefs.label();
        int basis = prefs.basis();
        List<MediaItem> sorted = MediaRepository.sorted(all, basis, prefs.descending());
        photoAdapter.setItems(sorted, basis);
        albums = MediaRepository.albums(sorted, basis);
        albumAdapter.setAlbums(albums);

        if (loaded && all.isEmpty()) {
            messageText.setText("표시할 사진이 없습니다.");
            messageButton.setVisibility(View.GONE);
            messageView.setVisibility(View.VISIBLE);
        } else if (loaded) {
            messageView.setVisibility(View.GONE);
        }
        updateVisibility();
        updateSubtitle();
    }
}
