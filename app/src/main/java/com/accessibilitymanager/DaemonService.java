package com.accessibilitymanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 后台守护服务 (增强版)
 * <p>
 * 新增特性：
 * 1. 监听屏幕点亮/解锁广播：手机唤醒时立即检查。
 * 2. 定时心跳机制：兜底防止 Observer 失效。
 */
public class DaemonService extends Service {
    private static final String TAG = "DaemonService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "daemon_service_channel";

    // 心跳间隔：60秒检查一次
    private static final long HEARTBEAT_INTERVAL = 60 * 1000;

    private SettingsObserver mContentOb;
    private HandlerThread mWorkerThread;
    private Handler mHandler;
    private Handler mMainHandler;
    private SharedPreferences sp;

    /**
     * 上一次由本服务写进去的设置原始值。
     * <p>
     * 用它判断"这次设置变化是不是自己引起的"：读到的值和它一致 ⇒ 是我们自己写的，跳过；
     * 不一致 ⇒ 是外部（系统/用户/别的应用）改的，必须立刻处理。
     * <p>
     * 改前这里是一个盲目的 500ms 时间窗 —— 系统若在我们写回后 0.5 秒内又把条目摘掉，
     * 那次变更正好落在窗口里被误吞，只能等 60 秒心跳才恢复（实机表现：强停后要等约一分钟）。
     */
    private String lastSelfWritten;

    /** 连续"刚写回又被摘掉"的次数：用于短间隔重试；一切正常时清零。 */
    private int restoreRetry;

    /** 短间隔重试上限：1s、2s、4s 各一次，之后交回常规触发（设置变化/亮屏/心跳）。 */
    private static final int MAX_RESTORE_RETRY = 3;

    // 屏幕广播接收器
    private ScreenReceiver mScreenReceiver;

    // 心跳任务
    private final Runnable mHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            doDaemonCheck();
            // 循环调用，保持心跳
            if (mHandler != null) {
                mHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences(AppConstants.PREFS_NAME, 0);
        mMainHandler = new Handler(getMainLooper());

        // 1. 启动后台线程
        mWorkerThread = new HandlerThread("DaemonWorker");
        mWorkerThread.start();
        mHandler = new Handler(mWorkerThread.getLooper());

        // 2. 注册 Settings 监听器
        mContentOb = new SettingsObserver(mHandler);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                true,
                mContentOb
        );

        // 3. 注册屏幕广播监听 (新增的关键部分)
        registerScreenReceiver();

        // 4. 开启前台服务通知
        startForegroundNotification();

        // 5. 立即执行一次检查，并启动心跳
        mHandler.post(this::doDaemonCheck);
        mHandler.postDelayed(mHeartbeatRunnable, HEARTBEAT_INTERVAL);

        Log.i(TAG, "DaemonService 已启动 (含屏幕监听与心跳)...");
    }

    /**
     * 注册动态广播接收器监听屏幕状态
     * 必须在代码中注册，Android 8.0+ 静态注册无效
     */
    private void registerScreenReceiver() {
        mScreenReceiver = new ScreenReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);     // 屏幕点亮
        filter.addAction(Intent.ACTION_USER_PRESENT);  // 用户解锁
        registerReceiver(mScreenReceiver, filter);
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.daemon_notification_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_accessibility)
                .setContentTitle(getString(R.string.daemon_notification_title))
                .setContentText(getString(R.string.daemon_notification_text))
                .setContentIntent(pi);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }
        startForeground(NOTIFICATION_ID, builder.build());
    }

    /** 该组件是否仍在守护名单里（逐个 unflatten 比较，避开 flatten 长短格式差异）。 */
    private static boolean stillListed(Set<String> ids, ComponentName cn) {
        for (String id : ids) {
            if (cn.equals(ComponentName.unflattenFromString(id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 核心检测逻辑
     * 运行在后台线程，比对当前开启的服务与保活列表
     */
    private void doDaemonCheck() {
        // 这次设置变化是自己引起的吗？比对"现在读到的原始值"和"我们上次写进去的值"。
        // 一致才是我们写的（跳过）；不一致说明外部动了它，立刻往下处理。
        String currentRaw = AccessibilityUtils.getRawEnabledServices(this);
        if (lastSelfWritten != null && lastSelfWritten.equals(currentRaw)) {
            return;
        }

        Set<String> daemonIds = DaemonListStore.readIds(sp);
        if (daemonIds.isEmpty()) {
            Log.d(TAG, "检查：守护名单为空，跳过");
            return;
        }

        Set<ComponentName> currentEnabled = AccessibilityUtils.getEnabledServices(this);
        // 诊断用：一次检查就够看出"名单里有没有它""系统认不认为它已启用"
        Log.d(TAG, "检查：守护名单=" + daemonIds + " 当前已启用=" + currentEnabled);
        Set<ComponentName> targetEnabled = new HashSet<>(currentEnabled);
        boolean needUpdate = false;
        Map<ComponentName, String> restoredLabels = new HashMap<>();

        for (String id : daemonIds) {
            ComponentName cn = ComponentName.unflattenFromString(id);

            // 如果保活列表中的服务未开启
            if (cn != null && !currentEnabled.contains(cn)) {
                try {
                    getPackageManager().getServiceInfo(cn, 0);
                    targetEnabled.add(cn);
                    needUpdate = true;
                    String label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(cn.getPackageName(), 0)).toString();
                    restoredLabels.put(cn, label);
                } catch (PackageManager.NameNotFoundException ignored) {
                    Log.w(TAG, "未找到应用，跳过保活: " + id);
                }
            }
        }

        if (needUpdate) {
            // 写回之前重读一次守护名单：上面的名单是本次检查开头读的，中间用户可能已经点了锁定按钮
            // 把某一项解锁（"关掉服务"与"解锁"是同一次点击的两步）—— 那就不能再把它拉回来，
            // 否则用户看到的就是"关掉的一瞬间又弹回开启"。
            Set<String> stillDaemon = DaemonListStore.readIds(sp);
            Set<ComponentName> finalTarget = new HashSet<>(currentEnabled);
            StringBuilder restoredNames = new StringBuilder();
            // 遍历"真正要恢复的那些"（targetEnabled - currentEnabled），
            // 不要遍历 restoredLabels —— 标签只是提示文案，取不到标签的项也必须照常恢复。
            for (ComponentName cn : targetEnabled) {
                if (currentEnabled.contains(cn)) {
                    continue; // 本来就是开的，不用动
                }
                if (!stillListed(stillDaemon, cn)) {
                    Log.i(TAG, "该项已被用户解锁，本次不恢复: " + cn.flattenToString());
                    continue;
                }
                finalTarget.add(cn);
                String label = restoredLabels.get(cn);
                restoredNames.append(label != null ? label : cn.flattenToString()).append(' ');
            }
            if (restoredNames.length() == 0) {
                Log.d(TAG, "本次要恢复的项都已被用户解锁，跳过写回");
                return; // 不值得为此写一次设置
            }

            Log.d(TAG, "检测到服务被关闭，正在恢复: " + restoredNames);
            lastSelfWritten = AccessibilityUtils.setEnabledServices(this, finalTarget);
            // 立刻读回一次：写入有没有被系统接受（强停状态下可能马上又被摘掉）
            Log.i(TAG, "已写回启用列表，复查=" + AccessibilityUtils.getEnabledServices(this));

            // 短间隔重试：系统摘掉后通常 1 秒内就能再写一次，比等 60 秒心跳快得多。
            // 次数有限（1s/2s/4s），一切正常时会清零，不会长期空转。
            if (restoreRetry < MAX_RESTORE_RETRY) {
                restoreRetry++;
                long delay = 1000L << (restoreRetry - 1);
                mHandler.postDelayed(this::doDaemonCheck, delay);
                Log.i(TAG, "已安排 " + delay + "ms 后复查（第 " + restoreRetry + " 次）");
            }

            if (sp.getBoolean(AppConstants.KEY_SHOW_TOAST, true)) {
                String msg = getString(R.string.daemon_restore_toast, restoredNames.toString().trim());
                mMainHandler.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
            }
        } else {
            restoreRetry = 0; // 一切正常，重试计数归零
        }
    }

    /**
     * Settings 内容观察者
     */
    class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            // Log.d(TAG, "Settings changed, trigger check");
            mHandler.removeCallbacks(mHeartbeatRunnable); // 重置心跳计时
            mHandler.post(DaemonService.this::doDaemonCheck);
            mHandler.postDelayed(mHeartbeatRunnable, HEARTBEAT_INTERVAL);
        }
    }

    /**
     * 屏幕广播接收器内部类
     */
    class ScreenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                Log.i(TAG, "屏幕点亮/解锁，强制检查服务状态...");
                if (mHandler != null) {
                    mHandler.post(DaemonService.this::doDaemonCheck);
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次 startService 也触发一次检查
        if (mHandler != null) {
            mHandler.post(this::doDaemonCheck);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mContentOb != null) {
            getContentResolver().unregisterContentObserver(mContentOb);
        }
        if (mScreenReceiver != null) {
            unregisterReceiver(mScreenReceiver);
        }
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        if (mWorkerThread != null) {
            mWorkerThread.quitSafely();
        }
        Log.i(TAG, "DaemonService destroyed.");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}