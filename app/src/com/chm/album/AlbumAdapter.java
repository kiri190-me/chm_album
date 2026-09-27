package com.chm.album;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 앨범(폴더) 탭 격자: 대표 사진 + 폴더 이름 + 사진 수. */
public class AlbumAdapter extends BaseAdapter {

    private final Context ctx;
    private final ThumbnailLoader loader;
    private List<MediaRepository.Album> albums = new ArrayList<>();

    public AlbumAdapter(Context ctx) {
        this.ctx = ctx;
        this.loader = ThumbnailLoader.get(ctx);
    }

    public void setAlbums(List<MediaRepository.Album> albums) {
        this.albums = albums;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return albums.size();
    }

    @Override
    public MediaRepository.Album getItem(int position) {
        return albums.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private static final class Holder {
        SquareImageView cover;
        TextView name;
        TextView count;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(0, 0, 0, Ui.dp(ctx, 12));

            h = new Holder();
            h.cover = new SquareImageView(ctx);
            h.cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFE6E6E6);
            bg.setCornerRadius(Ui.dp(ctx, 14));
            h.cover.setBackground(bg);
            h.cover.setClipToOutline(true);
            cell.addView(h.cover, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            h.name = Ui.text(ctx, "", 15, Ui.TEXT, true);
            h.name.setSingleLine(true);
            h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.name.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 8), 0, 0);
            cell.addView(h.name);

            h.count = Ui.text(ctx, "", 13, Ui.SUBTEXT, false);
            h.count.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 1), 0, 0);
            cell.addView(h.count);

            cell.setTag(h);
            convertView = cell;
        } else {
            h = (Holder) convertView.getTag();
        }

        MediaRepository.Album a = albums.get(position);
        h.name.setText(a.name);
        h.count.setText(Ui.count(a.items.size()));
        int size = Math.max(128, ctx.getResources().getDisplayMetrics().widthPixels / 3);
        loader.load(a.items.get(0), h.cover, size);
        return convertView;
    }
}
