package com.chm.album;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 전체 화면 보기. 좌우로 밀어 이전/다음, 탭으로 메뉴 표시/숨김.
 * 아래로 끌면 사진이 손가락을 따라 작아지고, 놓으면 목록의 제자리 칸으로 줄어들며 닫힌다.
 * 사진은 두 손가락으로 벌리거나 두 번 탭해 확대하고, 확대한 채로 끌어 이동한다.
 * 동영상은 가운데 재생 버튼으로 재생한다.
 */
public class ViewerActivity extends Activity implements TrashController.Callback {

    private static final String STATE_INDEX = "index";
    private static final int MAX_ZOOM_SIDE = 4096;
    private static List<MediaItem> sItems;
    private static int sStart;
    /** 눌린 칸의 화면 위치 (여는 애니메이션 시작점) */
    private static Rect sStartRect;
    /** 열어 준 목록. 닫을 때 사진이 돌아갈 칸을 찾는다. */
    private static WeakReference<PhotoGridAdapter> sGrid;

    public static void open(Activity from, List<MediaItem> items, int index, View cell, PhotoGridAdapter grid) {
        sItems = new ArrayList<>(items);
        sStart = index;
        sStartRect = cell != null ? Ui.screenRect(cell) : null;
        sGrid = grid != null ? new WeakReference<>(grid) : null;
        from.startActivity(new Intent(from, ViewerActivity.class));
        from.overridePendingTransition(0, 0);
    }

    private static final long TRANSITION_MS = 280;
    /** Material 의 "emphasized decelerate" 곡선: 빠르게 출발해 부드럽게 멈춘다 */
    private static final TimeInterpolator EASE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private final ColorDrawable backdrop = new ColorDrawable(0xFF000000);
    private boolean closing;
    private boolean entering;
    private List<MediaItem> items;
    private int index;
    private int generation;
    private boolean barsVisible = true;
    private boolean infoVisible;
    private boolean videoActive;

    private ZoomImageView image;
    private VideoView video;
    private ImageView bigPlay;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private LinearLayout videoControls;
    private ImageView playPause;
    private SeekBar seek;
    private TextView videoTime;
    private TextView topTitle;
    private TextView topSub;
    private TextView position;
    private TextView info;
    private SortPrefs prefs;
    private TrashController trash;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (!videoActive) return;
            int pos = video.getCurrentPosition();
            int dur = Math.max(1, video.getDuration());
            seek.setMax(dur);
            seek.setProgress(pos);
            videoTime.setText(MediaCellView.formatDuration(pos) + " / " + MediaCellView.formatDuration(dur));
            main.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        items = sItems;
        if (items == null || items.isEmpty()) {
            finish();
            return;
        }
        prefs = new SortPrefs(this);
        trash = new TrashController(this, this);
        index = savedInstanceState != null ? savedInstanceState.getInt(STATE_INDEX, sStart) : sStart;
        index = Math.max(0, Math.min(items.size() - 1, index));
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(BASE_UI_FLAGS);
        setContentView(buildLayout());
        show(index);
        if (savedInstanceState == null) playEnterAnimation();
    }

    /** 상태 표시줄 뒤까지 화면을 쓰되(닫을 때 뒤 목록이 보이도록) 레이아웃은 고정 */
    private static final int BASE_UI_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;

    @Override
    public void onBackPressed() {
        dismissAnimated();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private PhotoGridAdapter grid() {
        return sGrid == null ? null : sGrid.get();
    }

    // ---------------------------------------------------------------- 열기 / 닫기 애니메이션

    /**
     * 사진의 보이는 부분이 화면의 target(창 좌표) 칸을 가득 채우도록(가운데 잘림) 하는
     * 뷰 배율/이동/잘라낼 영역을 구한다. {scale, tx, ty, clipL, clipT, clipR, clipB}
     */
    private float[] frameFor(Rect target) {
        RectF shown = image.displayedRect();
        if (!shown.intersect(0, 0, image.getWidth(), image.getHeight()) || shown.isEmpty()) return null;
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        float tl = target.left - origin[0];
        float tt = target.top - origin[1];
        float tw = target.width();
        float th = target.height();
        float s = Math.max(tw / shown.width(), th / shown.height());
        float cx = image.getWidth() / 2f;
        float cy = image.getHeight() / 2f;
        float tx = tl + tw / 2f - cx - s * (shown.centerX() - cx);
        float ty = tt + th / 2f - cy - s * (shown.centerY() - cy);
        float hw = tw / s / 2f;
        float hh = th / s / 2f;
        return new float[]{s, tx, ty, shown.centerX() - hw, shown.centerY() - hh, shown.centerX() + hw, shown.centerY() + hh};
    }

    private float[] currentFrame() {
        return new float[]{image.getScaleX(), image.getTranslationX(), image.getTranslationY(),
                0, 0, image.getWidth(), image.getHeight()};
    }

    private void applyFrame(float[] a, float[] b, float t) {
        float s = a[0] + (b[0] - a[0]) * t;
        image.setScaleX(s);
        image.setScaleY(s);
        image.setTranslationX(a[1] + (b[1] - a[1]) * t);
        image.setTranslationY(a[2] + (b[2] - a[2]) * t);
        image.setClipBounds(new Rect(
                Math.round(a[3] + (b[3] - a[3]) * t), Math.round(a[4] + (b[4] - a[4]) * t),
                Math.round(a[5] + (b[5] - a[5]) * t), Math.round(a[6] + (b[6] - a[6]) * t)));
    }

    private void setChromeAlpha(float a) {
        topBar.setAlpha(barsVisible ? a : 0f);
        bottomBar.setAlpha(barsVisible ? a : 0f);
        bigPlay.setAlpha(a);
    }

    /** 목록의 칸에서 커지며 열린다. */
    private void playEnterAnimation() {
        final Rect start = sStartRect;
        sStartRect = null;
        if (start == null || image.getDrawable() == null) {
            backdrop.setAlpha(255);
            return;
        }
        entering = true;
        backdrop.setAlpha(0);
        setChromeAlpha(0f);
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                final float[] from = frameFor(start);
                if (from == null) {
                    entering = false;
                    backdrop.setAlpha(255);
                    setChromeAlpha(1f);
                    return true;
                }
                final float[] to = {1f, 0f, 0f, from[3], from[4], from[5], from[6]};
                // 끝 상태의 잘라낼 영역은 뷰 전체
                to[3] = 0;
                to[4] = 0;
                to[5] = image.getWidth();
                to[6] = image.getHeight();
                applyFrame(from, to, 0f);
                ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(TRANSITION_MS);
                va.setInterpolator(EASE);
                va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        float t = (float) a.getAnimatedValue();
                        applyFrame(from, to, t);
                        backdrop.setAlpha(Math.round(255 * t));
                        setChromeAlpha(t);
                    }
                });
                va.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        entering = false;
                        image.setClipBounds(null);
                    }
                });
                va.start();
                return true;
            }
        });
    }

    /** 끄는 동안: 손가락을 따라 사진이 움직이며 작아지고, 배경이 옅어져 뒤의 목록이 비친다. */
    private void onDismissDrag(float dx, float dy) {
        if (closing || entering) return;
        float h = Math.max(1, root.getHeight());
        float p = Math.max(0f, Math.min(1f, dy / h));
        float s = 1f - 0.45f * p;
        image.setScaleX(s);
        image.setScaleY(s);
        image.setTranslationX(dx);
        image.setTranslationY(dy);
        backdrop.setAlpha(Math.round(255 * Math.max(0f, 1f - p * 2.2f)));
        setChromeAlpha(Math.max(0f, 1f - p * 5f));
    }

    private void onDismissRelease(float dx, float dy, float vy) {
        if (closing || entering) return;
        float density = getResources().getDisplayMetrics().density;
        if (dy > 90 * density || (vy > 800 * density && dy > 0)) {
            dismissAnimated();
            return;
        }
        // 제자리로 부드럽게 돌아온다
        final float[] from = currentFrame();
        final float[] to = {1f, 0f, 0f, 0, 0, image.getWidth(), image.getHeight()};
        final int alpha0 = backdrop.getAlpha();
        final float chrome0 = topBar.getAlpha();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(220);
        va.setInterpolator(EASE);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                applyFrame(from, to, t);
                backdrop.setAlpha(Math.round(alpha0 + (255 - alpha0) * t));
                setChromeAlpha(chrome0 + (1f - chrome0) * t);
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                image.setClipBounds(null);
            }
        });
        va.start();
    }

    /** 지금 보던 사진이 목록의 제자리 칸으로 줄어들며 닫힌다. 칸을 못 찾으면 작아지며 사라진다. */
    private void dismissAnimated() {
        if (closing) return;
        closing = true;
        stopVideo();
        image.setVisibility(View.VISIBLE);
        PhotoGridAdapter g = grid();
        Rect target = g != null && image.getDrawable() != null ? g.cellRectOnScreen(items.get(index).key()) : null;
        final float[] from = currentFrame();
        final float[] to = target != null ? frameFor(target) : null;
        final int alpha0 = backdrop.getAlpha();
        final float chrome0 = topBar.getAlpha();
        final float img0 = image.getAlpha();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(TRANSITION_MS);
        va.setInterpolator(EASE);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                if (to != null) {
                    applyFrame(from, to, t);
                } else {
                    float s = from[0] * (1f - 0.25f * t);
                    image.setScaleX(s);
                    image.setScaleY(s);
                    image.setAlpha(img0 * (1f - t));
                }
                backdrop.setAlpha(Math.round(alpha0 * (1f - t)));
                setChromeAlpha(chrome0 * (1f - t));
            }
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                finish();
            }
        });
        va.start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_INDEX, index);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoActive && video.isPlaying()) {
            video.pause();
            playPause.setImageResource(R.drawable.ic_play);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private View buildLayout() {
        root = new FrameLayout(this);
        root.setBackground(backdrop);

        image = new ZoomImageView(this);
        image.setListener(new ZoomImageView.Listener() {
            @Override
            public void onSingleTap() {
                setBarsVisible(!barsVisible);
            }

            @Override
            public void onSwipe(int direction) {
                move(direction);
            }

            @Override
            public void onDismissDrag(float dx, float dy) {
                ViewerActivity.this.onDismissDrag(dx, dy);
            }

            @Override
            public void onDismissRelease(float dx, float dy, float velocityY) {
                ViewerActivity.this.onDismissRelease(dx, dy, velocityY);
            }
        });
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        video = new VideoView(this);
        video.setVisibility(View.GONE);
        root.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                image.setVisibility(View.INVISIBLE);
                main.removeCallbacks(progressTick);
                main.post(progressTick);
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                stopVideo();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(ViewerActivity.this, "이 동영상을 재생할 수 없습니다", Toast.LENGTH_SHORT).show();
                stopVideo();
                return true;
            }
        });

        bigPlay = new ImageView(this);
        bigPlay.setImageResource(R.drawable.ic_play);
        bigPlay.setColorFilter(0xFFFFFFFF);
        int bp = Ui.dp(this, 18);
        bigPlay.setPadding(bp, bp, bp, bp);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x66000000);
        circle.setStroke(Ui.dp(this, 1.5f), 0xCCFFFFFF);
        bigPlay.setBackground(circle);
        bigPlay.setContentDescription("동영상 재생");
        bigPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVideo();
            }
        });
        root.addView(bigPlay, new FrameLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72), Gravity.CENTER));

        // 상단 바
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 4), statusBarHeight() + Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 20));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xAA000000, 0x00000000}));
        ImageView back = Ui.iconButton(this, R.drawable.ic_back, 0xFFFFFFFF);
        back.setContentDescription("뒤로");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissAnimated();
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
        bottomBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 12));
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
        ilp.rightMargin = Ui.dp(this, 4);
        bottomBar.addView(info, ilp);

        // 동영상 재생 조작
        videoControls = new LinearLayout(this);
        videoControls.setOrientation(LinearLayout.HORIZONTAL);
        videoControls.setGravity(Gravity.CENTER_VERTICAL);
        playPause = Ui.iconButton(this, R.drawable.ic_pause, 0xFFFFFFFF);
        playPause.setContentDescription("재생/일시정지");
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (video.isPlaying()) {
                    video.pause();
                    playPause.setImageResource(R.drawable.ic_play);
                } else {
                    video.start();
                    playPause.setImageResource(R.drawable.ic_pause);
                }
            }
        });
        videoControls.addView(playPause, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        seek = new SeekBar(this);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser && videoActive) video.seekTo(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        videoControls.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        videoTime = Ui.text(this, "", 12, 0xCCFFFFFF, false);
        videoTime.setFontFeatureSettings("tnum");
        videoControls.addView(videoTime);
        videoControls.setVisibility(View.GONE);
        bottomBar.addView(videoControls);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        position = Ui.text(this, "", 13, 0xCCFFFFFF, false);
        position.setSingleLine(true);
        position.setEllipsize(android.text.TextUtils.TruncateAt.END);
        actions.addView(position, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(action(R.drawable.ic_edit, "편집", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edit();
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        actions.addView(action(R.drawable.ic_share, "공유", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ShareHelper.share(ViewerActivity.this, Collections.singletonList(items.get(index)));
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        actions.addView(action(R.drawable.ic_delete, "삭제", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopVideo();
                trash.moveToTrash(Collections.singletonList(items.get(index)));
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        actions.addView(action(R.drawable.ic_info, "상세 정보", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                infoVisible = !infoVisible;
                info.setVisibility(infoVisible ? View.VISIBLE : View.GONE);
            }
        }), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        bottomBar.addView(actions);
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // 재생 중인 동영상 위의 제스처 (사진은 ZoomImageView 가 직접 처리)
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
                    dismissAnimated(); // 재생 중인 동영상을 아래로 밀어서 닫기
                    return true;
                }
                return false;
            }
        });
        View.OnTouchListener touch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestures.onTouchEvent(event);
            }
        };
        video.setOnTouchListener(touch);
        return root;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!trash.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        trash.onRequestPermissionsResult(requestCode, grantResults);
    }

    /** 휴지통으로 옮긴 사진은 목록에서 빼고 다음 사진을 보여준다. */
    @Override
    public void onTrashDone(int op, List<MediaItem> affected) {
        if (affected.isEmpty()) return;
        java.util.Set<Long> gone = new java.util.HashSet<>();
        for (MediaItem m : affected) gone.add(m.key());
        List<MediaItem> left = new ArrayList<>();
        for (MediaItem m : items) {
            if (!gone.contains(m.key())) left.add(m);
        }
        items = left;
        if (items.isEmpty()) {
            finish();
            return;
        }
        show(Math.min(index, items.size() - 1));
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : Ui.dp(this, 24);
    }

    private ImageView action(int icon, String label, View.OnClickListener l) {
        ImageView b = Ui.iconButton(this, icon, 0xFFFFFFFF);
        b.setContentDescription(label);
        b.setOnClickListener(l);
        return b;
    }

    private void edit() {
        MediaItem m = items.get(index);
        stopVideo();
        if (m.isVideo) VideoTrimActivity.open(this, m);
        else PhotoEditorActivity.open(this, m);
    }

    private void startVideo() {
        MediaItem m = items.get(index);
        if (!m.isVideo) return;
        videoActive = true;
        bigPlay.setVisibility(View.GONE);
        videoControls.setVisibility(View.VISIBLE);
        playPause.setImageResource(R.drawable.ic_pause);
        video.setVisibility(View.VISIBLE);
        video.setVideoURI(m.uri);
        video.start();
    }

    private void stopVideo() {
        main.removeCallbacks(progressTick);
        if (videoActive) {
            video.stopPlayback();
            video.setVisibility(View.GONE);
        }
        videoActive = false;
        image.setVisibility(View.VISIBLE);
        videoControls.setVisibility(View.GONE);
        MediaItem m = items.get(index);
        bigPlay.setVisibility(m.isVideo ? View.VISIBLE : View.GONE);
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
        decor.setSystemUiVisibility(visible ? BASE_UI_FLAGS
                : BASE_UI_FLAGS | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void move(final int delta) {
        final int target = index + delta;
        final View moving = videoActive ? video : image;
        if (target < 0 || target >= items.size()) {
            // 끝에서는 살짝 튕기는 효과
            moving.animate().translationX(-delta * Ui.dp(this, 24)).setDuration(90).withEndAction(new Runnable() {
                @Override
                public void run() {
                    moving.animate().translationX(0).setDuration(120).start();
                }
            }).start();
            return;
        }
        stopVideo();
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
        topSub.setText(Ui.time(t) + " · " + (basis == SortPrefs.BASIS_TAKEN ? "촬영 날짜" : "기기 저장 날짜"));
        position.setText((i + 1) + " / " + items.size() + "   " + m.bucketName);
        info.setText(infoText(m));
        bigPlay.setVisibility(m.isVideo ? View.VISIBLE : View.GONE);
        image.resetZoom();
        image.setZoomEnabled(!m.isVideo);

        // 목록에 보이던 썸네일을 바로 보여주고, 원본을 화면 크기로 디코딩해 교체한다
        ThumbnailLoader loader = ThumbnailLoader.get(this);
        Bitmap cached = loader.getCached(m);
        if (cached != null) {
            loader.cancel(image);
            image.setImageBitmap(cached);
        } else {
            loader.load(m, image, 256);
        }
        // 뒤의 목록도 이 사진이 보이는 위치로 옮겨 둔다 (닫을 때 그 칸으로 돌아간다)
        PhotoGridAdapter g = grid();
        if (g != null) g.reveal(m.key());
        final int screenSide = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        // 확대해도 선명하도록 화면보다 크게 (최대 긴 변 4096px) 디코딩한다
        final int zoomSide = Math.min(MAX_ZOOM_SIDE, screenSide * 2);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bm = m.isVideo
                        ? ThumbnailLoader.videoFrame(ViewerActivity.this, m.uri, 0, screenSide)
                        : ThumbnailLoader.decodeWithin(getContentResolver(), m.uri, m.orientation, zoomSide);
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
        if (m.isVideo) sb.append("길이: ").append(MediaCellView.formatDuration(m.durationMs)).append("   ");
        if (m.width > 0 && m.height > 0) {
            sb.append("해상도: ").append(m.width).append(" × ").append(m.height).append("   ");
        }
        sb.append("크기: ").append(Ui.fileSize(m.size));
        return sb.toString();
    }
}
