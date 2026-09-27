package com.chm.album;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 사진 편집: 자르기, 90° 회전, 좌우 반전, 기울기, 크기 조절. 결과는 사본으로 저장한다. */
public class PhotoEditorActivity extends Activity {

    private static final int REQ_WRITE = 11;
    /** 저장할 때 디코딩하는 긴 변의 최대 크기 */
    private static final int SAVE_MAX_SIDE = 4096;
    private static final int PREVIEW_MAX_SIDE = 2048;

    private static final String[] RATIO_LABELS = {"자유", "원본", "1:1", "4:3", "3:4", "16:9", "9:16"};
    private static final float[] RATIOS = {0f, -1f, 1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f};

    private static MediaItem sItem;

    public static void open(Context ctx, MediaItem item) {
        sItem = item;
        ctx.startActivity(new Intent(ctx, PhotoEditorActivity.class));
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaItem item;
    private CropView cropView;
    private TextView angleText;
    private SeekBar angleBar;
    private TextView sizeText;
    private TextView saveButton;
    private FrameLayout busy;
    private final List<TextView> ratioChips = new ArrayList<>();

    /** 회전 보정한 원본 크기 */
    private int fullW;
    private int fullH;
    /** 저장 시 실제로 디코딩될 크기 */
    private int saveW;
    private int saveH;
    /** 저장 배율 (1 = 자른 영역 그대로) */
    private float outScale = 1f;
    private boolean saving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        item = sItem;
        if (item == null || item.isVideo) {
            finish();
            return;
        }
        setContentView(buildLayout());
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---------------------------------------------------------------- 화면

    private View buildLayout() {
        FrameLayout frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        frame.addView(root);

        // 상단: 취소 / 제목 / 저장
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
        TextView title = Ui.text(this, "사진 편집", 17, 0xFFFFFFFF, true);
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        saveButton = Ui.text(this, "저장", 16, Ui.ACCENT, true);
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        saveButton.setClickable(true);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trySave();
            }
        });
        top.addView(saveButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44)));
        root.addView(top);

        cropView = new CropView(this);
        cropView.setListener(new CropView.Listener() {
            @Override
            public void onCropChanged() {
                updateSizeText();
            }
        });
        root.addView(cropView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 기울기
        LinearLayout tilt = new LinearLayout(this);
        tilt.setOrientation(LinearLayout.HORIZONTAL);
        tilt.setGravity(Gravity.CENTER_VERTICAL);
        tilt.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 12), 0);
        tilt.addView(Ui.text(this, "기울기", 13, 0xCCFFFFFF, false));
        angleBar = new SeekBar(this);
        angleBar.setMax(180); // 0.5° 단위, 가운데(90)가 0°
        angleBar.setProgress(90);
        angleBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        angleBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        angleBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        angleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) setAngle((p - 90) / 2f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        tilt.addView(angleBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        angleText = Ui.text(this, "0°", 13, 0xFFFFFFFF, true);
        angleText.setGravity(Gravity.CENTER);
        angleText.setFontFeatureSettings("tnum");
        angleText.setContentDescription("기울기 0도로 되돌리기");
        angleText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAngle(0);
            }
        });
        tilt.addView(angleText, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 36)));
        root.addView(tilt);

        sizeText = Ui.text(this, "", 12, 0x99FFFFFF, false);
        sizeText.setGravity(Gravity.CENTER);
        sizeText.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        root.addView(sizeText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 비율 칩
        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        for (int i = 0; i < RATIO_LABELS.length; i++) {
            final int idx = i;
            TextView chip = Ui.text(this, RATIO_LABELS[i], 13, 0xFFFFFFFF, false);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectRatio(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 32));
            lp.rightMargin = Ui.dp(this, 8);
            chips.addView(chip, lp);
            ratioChips.add(chip);
        }
        chipsScroll.addView(chips);
        root.addView(chipsScroll);
        styleChips(0);

        // 도구
        root.addView(Ui.actionBar(this, 0xFFFFFFFF, 0xFF000000,
                new int[]{R.drawable.ic_rotate, R.drawable.ic_flip, R.drawable.ic_resize, R.drawable.ic_reset},
                new String[]{"90° 회전", "좌우 반전", "크기", "초기화"},
                new View.OnClickListener[]{
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                cropView.rotate90();
                            }
                        },
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                cropView.toggleFlip();
                            }
                        },
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showSizeDialog();
                            }
                        },
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                cropView.reset();
                                angleBar.setProgress(90);
                                angleText.setText("0°");
                                outScale = 1f;
                                styleChips(0);
                                updateSizeText();
                            }
                        }}));

        busy = new FrameLayout(this);
        busy.setBackgroundColor(0x88000000);
        busy.setClickable(true);
        busy.addView(new ProgressBar(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        busy.setVisibility(View.VISIBLE);
        frame.addView(busy, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private void setAngle(float a) {
        cropView.setAngle(a);
        angleBar.setProgress(Math.round(a * 2) + 90);
        angleText.setText(a == Math.round(a)
                ? String.format(Locale.US, "%d°", Math.round(a))
                : String.format(Locale.US, "%.1f°", a));
    }

    private void selectRatio(int idx) {
        cropView.setRatio(RATIOS[idx]);
        styleChips(idx);
    }

    private void styleChips(int selected) {
        for (int i = 0; i < ratioChips.size(); i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 16));
            boolean on = i == selected;
            bg.setColor(on ? 0xFFFFFFFF : 0x00000000);
            bg.setStroke(Ui.dp(this, 1), on ? 0xFFFFFFFF : 0x66FFFFFF);
            ratioChips.get(i).setBackground(bg);
            ratioChips.get(i).setTextColor(on ? 0xFF000000 : 0xFFFFFFFF);
        }
    }

    // ---------------------------------------------------------------- 크기

    /** 현재 자르기 영역을 저장 해상도로 계산한 크기 (배율 적용 전). */
    private int[] cropPixels() {
        RectF c = cropView.getCrop();
        boolean swap = cropView.getRot90() % 2 == 1;
        float fw = swap ? saveH : saveW;
        float fh = swap ? saveW : saveH;
        return new int[]{Math.max(1, Math.round(c.width() * fw)), Math.max(1, Math.round(c.height() * fh))};
    }

    private void updateSizeText() {
        if (saveW == 0) return;
        int[] px = cropPixels();
        int w = Math.max(1, Math.round(px[0] * outScale));
        int h = Math.max(1, Math.round(px[1] * outScale));
        String pct = outScale >= 0.999f ? "" : String.format(Locale.US, " (%d%%)", Math.round(outScale * 100));
        sizeText.setText("저장 크기 " + w + " × " + h + pct);
    }

    private void showSizeDialog() {
        if (saveW == 0) return;
        final int[] px = cropPixels();
        final List<Float> scales = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        float[] pcts = {1f, 0.75f, 0.5f, 0.25f};
        for (float p : pcts) {
            scales.add(p);
            labels.add((p == 1f ? "원래 크기" : Math.round(p * 100) + "%") + "  ·  "
                    + Math.round(px[0] * p) + " × " + Math.round(px[1] * p));
        }
        int longSide = Math.max(px[0], px[1]);
        for (int target : new int[]{2048, 1280}) {
            if (target < longSide) {
                float p = target / (float) longSide;
                scales.add(p);
                labels.add("긴 변 " + target + "px  ·  " + Math.round(px[0] * p) + " × " + Math.round(px[1] * p));
            }
        }
        int checked = 0;
        for (int i = 0; i < scales.size(); i++) {
            if (Math.abs(scales.get(i) - outScale) < 0.001f) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("저장 크기")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        outScale = scales.get(which);
                        updateSizeText();
                        d.dismiss();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------------------------------------------------------------- 불러오기 / 저장

    private void load() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                int[] raw = ThumbnailLoader.imageBounds(getContentResolver(), item.uri);
                final Bitmap preview = raw == null ? null
                        : ThumbnailLoader.decodeForScreen(getContentResolver(), item.uri, item.orientation, PREVIEW_MAX_SIDE);
                if (raw != null) {
                    boolean swap = item.orientation % 180 != 0;
                    fullW = swap ? raw[1] : raw[0];
                    fullH = swap ? raw[0] : raw[1];
                    int sample = ThumbnailLoader.sampleFor(Math.max(fullW, fullH), SAVE_MAX_SIDE);
                    saveW = fullW / sample;
                    saveH = fullH / sample;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        busy.setVisibility(View.GONE);
                        if (preview == null) {
                            Toast.makeText(PhotoEditorActivity.this, "사진을 열 수 없습니다", Toast.LENGTH_SHORT).show();
                            finish();
                            return;
                        }
                        cropView.setBitmap(preview);
                        updateSizeText();
                    }
                });
            }
        });
    }

    private void trySave() {
        if (saving || saveW == 0) return;
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
        busy.setVisibility(View.VISIBLE);
        final int rot90 = cropView.getRot90();
        final boolean flip = cropView.isFlipped();
        final float angle = cropView.getAngle();
        final RectF crop = cropView.getCrop();
        final float scale = outScale;
        io.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    render(rot90, flip, angle, crop, scale);
                } catch (Throwable t) {
                    error = t instanceof OutOfMemoryError ? "메모리가 부족합니다. 크기를 줄여 보세요." : t.getMessage();
                }
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        saving = false;
                        busy.setVisibility(View.GONE);
                        if (err != null) {
                            Toast.makeText(PhotoEditorActivity.this, "저장하지 못했습니다: " + err, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(PhotoEditorActivity.this, "사본으로 저장했습니다", Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    }
                });
            }
        });
    }

    private void render(int rot90, boolean flip, float angle, RectF crop, float scale) throws Exception {
        Bitmap src = ThumbnailLoader.decodeForScreen(getContentResolver(), item.uri, item.orientation, SAVE_MAX_SIDE);
        if (src == null) throw new Exception("원본을 읽을 수 없습니다");
        boolean swap = rot90 % 2 == 1;
        float fw = swap ? src.getHeight() : src.getWidth();
        float fh = swap ? src.getWidth() : src.getHeight();
        int outW = Math.max(1, Math.round(crop.width() * fw * scale));
        int outH = Math.max(1, Math.round(crop.height() * fh * scale));

        Matrix m = CropView.frameMatrix(src.getWidth(), src.getHeight(), rot90, flip, angle);
        m.postTranslate(-crop.left * fw, -crop.top * fh);
        m.postScale(scale, scale);

        boolean png = "image/png".equals(item.mime);
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (!png) canvas.drawColor(0xFF000000);
        canvas.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        src.recycle();

        String ext = png ? "png" : "jpg";
        File tmp = new File(getCacheDir(), "edit_tmp." + ext);
        OutputStream os = new FileOutputStream(tmp);
        try {
            out.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 95, os);
        } finally {
            os.close();
            out.recycle();
        }
        if (!png) writeDate(tmp, item.time(SortPrefs.BASIS_TAKEN));
        try {
            MediaSaver.save(this, tmp, item, MediaSaver.derivedName(item, "edit", ext), png ? "image/png" : "image/jpeg");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 편집본이 '촬영 날짜' 정렬에서 원본 옆에 오도록 원래 촬영 날짜를 EXIF 에 기록한다. */
    private static void writeDate(File jpeg, long ms) {
        if (ms <= 0) return;
        try {
            String v = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date(ms));
            ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
            exif.setAttribute("DateTimeOriginal", v);
            exif.setAttribute(ExifInterface.TAG_DATETIME, v);
            exif.saveAttributes();
        } catch (Throwable ignored) {
            // 날짜를 못 쓰면 저장한 날짜로 정렬된다
        }
    }
}
