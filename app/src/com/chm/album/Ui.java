package com.chm.album;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 화면 구성 공통 유틸. */
public final class Ui {
    public static final int BG = 0xFFF7F7F7;
    public static final int TEXT = 0xFF111111;
    public static final int SUBTEXT = 0xFF7A7A7A;
    public static final int ACCENT = 0xFF3E91FF;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return tv;
    }

    /** 원형 리플이 있는 아이콘 버튼. */
    public static ImageView iconButton(Context c, int drawableRes, int tint) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(drawableRes);
        iv.setColorFilter(tint);
        int p = dp(c, 10);
        iv.setPadding(p, p, p, p);
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        iv.setBackgroundResource(tv.resourceId);
        iv.setClickable(true);
        return iv;
    }

    /** 둥근 테두리의 작은 텍스트 버튼 (예: "전체 선택"). */
    public static TextView pillButton(Context c, String label) {
        TextView tv = text(c, label, 14, TEXT, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(c, 18));
        bg.setColor(0x14000000);
        tv.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000), bg, null));
        tv.setClickable(true);
        return tv;
    }

    /** 아이콘 + 글자 버튼을 가로로 나란히 놓은 하단 막대. */
    public static LinearLayout actionBar(Context c, int fg, int bg, int[] icons, String[] labels,
                                         View.OnClickListener[] listeners) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(bg);
        bar.setPadding(0, dp(c, 4), 0, dp(c, 4));
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        for (int i = 0; i < labels.length; i++) {
            TextView b = text(c, labels[i], 12, fg, false);
            b.setGravity(Gravity.CENTER);
            android.graphics.drawable.Drawable d = c.getDrawable(icons[i]).mutate();
            d.setTint(fg);
            b.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null);
            b.setCompoundDrawablePadding(dp(c, 2));
            b.setBackgroundResource(tv.resourceId);
            b.setOnClickListener(listeners[i]);
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(c, 56), 1f));
        }
        return bar;
    }

    /** 버튼 아래에 뜨는 간단한 메뉴 (One UI 풍의 둥근 카드). */
    public static void showMenu(View anchor, String[] labels, final Runnable[] actions) {
        Context c = anchor.getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(c, 18));
        box.setBackground(bg);
        box.setElevation(dp(c, 8));
        box.setPadding(0, dp(c, 6), 0, dp(c, 6));
        final android.widget.PopupWindow pw = new android.widget.PopupWindow(box,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        for (int i = 0; i < labels.length; i++) {
            final Runnable action = actions[i];
            TextView row = text(c, labels[i], 16, TEXT, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinWidth(dp(c, 180));
            row.setPadding(dp(c, 20), 0, dp(c, 24), 0);
            row.setBackgroundResource(tv.resourceId);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pw.dismiss();
                    action.run();
                }
            });
            box.addView(row, new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48)));
        }
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(c, 8));
        pw.showAsDropDown(anchor, -dp(c, 140), 0);
    }

    /** 뷰의 화면(전체 창) 좌표 사각형. */
    public static android.graphics.Rect screenRect(View v) {
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        return new android.graphics.Rect(xy[0], xy[1], xy[0] + v.getWidth(), xy[1] + v.getHeight());
    }

    public static void lightNavigationBar(Activity a) {
        if (Build.VERSION.SDK_INT >= 26) {
            View d = a.getWindow().getDecorView();
            // View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR (API 26)
            d.setSystemUiVisibility(d.getSystemUiVisibility() | 0x00000010);
        }
    }

    /** 날짜 묶음 헤더: 오늘 / 어제 / 9월 27일 토요일 / 2024년 3월 2일 토요일 */
    public static String dayLabel(long ms) {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        if (sameDay(now, t)) return "오늘";
        Calendar y = Calendar.getInstance();
        y.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(y, t)) return "어제";
        String pattern = now.get(Calendar.YEAR) == t.get(Calendar.YEAR)
                ? "M월 d일 EEEE" : "yyyy년 M월 d일 EEEE";
        return new SimpleDateFormat(pattern, Locale.KOREAN).format(new Date(ms));
    }

    public static long dayKey(long ms) {
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(ms);
        return t.get(Calendar.YEAR) * 1000L + t.get(Calendar.DAY_OF_YEAR);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    public static String dateTime(long ms) {
        if (ms <= 0) return "정보 없음";
        return new SimpleDateFormat("yyyy년 M월 d일 (E) a h:mm", Locale.KOREAN).format(new Date(ms));
    }

    public static String time(long ms) {
        return new SimpleDateFormat("a h:mm", Locale.KOREAN).format(new Date(ms));
    }

    public static String fileSize(long bytes) {
        if (bytes <= 0) return "-";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    public static String count(int n) {
        return String.format(Locale.KOREAN, "%,d", n);
    }

    public interface SortListener {
        void onSortChanged();
    }

    /** 정렬 기준(메타데이터 촬영 날짜 / 기기 저장 날짜)과 순서를 고르는 대화상자. */
    public static void showSortDialog(final Activity a, final SortListener listener) {
        final SortPrefs prefs = new SortPrefs(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 24);
        box.setPadding(pad, dp(a, 8), pad, 0);

        box.addView(sectionTitle(a, "정렬 기준"));
        final RadioGroup basis = new RadioGroup(a);
        RadioButton taken = radio(a, 1, "촬영 날짜", "사진 메타데이터(EXIF)에 기록된 날짜");
        RadioButton added = radio(a, 2, "기기에 저장된 날짜", "이 기기에 파일이 만들어진(추가된) 날짜");
        basis.addView(taken);
        basis.addView(added);
        basis.check(prefs.basis() == SortPrefs.BASIS_TAKEN ? 1 : 2);
        box.addView(basis);

        TextView order = sectionTitle(a, "정렬 순서");
        order.setPadding(0, dp(a, 16), 0, 0);
        box.addView(order);
        final RadioGroup dir = new RadioGroup(a);
        dir.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton desc = radio(a, 3, "최신순", null);
        RadioButton asc = radio(a, 4, "오래된순", null);
        dir.addView(desc);
        dir.addView(asc);
        dir.check(prefs.descending() ? 3 : 4);
        box.addView(dir);

        new AlertDialog.Builder(a, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("정렬")
                .setView(box)
                .setNegativeButton("취소", null)
                .setPositiveButton("적용", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        prefs.set(basis.getCheckedRadioButtonId() == 1
                                        ? SortPrefs.BASIS_TAKEN : SortPrefs.BASIS_ADDED,
                                dir.getCheckedRadioButtonId() == 3);
                        listener.onSortChanged();
                    }
                })
                .show();
    }

    private static TextView sectionTitle(Context c, String s) {
        TextView tv = text(c, s, 13, ACCENT, true);
        tv.setPadding(0, 0, 0, dp(c, 4));
        return tv;
    }

    private static RadioButton radio(Context c, int id, String title, String desc) {
        RadioButton rb = new RadioButton(c);
        rb.setId(id);
        String s = desc == null ? title : title + "\n" + desc;
        android.text.SpannableString span = new android.text.SpannableString(s);
        if (desc != null) {
            int start = title.length() + 1;
            span.setSpan(new android.text.style.ForegroundColorSpan(SUBTEXT), start, s.length(), 0);
            span.setSpan(new android.text.style.RelativeSizeSpan(0.82f), start, s.length(), 0);
        }
        rb.setText(span);
        rb.setTextColor(TEXT);
        rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        rb.setGravity(Gravity.CENTER_VERTICAL);
        rb.setPadding(dp(c, 8), dp(c, 8), dp(c, 16), dp(c, 8));
        rb.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        return rb;
    }
}
