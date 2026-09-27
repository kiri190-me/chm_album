package com.chm.album;

import android.content.Context;
import android.content.SharedPreferences;

/** 정렬 기준 / 순서 / 열 개수 설정. */
public class SortPrefs {
    /** 사진 메타데이터(EXIF)상의 촬영 날짜 기준 */
    public static final int BASIS_TAKEN = 0;
    /** 기기에 추가(저장)된 날짜 기준 */
    public static final int BASIS_ADDED = 1;

    private static final String FILE = "gallery_prefs";
    private static final String KEY_BASIS = "sort_basis";
    private static final String KEY_DESC = "sort_desc";
    private static final String KEY_COLUMNS = "grid_columns";

    private final SharedPreferences sp;

    public SortPrefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int basis() {
        return sp.getInt(KEY_BASIS, BASIS_TAKEN);
    }

    public boolean descending() {
        return sp.getBoolean(KEY_DESC, true);
    }

    public int columns() {
        return sp.getInt(KEY_COLUMNS, 4);
    }

    public void set(int basis, boolean desc) {
        sp.edit().putInt(KEY_BASIS, basis).putBoolean(KEY_DESC, desc).apply();
    }

    public void setColumns(int columns) {
        sp.edit().putInt(KEY_COLUMNS, columns).apply();
    }

    public String label() {
        return (basis() == BASIS_TAKEN ? "촬영 날짜(메타데이터)" : "기기에 저장된 날짜")
                + " · " + (descending() ? "최신순" : "오래된순");
    }
}
