package com.chm.album;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 전체 화면 보기. 좌우로 밀어 이전/다음, 아래로 밀어 닫기, 탭으로 메뉴 표시/숨김.
 * 동영상은 가운데 재생 버튼으로 재생한다.
 */
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
    private boolean videoActive;

    private ImageView image;
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
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
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
        View.OnTouchListener touch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestures.onTouchEvent(event);
            }
        };
        image.setOnTouchListener(touch);
        video.setOnTouchListener(touch);
        return root;
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
        decor.setSystemUiVisibility(visible ? 0
                : View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
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

        // 캐시된 썸네일을 먼저 보여주고, 원본을 화면 크기로 디코딩해 교체한다
        ThumbnailLoader.get(this).load(m, image, 256);
        final int maxSide = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bm = m.isVideo
                        ? ThumbnailLoader.videoFrame(ViewerActivity.this, m.uri, 0, maxSide)
                        : ThumbnailLoader.decodeForScreen(getContentResolver(), m.uri, m.orientation, maxSide);
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
