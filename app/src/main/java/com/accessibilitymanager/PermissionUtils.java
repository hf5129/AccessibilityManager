package com.accessibilitymanager;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 权限管理工具类
 * <p>
 * 负责 WRITE_SECURE_SETTINGS 权限的检查与授权引导。
 * 支持 Root 命令授权、Shizuku 授权以及生成 ADB 命令。
 */
public class PermissionUtils {

    public static final int REQUEST_CODE_SHIZUKU = 1002;

    private static final String TAG = "PermissionUtils";

    /**
     * 检查是否拥有写入安全设置的权限
     *
     * @param context 上下文
     * @return true 表示已拥有权限
     */
    public static boolean hasSecureSettingsPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 显示权限申请引导对话框
     *
     * @param context 上下文
     */
    public static void showPermissionDialog(Context context) {
        String cmd = "pm grant " + context.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.permission_dialog_title)
                .setMessage(R.string.permission_dialog_message)
                .setPositiveButton(R.string.permission_dialog_action_copy_adb, (dialog, i) -> {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("cmd", "adb shell " + cmd));
                        Toast.makeText(context, R.string.permission_dialog_copy_success, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.permission_dialog_action_root, (dialog, i) -> runRootCommand(context, cmd))
                .setNeutralButton(R.string.permission_dialog_action_shizuku, (dialog, i) -> requestShizukuPermission(context, REQUEST_CODE_SHIZUKU))
                .show();
    }

    /**
     * 授权命令统一走这条单线程（守护线程，随进程退出）。
     * <p>
     * runRootCommand：要 exec("su") 并等结果，su 会等用户在 Magisk/SuperSU 里点授权，
     * 可能好几秒。runShizukuCommand：要等 Shizuku 起进程并返回退出码。两者都是
     * 阻塞等待，放主线程上就是掉帧甚至 ANR，所以整段丢后台，只有 Toast 回主线程。
     * 用单线程是为了让两条授权命令串行，不会同时弹两个 su 请求。
     */
    private static final ExecutorService SHELL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "privileged-shell");
        t.setDaemon(true);
        return t;
    });

    /** 等外部进程的上限（su 要等用户点授权，给宽一点）。 */
    private static final long SHELL_TIMEOUT_SECONDS = 10;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** 后台线程执行完，Toast 统一回主线程弹。 */
    private static void toastOnMain(Context context, int resId) {
        MAIN_HANDLER.post(() -> Toast.makeText(context, resId, Toast.LENGTH_SHORT).show());
    }

    private static void toastOnMain(Context context, String text) {
        MAIN_HANDLER.post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }

    /**
     * 通过 Root 执行授权命令（在后台线程执行，不阻塞调用方）
     */
    private static void runRootCommand(Context context, String cmd) {
        Context appContext = context.getApplicationContext();
        SHELL_EXECUTOR.execute(() -> {
            Process p = null;
            DataOutputStream o = null;
            try {
                p = Runtime.getRuntime().exec("su");
                o = new DataOutputStream(p.getOutputStream());
                o.writeBytes(cmd + "\nexit\n");
                o.flush();
                if (p.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    int exitCode = p.exitValue();
                    if (hasSecureSettingsPermission(appContext)) {
                        toastOnMain(appContext, R.string.permission_dialog_root_success);
                    } else {
                        toastOnMain(appContext, appContext.getString(R.string.permission_dialog_root_failed, exitCode));
                    }
                } else {
                    // 超时：多半是用户没在 su 弹窗里点授权
                    toastOnMain(appContext, R.string.permission_dialog_root_unavailable);
                }
            } catch (Exception e) {
                Log.e(TAG, "Root command failed", e);
                toastOnMain(appContext, R.string.permission_dialog_root_unavailable);
            } finally {
                try {
                    if (o != null) o.close();
                } catch (IOException ignored) {
                }
                if (p != null) p.destroy();
            }
        });
    }

    /**
     * 请求 Shizuku 权限
     */
    public static void requestShizukuPermission(Context context, int requestCode) {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                runShizukuCommand(context);
            } else {
                Shizuku.requestPermission(requestCode);
                // 授权结果将在 Activity 的 Listener 中回调
            }
        } catch (Exception e) {
            Log.e(TAG, "Shizuku not available", e);
            Toast.makeText(context, R.string.permission_dialog_shizuku_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 通过 Shizuku 执行授权命令（后台线程执行；完成后按真实权限状态报结果）。
     * <p>
     * ★ 这里必须用**阻塞版** {@code waitFor()}。
     * {@link rikka.shizuku.ShizukuRemoteProcess} 只重写了无参的 {@code waitFor()}，
     * 没重写 {@code waitFor(long, TimeUnit)}，后者会落到 JDK 默认实现 —— 它会**轮询
     * {@code exitValue()}**，而 Shizuku 服务端的 {@code RemoteProcessHolder.exitValue()}
     * 是裸的 {@code process.exitValue()}，进程还没结束时抛 {@code IllegalThreadStateException}，
     * 跨 Binder 按 EX_ILLEGAL_ARGUMENT 传回客户端就变成了 {@code IllegalArgumentException}
     * （前者正是后者的子类），JDK 默认实现只捕获 IllegalThreadStateException，于是它直接
     * 逃出来打断授权 —— 表现就是"点了 Shizuku 却一直需要权限"。
     */
    public static void runShizukuCommand(Context context) {
        Context appContext = context.getApplicationContext();
        String cmd = "pm grant " + appContext.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
        SHELL_EXECUTOR.execute(() -> {
            Process p = null;
            try {
                // sh 用绝对路径：不依赖服务端进程的 PATH
                p = Shizuku.newProcess(new String[]{"/system/bin/sh", "-c", cmd}, null, null);
                int exitCode = p.waitFor();
                if (hasSecureSettingsPermission(appContext)) {
                    toastOnMain(appContext, R.string.permission_dialog_shizuku_success);
                } else {
                    toastOnMain(appContext, appContext.getString(R.string.permission_dialog_shizuku_failed) + "：exit=" + exitCode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Shizuku command failed", e);
                toastOnMain(appContext, appContext.getString(R.string.permission_dialog_shizuku_failed)
                        + "：" + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                if (p != null) p.destroy();
            }
        });
    }
}