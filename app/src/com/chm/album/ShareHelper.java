package com.chm.album;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 사진/동영상을 다른 앱으로 공유한다. */
public final class ShareHelper {
    private ShareHelper() {
    }

    public static void share(Activity a, List<MediaItem> items) {
        if (items.isEmpty()) {
            Toast.makeText(a, "공유할 항목을 선택하세요", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send;
        if (items.size() == 1) {
            MediaItem m = items.get(0);
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, m.uri);
            send.setType(m.mime);
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            for (MediaItem m : items) uris.add(m.uri);
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            send.setType(commonType(items));
        }
        // 받는 앱이 파일을 읽을 수 있도록 읽기 권한을 넘긴다
        ClipData clip = ClipData.newRawUri("", items.get(0).uri);
        for (int i = 1; i < items.size(); i++) clip.addItem(new ClipData.Item(items.get(i).uri));
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        String title = items.size() == 1 ? "공유" : items.size() + "개 항목 공유";
        try {
            a.startActivity(Intent.createChooser(send, title));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "공유할 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    private static String commonType(List<MediaItem> items) {
        boolean anyImage = false;
        boolean anyVideo = false;
        for (MediaItem m : items) {
            if (m.isVideo) anyVideo = true;
            else anyImage = true;
        }
        if (anyImage && anyVideo) return "*/*";
        return anyVideo ? "video/*" : "image/*";
    }
}
