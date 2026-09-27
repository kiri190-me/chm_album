package com.chm.album;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 전체 화면 사진 보기. 좌우로 밀어 이전/다음 사진, 탭으로 메뉴 표시/숨김. */
public class ViewerActivity extends Activity {

    private static final String STATE_INDEX = "index";
    private static List<MediaItem> sItems;
    private static int sStart;

    public static void open(Context ctx, List<MediaItem> items, int index) {
        sItems = new ArrayList<>(items);
        sStart = index;
        ctx.startActivity(new Intent(ctx, ViewerActivity.class));
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private List<MediaItem> items;
    private int index;
    private int generation;
    private boolean barsVisible = true;
    private boolean infoVisible;

    private ImageView image;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView topTitle;
    private TextView topSub;
    private TextView position;
    private TextView info;
    private SortPrefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        items = sItems;
        if (items == null || items.isEmpty()) {
            finish();
            return;
        }
        prefs = new SortPrefs(this);
        index = savedInstanceState != null ? savedInstanceState.getInt(STATE_INDEX, sStart) : sStart;
        index = Math.max(0, Math.min(items.size() - 1, index));
        setContentView(buildLayout());
        show(index);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_INDEX, index);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 상단 바
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 20));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xAA000000, 0x00000000}));
        ImageView back = Ui.iconButton(this, R.drawable.ic_back, 0xFFFFFFFF);
        back.setContentDescription("뒤로");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        topBar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 4), 0, 0, 0);
        topTitle = Ui.text(this, "", 16, 0xFFFFFFFF, true);
        topSub = Ui.text(this, "", 12, 0xCCFFFFFF, false);
        titles.addView(topTitle);
        titles.addView(topSub);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // 하단 바
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 12));
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xAA000000, 0x00000000}));

        info = Ui.text(this, "", 13, 0xFFFFFFFF, false);
        info.setLineSpacing(Ui.dp(this, 3), 1f);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xCC222222);
        card.setCornerRadius(Ui.dp(this, 16));
        info.setBackground(card);
        info.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        info.setVisibility(View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.bottomMargin = Ui.dp(this, 10);
        bottomBar.addView(info, ilp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        position = Ui.text(this, "", 13, 0xCCFFFFFF, false);
        actions.addView(position, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView infoBtn = Ui.iconButton(this, R.drawable.ic_info, 0xFFFFFFFF);
        infoBtn.setContentDescription("상세 정보");
        infoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                infoVisible = !infoVisible;
                info.setVisibility(infoVisible ? View.VISIBLE : View.GONE);
            }
        });
        actions.addView(infoBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        bottomBar.addView(actions);
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        final GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                setBarsVisible(!barsVisible);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > Ui.dp(ViewerActivity.this, 50)) {
                    move(dx < 0 ? 1 : -1);
                    return true;
                }
                if (dy > Ui.dp(ViewerActivity.this, 120) && Math.abs(dy) > Math.abs(dx) * 2) {
                    finish(); // 아래로 밀어서 닫기
                    return true;
                }
                return false;
            }
        });
        image.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestures.onTouchEvent(event);
            }
        });
        return root;
    }

    private void setBarsVisible(boolean visible) {
        barsVisible = visible;
        topBar.animate().alpha(visible ? 1f : 0f).setDuration(150).start();
        bottomBar.animate().alpha(visible ? 1f : 0f).setDuration(150).start();
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        if (!visible) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!barsVisible) {
                        topBar.setVisibility(View.GONE);
                        bottomBar.setVisibility(View.GONE);
                    }
                }
            }, 160);
        }
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(visible ? 0
                : View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void move(final int delta) {
        final int target = index + delta;
        if (target < 0 || target >= items.size()) {
            // 끝에서는 살짝 튕기는 효과
            image.animate().translationX(-delta * Ui.dp(this, 24)).setDuration(90).withEndAction(new Runnable() {
                @Override
                public void run() {
                    image.animate().translationX(0).setDuration(120).start();
                }
            }).start();
            return;
        }
        final float w = image.getWidth();
        image.animate().translationX(-delta * w).alpha(0.3f).setDuration(140).withEndAction(new Runnable() {
            @Override
            public void run() {
                show(target);
                image.setTranslationX(delta * w);
                image.animate().translationX(0).alpha(1f).setDuration(160).start();
            }
        }).start();
    }

    private void show(int i) {
        index = i;
        final int gen = ++generation;
        final MediaItem m = items.get(i);
        int basis = prefs.basis();
        long t = m.time(basis);
        topTitle.setText(Ui.dayLabel(t));
        topSub.setText(Ui.time(t)
                + " · " + (basis == SortPrefs.BASIS_TAKEN ? "촬영 날짜" : "기기 저장 날짜"));
        position.setText((i + 1) + " / " + items.size() + "   " + m.bucketName);
        info.setText(infoText(m));

        // 캐시된 썸네일을 먼저 보여주고, 원본을 화면 크기로 디코딩해 교체한다
        ThumbnailLoader.get(this).load(m, image, 256);
        final int maxSide = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bm = ThumbnailLoader.decodeForScreen(getContentResolver(), m.uri, m.orientation, maxSide);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || isFinishing() || bm == null) return;
                        ThumbnailLoader.get(ViewerActivity.this).cancel(image);
                        image.setImageBitmap(bm);
                    }
                });
            }
        });
    }

    private static String infoText(MediaItem m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.name).append('\n');
        sb.append("촬영 날짜 (메타데이터): ").append(m.dateTakenMs > 0 ? Ui.dateTime(m.dateTakenMs) : "정보 없음").append('\n');
        sb.append("기기에 저장된 날짜: ").append(Ui.dateTime(m.dateAddedMs)).append('\n');
        sb.append("폴더: ").append(m.bucketName).append('\n');
        if (m.width > 0 && m.height > 0) {
            sb.append("해상도: ").append(m.width).append(" × ").append(m.height).append("   ");
        }
        sb.append("크기: ").append(Ui.fileSize(m.size));
        return sb.toString();
    }
}
