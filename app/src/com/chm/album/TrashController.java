package com.chm.album;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 휴지통으로 이동 / 복원 / 완전 삭제.
 *
 * Android 11 이상: 시스템 휴지통(MediaStore.createTrashRequest)을 쓴다. 확인 창은 시스템이 띄우고,
 * 30일이 지나면 시스템이 자동으로 지운다.
 * Android 10 이하: 시스템 휴지통이 없어서 LegacyTrash(앱 전용 폴더)에 옮겨 둔다.
 *
 * 이 클래스를 쓰는 Activity 는 onActivityResult / onRequestPermissionsResult 를 넘겨줘야 한다.
 */
public class TrashController {

    public interface Callback {
        /** 작업이 끝남. affected 는 실제로 처리된 항목. */
        void onTrashDone(int op, List<MediaItem> affected);
    }

    public static final int OP_TRASH = 1;
    public static final int OP_RESTORE = 2;
    public static final int OP_DELETE = 3;

    private static final int REQ_SYSTEM = 901;
    private static final int REQ_RECOVERABLE = 902;
    private static final int REQ_WRITE = 903;

    private final Activity activity;
    private final Callback callback;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 시스템 확인 창이나 권한 요청의 결과를 기다리는 작업 */
    private int pendingOp;
    private List<MediaItem> pendingItems;

    /** Android 10 에서 다른 앱이 만든 파일을 지울 때 항목마다 받는 사용자 승인 대기열 */
    private final ArrayDeque<Object[]> recoverQueue = new ArrayDeque<>();
    private Object[] recovering;
    private final List<MediaItem> moved = new ArrayList<>();

    public TrashController(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public static boolean usesSystemTrash() {
        return Build.VERSION.SDK_INT >= 30;
    }

    public static String message(int op, int count) {
        String n = count == 1 ? "1개 항목을" : count + "개 항목을";
        if (op == OP_TRASH) return n + " 휴지통으로 이동했습니다";
        if (op == OP_RESTORE) return n + " 복원했습니다";
        return n + " 완전히 삭제했습니다";
    }

    // ---------------------------------------------------------------- 공개 동작

    public void moveToTrash(final List<MediaItem> items) {
        if (items.isEmpty()) {
            toast("항목을 선택하세요");
            return;
        }
        if (usesSystemTrash()) {
            systemRequest(OP_TRASH, items);
            return;
        }
        confirm(items.size() == 1 ? "휴지통으로 이동할까요?" : items.size() + "개 항목을 휴지통으로 이동할까요?",
                "휴지통에 30일 동안 보관된 뒤 완전히 삭제됩니다.", "휴지통으로 이동", new Runnable() {
                    @Override
                    public void run() {
                        withWritePermission(OP_TRASH, items);
                    }
                });
    }

    public void restore(List<MediaItem> items) {
        if (items.isEmpty()) {
            toast("항목을 선택하세요");
            return;
        }
        if (usesSystemTrash()) systemRequest(OP_RESTORE, items);
        else withWritePermission(OP_RESTORE, items);
    }

    public void deleteForever(final List<MediaItem> items) {
        if (items.isEmpty()) {
            toast("항목을 선택하세요");
            return;
        }
        if (usesSystemTrash()) {
            systemRequest(OP_DELETE, items);
            return;
        }
        confirm(items.size() == 1 ? "완전히 삭제할까요?" : items.size() + "개 항목을 완전히 삭제할까요?",
                "삭제한 항목은 되돌릴 수 없습니다.", "삭제", new Runnable() {
                    @Override
                    public void run() {
                        runLegacy(OP_DELETE, items);
                    }
                });
    }

    /** Activity.onActivityResult 에서 호출. 처리했으면 true. */
    public boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode == REQ_SYSTEM) {
            List<MediaItem> items = pendingItems;
            int op = pendingOp;
            pendingItems = null;
            if (resultCode == Activity.RESULT_OK && items != null) finish(op, items);
            return true;
        }
        if (requestCode == REQ_RECOVERABLE) {
            final Object[] entry = recovering;
            recovering = null;
            if (entry == null) return true;
            if (resultCode == Activity.RESULT_OK) {
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        MediaItem m = (MediaItem) entry[0];
                        File copy = (File) entry[1];
                        try {
                            if (activity.getContentResolver().delete(m.uri, null, null) > 0) {
                                LegacyTrash.commit(activity, m, copy);
                                moved.add(m);
                            } else {
                                LegacyTrash.discardCopy(copy);
                            }
                        } catch (Exception e) {
                            LegacyTrash.discardCopy(copy);
                        }
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                nextRecoverable();
                            }
                        });
                    }
                });
            } else {
                LegacyTrash.discardCopy((File) entry[1]);
                nextRecoverable();
            }
            return true;
        }
        return false;
    }

    /** Activity.onRequestPermissionsResult 에서 호출. 처리했으면 true. */
    public boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_WRITE) return false;
        List<MediaItem> items = pendingItems;
        pendingItems = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && items != null) {
            runLegacy(pendingOp, items);
        } else {
            toast("저장소 권한이 필요합니다");
        }
        return true;
    }

    // ---------------------------------------------------------------- Android 11+

    private void systemRequest(int op, List<MediaItem> items) {
        try {
            ContentResolver cr = activity.getContentResolver();
            List<Uri> uris = new ArrayList<>();
            for (MediaItem m : items) uris.add(m.uri);
            PendingIntent pi;
            if (op == OP_DELETE) {
                Method m = MediaStore.class.getMethod("createDeleteRequest", ContentResolver.class, Collection.class);
                pi = (PendingIntent) m.invoke(null, cr, uris);
            } else {
                Method m = MediaStore.class.getMethod("createTrashRequest",
                        ContentResolver.class, Collection.class, boolean.class);
                pi = (PendingIntent) m.invoke(null, cr, uris, op == OP_TRASH);
            }
            pendingOp = op;
            pendingItems = new ArrayList<>(items);
            activity.startIntentSenderForResult(pi.getIntentSender(), REQ_SYSTEM, null, 0, 0, 0);
        } catch (Exception e) {
            toast("요청을 처리할 수 없습니다");
        }
    }

    // ---------------------------------------------------------------- Android 10 이하

    private void withWritePermission(int op, List<MediaItem> items) {
        if (MediaSaver.needsWritePermission()
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingOp = op;
            pendingItems = new ArrayList<>(items);
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        runLegacy(op, items);
    }

    private void runLegacy(final int op, final List<MediaItem> items) {
        moved.clear();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<MediaItem> done = new ArrayList<>();
                for (MediaItem m : items) {
                    try {
                        if (op == OP_TRASH) {
                            if (legacyTrashOne(m)) done.add(m);
                        } else if (op == OP_RESTORE) {
                            LegacyTrash.restore(activity, m);
                            done.add(m);
                        } else {
                            LegacyTrash.remove(activity, m);
                            done.add(m);
                        }
                    } catch (Exception ignored) {
                        // 이 항목은 건너뛴다
                    }
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (op == OP_TRASH) {
                            moved.addAll(done);
                            nextRecoverable();
                        } else {
                            finish(op, done);
                        }
                    }
                });
            }
        });
    }

    /** 휴지통 폴더로 복사한 뒤 원본을 지운다. Android 10 에서 승인이 필요하면 대기열에 넣고 false. */
    private boolean legacyTrashOne(MediaItem m) throws Exception {
        File copy = LegacyTrash.copyIn(activity, m);
        try {
            if (activity.getContentResolver().delete(m.uri, null, null) > 0) {
                LegacyTrash.commit(activity, m, copy);
                return true;
            }
            LegacyTrash.discardCopy(copy);
            return false;
        } catch (SecurityException e) {
            IntentSender sender = recoverableSender(e);
            if (sender == null) {
                LegacyTrash.discardCopy(copy);
                throw e;
            }
            recoverQueue.add(new Object[]{m, copy, sender});
            return false;
        }
    }

    /** Android 10 의 RecoverableSecurityException 에서 사용자 승인 화면을 꺼낸다. */
    private static IntentSender recoverableSender(SecurityException e) {
        if (Build.VERSION.SDK_INT < 29 || !"android.app.RecoverableSecurityException".equals(e.getClass().getName())) {
            return null;
        }
        try {
            Object action = e.getClass().getMethod("getUserAction").invoke(e);
            PendingIntent pi = (PendingIntent) action.getClass().getMethod("getActionIntent").invoke(action);
            return pi.getIntentSender();
        } catch (Exception ex) {
            return null;
        }
    }

    private void nextRecoverable() {
        Object[] entry = recoverQueue.poll();
        if (entry == null) {
            finish(OP_TRASH, new ArrayList<>(moved));
            moved.clear();
            return;
        }
        recovering = entry;
        try {
            activity.startIntentSenderForResult((IntentSender) entry[2], REQ_RECOVERABLE, null, 0, 0, 0);
        } catch (Exception e) {
            LegacyTrash.discardCopy((File) entry[1]);
            recovering = null;
            nextRecoverable();
        }
    }

    // ---------------------------------------------------------------- 공통

    private void finish(int op, List<MediaItem> affected) {
        if (activity.isFinishing()) return;
        if (!affected.isEmpty()) toast(message(op, affected.size()));
        callback.onTrashDone(op, affected);
    }

    private void confirm(String title, String body, String action, final Runnable onOk) {
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(title)
                .setMessage(body)
                .setNegativeButton("취소", null)
                .setPositiveButton(action, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        onOk.run();
                    }
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }
}
