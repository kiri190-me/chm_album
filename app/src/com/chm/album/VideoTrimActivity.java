package com.chm.album;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 동영상 자르기: 시작/끝 손잡이로 구간을 고르고, 잘라낸 부분을 사본으로 저장한다. */
public class VideoTrimActivity extends Activity {

    private static final int REQ_WRITE = 12;
    private static final int FRAME_COUNT = 10;

    private static MediaItem sItem;

    public static void open(Context ctx, MediaItem item) {
        sItem = item;
        ctx.startActivity(new Intent(ctx, VideoTrimActivity.class));
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaItem item;
    private VideoView video;
    private ImageView bigPlay;
    private ImageView playButton;
    private RangeTrimView trim;
    private TextView rangeText;
    private FrameLayout busy;
    private TextView busyText;
    private boolean prepared;
    private boolean saving;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!prepared) return;
            int pos = video.getCurrentPosition();
            trim.setPosition(pos);
            if (video.isPlaying()) {
                if (pos >= trim.getEndMs()) {
                    pause();
                    video.seekTo((int) trim.getStartMs());
                    trim.setPosition(trim.getStartMs());
                    return;
                }
                main.postDelayed(this, 40);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        item = sItem;
        if (item == null || !item.isVideo) {
            finish();
            return;
        }
        setContentView(buildLayout());
        trim.setDuration(Math.max(1000, item.durationMs));
        updateRangeText();
        video.setVideoURI(item.uri);
        loadFrames();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (prepared) pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private View buildLayout() {
        FrameLayout frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        frame.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 4));
        ImageView cancel = Ui.iconButton(this, R.drawable.ic_close, 0xFFFFFFFF);
        cancel.setContentDescription("취소");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(cancel, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        TextView title = Ui.text(this, "동영상 자르기", 17, 0xFFFFFFFF, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = Ui.text(this, "저장", 16, Ui.ACCENT, true);
        save.setGravity(Gravity.CENTER);
        save.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        save.setClickable(true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trySave();
            }
        });
        top.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44)));
        root.addView(top);

        FrameLayout stage = new FrameLayout(this);
        video = new VideoView(this);
        stage.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
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
        bigPlay.setContentDescription("구간 재생");
        bigPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play();
            }
        });
        stage.addView(bigPlay, new FrameLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72), Gravity.CENTER));
        stage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (video.isPlaying()) pause();
                else play();
            }
        });
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                prepared = true;
                int d = video.getDuration();
                if (d > 0 && Math.abs(d - trim.getEndMs()) > 50 && trim.getStartMs() == 0) {
                    trim.setDuration(d);
                    updateRangeText();
                }
                video.seekTo(1); // 첫 화면을 보여준다
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                pause();
                video.seekTo((int) trim.getStartMs());
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(VideoTrimActivity.this, "미리보기를 재생할 수 없습니다", Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        rangeText = Ui.text(this, "", 13, 0xFFFFFFFF, false);
        rangeText.setGravity(Gravity.CENTER);
        rangeText.setFontFeatureSettings("tnum");
        rangeText.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        root.addView(rangeText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        trim = new RangeTrimView(this);
        trim.setListener(new RangeTrimView.Listener() {
            @Override
            public void onRangeChanged(long startMs, long endMs, boolean movingStart) {
                pause();
                long at = movingStart ? startMs : endMs;
                if (prepared) video.seekTo((int) at);
                trim.setPosition(at);
                updateRangeText();
            }

            @Override
            public void onSeek(long positionMs) {
                if (prepared) video.seekTo((int) positionMs);
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64));
        tlp.leftMargin = Ui.dp(this, 12);
        tlp.rightMargin = Ui.dp(this, 12);
        root.addView(trim, tlp);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        playButton = Ui.iconButton(this, R.drawable.ic_play, 0xFFFFFFFF);
        playButton.setContentDescription("구간 재생/일시정지");
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (video.isPlaying()) pause();
                else play();
            }
        });
        controls.addView(playButton, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        root.addView(controls);

        TextView hint = Ui.text(this, "양쪽 노란 손잡이를 끌어 남길 구간을 고르세요", 12, 0x88FFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, Ui.dp(this, 16));
        root.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        busy = new FrameLayout(this);
        busy.setBackgroundColor(0xAA000000);
        busy.setClickable(true);
        LinearLayout busyBox = new LinearLayout(this);
        busyBox.setOrientation(LinearLayout.VERTICAL);
        busyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        busyBox.addView(new ProgressBar(this));
        busyText = Ui.text(this, "", 14, 0xFFFFFFFF, false);
        busyText.setPadding(0, Ui.dp(this, 12), 0, 0);
        busyBox.addView(busyText);
        busy.addView(busyBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        busy.setVisibility(View.GONE);
        frame.addView(busy, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private void play() {
        if (!prepared) return;
        int pos = video.getCurrentPosition();
        if (pos < trim.getStartMs() || pos >= trim.getEndMs() - 100) video.seekTo((int) trim.getStartMs());
        video.start();
        bigPlay.setVisibility(View.GONE);
        playButton.setImageResource(R.drawable.ic_pause);
        main.removeCallbacks(tick);
        main.post(tick);
    }

    private void pause() {
        if (video.isPlaying()) video.pause();
        bigPlay.setVisibility(View.VISIBLE);
        playButton.setImageResource(R.drawable.ic_play);
        main.removeCallbacks(tick);
    }

    private static String clock(long ms) {
        return String.format(Locale.US, "%d:%02d.%d", ms / 60000, (ms / 1000) % 60, (ms / 100) % 10);
    }

    private void updateRangeText() {
        long s = trim.getStartMs();
        long e = trim.getEndMs();
        rangeText.setText(clock(s) + "  –  " + clock(e) + "   ·   "
                + String.format(Locale.US, "%.1f초", (e - s) / 1000f));
    }

    /** 프레임 띠에 쓸 작은 장면들을 백그라운드에서 뽑는다. */
    private void loadFrames() {
        final long durationMs = Math.max(1000, item.durationMs);
        final int h = Ui.dp(this, 52);
        io.execute(new Runnable() {
            @Override
            public void run() {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                final Bitmap[] frames = new Bitmap[FRAME_COUNT];
                try {
                    r.setDataSource(VideoTrimActivity.this, item.uri);
                    for (int i = 0; i < FRAME_COUNT; i++) {
                        if (Thread.currentThread().isInterrupted()) return;
                        long us = (durationMs * 1000L) * (2 * i + 1) / (2 * FRAME_COUNT);
                        Bitmap b = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (b != null) {
                            int w = Math.max(1, Math.round(b.getWidth() * h / (float) b.getHeight()));
                            Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
                            if (small != b) b.recycle();
                            frames[i] = small;
                        }
                        final Bitmap[] snapshot = frames.clone();
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                trim.setFrames(snapshot);
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // 프레임을 못 뽑아도 자르기는 할 수 있다
                } finally {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------- 저장

    private void trySave() {
        if (saving) return;
        if (MediaSaver.needsWritePermission()
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        save();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_WRITE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) save();
        else Toast.makeText(this, "저장하려면 저장소 권한이 필요합니다", Toast.LENGTH_SHORT).show();
    }

    private void save() {
        saving = true;
        pause();
        busy.setVisibility(View.VISIBLE);
        busyText.setText("자르는 중… 0%");
        final long start = trim.getStartMs();
        final long end = trim.getEndMs();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                File tmp = new File(getCacheDir(), "trim_tmp.mp4");
                try {
                    VideoTrimmer.trim(VideoTrimActivity.this, item.uri, start, end, tmp, new VideoTrimmer.Progress() {
                        private int last = -1;

                        @Override
                        public void onProgress(float fraction) {
                            final int pct = Math.round(fraction * 100);
                            if (pct == last) return;
                            last = pct;
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    busyText.setText("자르는 중… " + pct + "%");
                                }
                            });
                        }
                    });
                    MediaSaver.save(VideoTrimActivity.this, tmp, item,
                            MediaSaver.derivedName(item, "trim", "mp4"), "video/mp4");
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        saving = false;
                        busy.setVisibility(View.GONE);
                        if (err != null) {
                            Toast.makeText(VideoTrimActivity.this, "저장하지 못했습니다: " + err, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(VideoTrimActivity.this, "잘라낸 동영상을 사본으로 저장했습니다", Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    }
                });
            }
        });
    }
}
