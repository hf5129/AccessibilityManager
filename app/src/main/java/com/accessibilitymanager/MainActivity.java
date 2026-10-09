package com.accessibilitymanager;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.transition.Transition;
import androidx.transition.TransitionListenerAdapter;
import androidx.transition.TransitionManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.listitem.ListItemLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.transition.MaterialFade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * 主界面 Activity
 * <p>
 * 负责展示系统中的无障碍服务列表，提供开关控制与保活锁定功能。
 * 实现了权限的按需申请与保活服务的静默开启。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 1001; // 权限请求码

    private List<AccessibilityServiceInfo> serviceList;
    private List<AccessibilityServiceInfo> allServices;
    private SharedPreferences sp;
    private String daemonListStr;
    private ServiceAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ContentObserver settingsObserver;
    private Shizuku.OnRequestPermissionResultListener shizukuPermissionListener;

    /**
     * 拨开关、锁按钮要写的系统设置都是 IPC。统一放到这条后台线程上做，
     * 免得压在主线程上跟动画抢帧（MaterialSwitch 自己的滑柄是 250ms 补间）。
     */
    private HandlerThread toggleThread;
    private Handler toggleHandler;

    /**
     * 当前"已开启的无障碍服务"快照。
     * <p>
     * 每次刷新列表时读一次 Settings（一次 IPC），行内判断与系统应用过滤都查这份快照。
     * 改前是每个服务、每一行各读一次 Settings，一次刷新几十次 IPC 全压在主线程上，
     * 拨开关时的动画正好被这些活拖住，看着就是卡。
     */
    private Set<ComponentName> enabledServiceSnapshot = Collections.emptySet();

    /**
     * 应用元数据缓存：服务名 / 图标 / 是否系统应用。
     * <p>
     * PackageManager.getApplicationInfo()、getApplicationIcon() 都是 IPC，而同一份数据在一次
     * 刷新里会被反复取（过滤逐项取一遍、绑定又逐行取一遍）。缓存后每个包只查一次，
     * 之后刷新只做内存查表。
     */
    private final Map<String, AppMeta> appMetaCache = new HashMap<>();

    /** 一个包的应用元数据；found = false 表示查不到这个包。 */
    private static final class AppMeta {
        final boolean found;
        final CharSequence label;
        final Drawable icon;
        final boolean isSystem;

        AppMeta(boolean found, CharSequence label, Drawable icon, boolean isSystem) {
            this.found = found;
            this.label = label;
            this.icon = icon;
            this.isSystem = isSystem;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initToolbar();
        initImmersiveStatusBar();

        sp = getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE);
        daemonListStr = sp.getString(AppConstants.KEY_DAEMON_LIST, "");

        // 桌面图标跟随「跟随壁纸取色」：两套图标各挂一个 activity-alias，这里按设置同步启用状态
        syncLauncherIcon();

        // 初始化“隐藏后台”状态
        boolean hideRecents = sp.getBoolean(AppConstants.KEY_HIDE_RECENTS, false);
        if (hideRecents) {
            applyHideFromRecents(true);
        }

        // 拨开关、锁按钮的设置读改写统一走这条后台线程（IPC 不占动画帧）
        toggleThread = new HandlerThread("ToggleWorker");
        toggleThread.start();
        toggleHandler = new Handler(toggleThread.getLooper());

        initListView();
        initSettingsObserver();
        initShizukuListener();

        // 检查并申请通知权限 (Android 13+)
        checkNotificationPermission();

        // 尝试启动守护服务 (如果有权限)
        startDaemonService();
    }

    private void initToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }
    }

    /**
     * 配置沉浸式状态栏和导航栏
     */
    private void initImmersiveStatusBar() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            // 确保布局延伸到状态栏和导航栏下方
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            // 浅色模式下让状态栏图标变深色，避免白图标糊在浅色背景上
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isDarkMode()) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private boolean isDarkMode() {
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void initListView() {
        ListView listView = findViewById(R.id.list);
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);

        if (am != null) {
            allServices = new ArrayList<>(am.getInstalledAccessibilityServiceList());
        } else {
            allServices = new ArrayList<>();
        }

        refreshServiceList();

        adapter = new ServiceAdapter();
        listView.setAdapter(adapter);
    }

    /**
     * 重新加载列表：先按“显示系统应用”开关过滤，再按保活名单置顶排序。
     * 规则：非系统应用始终显示；系统应用仅在勾选开关时显示，
     * 但已开启无障碍权限且已锁定（保活名单）的系统应用始终保留。
     */
    private void refreshServiceList() {
        // 滑柄自己的形变窗口内不干这活：这里要读一次 Settings（IPC）、过滤排序，再让 ListView
        // 把可见项全部重绑，整段压在这 250ms 上滑柄就会掉帧。所以攒着，等窗口过去由
        // flushAdapterRefresh() 再刷 —— 那时 lastToggleAt 已经出了窗口，这里会直接放行。
        long sinceToggle = SystemClock.uptimeMillis() - lastToggleAt;
        if (sinceToggle < SWITCH_MORPH_MS) {
            pendingFullRefresh = true;
            if (!refreshScheduled) {
                refreshScheduled = true;
                mainHandler.postDelayed(this::flushAdapterRefresh, SWITCH_MORPH_MS - sinceToggle);
            }
            return;
        }
        boolean showSystem = sp.getBoolean(AppConstants.KEY_SHOW_SYSTEM_APPS, false);
        // 一个刷新周期只读一次 Settings，后面全查快照（原来是逐服务、逐行各读一次）。
        enabledServiceSnapshot = AccessibilityUtils.getEnabledServices(this);
        List<AccessibilityServiceInfo> filtered = new ArrayList<>();
        for (AccessibilityServiceInfo info : allServices) {
            if (shouldShowService(info, showSystem)) {
                filtered.add(info);
            }
        }
        serviceList = filtered;
        sortServices();
        if (adapter != null) {
            requestAdapterRefresh();
        }
    }

    /** 查快照，不读 Settings。 */
    private boolean isServiceEnabledSnapshot(String serviceId) {
        ComponentName cn = ComponentName.unflattenFromString(serviceId);
        return cn != null && enabledServiceSnapshot.contains(cn);
    }

    /**
     * 取应用元数据，带缓存。
     * <p>
     * 每个包只在首次遇到时查一次 PackageManager，之后都是查表。
     */
    private AppMeta getAppMeta(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return new AppMeta(false, null, null, false);
        }
        AppMeta cached = appMetaCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        AppMeta meta;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            meta = new AppMeta(true, ai.loadLabel(pm), ai.loadIcon(pm),
                    (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
        } catch (PackageManager.NameNotFoundException e) {
            meta = new AppMeta(false, null, null, false);
        }
        appMetaCache.put(packageName, meta);
        return meta;
    }

    /**
     * 取出图标的一份副本再交给 ImageView。
     * <p>
     * 同一个包可能对应多个服务（多个条目的图标来自同一个 Drawable），而 Drawable 只有一个
     * callback，直接共用同一实例会让两个 ImageView 互相踩。复制很便宜，够用。
     */
    private Drawable copyIcon(Drawable src) {
        if (src == null) {
            return null;
        }
        Drawable.ConstantState state = src.getConstantState();
        return state != null ? state.newDrawable(getResources()) : src;
    }

    private boolean shouldShowService(AccessibilityServiceInfo info, boolean showSystem) {
        if (showSystem) {
            return true;
        }
        ComponentName cn = ComponentName.unflattenFromString(info.getId());
        if (cn == null) {
            return true;
        }
        AppMeta meta = getAppMeta(cn.getPackageName());
        if (!meta.found || !meta.isSystem) {
            return true;
        }
        // 已开启无障碍权限且已锁定的系统应用，取消勾选后也不消失
        String id = info.getId();
        return isServiceEnabledSnapshot(id) && DaemonListStore.containsId(daemonListStr, id);
    }

    private void sortServices() {
        Collections.sort(serviceList, (o1, o2) -> {
            boolean firstPinned = DaemonListStore.containsId(daemonListStr, o1.getId());
            boolean secondPinned = DaemonListStore.containsId(daemonListStr, o2.getId());
            return Boolean.compare(secondPinned, firstPinned);
        });
    }

    private void initSettingsObserver() {
        settingsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                if (adapter != null) {
                    refreshServiceList();
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                true, settingsObserver);
    }

    private void initShizukuListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            shizukuPermissionListener = (requestCode, grantResult) -> {
                if (requestCode != PermissionUtils.REQUEST_CODE_SHIZUKU) {
                    return;
                }
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    PermissionUtils.runShizukuCommand(this);
                }
            };
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        }
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE_POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 用户授权后，尝试重新启动服务以显示通知
                startDaemonService();
            } else {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 把"写系统设置"这段活（Settings.Secure 的读改写全是 IPC）丢到后台线程，
     * 写完再回主线程按系统设置的真实状态刷新一次列表。
     * <p>
     * 原来这些 IPC 都直接跑在拨动那一帧上，正好跟 MaterialSwitch 250ms 的滑柄动画、
     * 锁按钮的形变动画抢帧——动画丢帧看着就是卡。挪到后台后动画帧就干净了；
     * 写完回主线程刷新的另一个好处是：万一写入失败（没权限），界面也能被纠正回真实状态。
     * （回主线程时若仍在滑柄形变窗口里，这次刷新会被 refreshServiceList() 拦下，等窗口过去再补。）
     */
    private void runOffAnimationFrames(@NonNull final Runnable settingsWork) {
        final Runnable work = () -> {
            settingsWork.run();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    refreshServiceList();
                }
            });
        };
        if (toggleHandler != null) {
            toggleHandler.post(work);
        } else {
            // 理论上走不到（onCreate 里就建好了），但真走到了也不能把用户这次拨动吞掉
            work.run();
        }
    }

    private void startDaemonService() {
        if (!PermissionUtils.hasSecureSettingsPermission(this)) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to request battery optimization ignore", e);
                }
            }
        }

        Intent intent = new Intent(this, DaemonService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /**
     * 设置应用是否从最近任务列表中隐藏
     */
    private void applyHideFromRecents(boolean hide) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ActivityManager.AppTask> tasks = am.getAppTasks();
                    if (tasks != null && !tasks.isEmpty()) {
                        tasks.get(0).setExcludeFromRecents(hide);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to change recents visibility", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // quitSafely：已经排进队列的那次设置写入仍会执行完，不丢用户刚拨的状态
        if (toggleThread != null) {
            toggleThread.quitSafely();
        }
        if (settingsObserver != null) {
            getContentResolver().unregisterContentObserver(settingsObserver);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && shizukuPermissionListener != null) {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.arrange, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            showSettingsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 「设置」弹窗：工具栏右侧三点按钮点开的那个。
     * <p>
     * 五项设置（开机自启 / 保活提示 / 隐藏最近任务 / 显示系统应用 / 跟随壁纸取色）以列表项样式排在弹窗里
     * （布局见 layout/dialog_settings.xml），改动先留在弹窗内，点「确认」才落盘、点「取消」整批丢弃 ——
     * 所以这里读一次当前值、写一次期望值，中间不再碰 SharedPreferences。
     * <p>
     * 关于复用主列表那套 ListItemLayout：圆角靠 ListItemLayout.updateAppearance(position, count)
     * 按位置下发 state_first / state_middle / state_last / state_single 实现，
     * 与 ServiceAdapter.getView() 里那一行的用法完全一致；不调它四张卡片就只有默认圆角、连不成一段。
     * 尾部控件是 M3 勾选框（MaterialCheckBox），点整行等于勾选/取消这一行
     * （卡片可点，和列表项一样有涟漪）。
     * 按钮没有交给 dialog 的 buttonPanel，原因见 dialog_settings.xml 顶部注释。
     */
    private void showSettingsDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_settings, null, false);

        // 顺序 = 弹窗里的视觉顺序（layout/dialog_settings.xml 的排列）：
        //   0 跟随壁纸取色 / 1 开机自动启动守护 / 2 显示保活提示 / 3 从最近任务中隐藏 / 4 显示系统应用
        // rows[] 必须与 XML 同序：updateAppearance(i, count) 用 i 当**位置**下发首/中/尾圆角；
        // 写入设置时不要再用下标，改成按 id 取（见下面的确认按钮），免得以后再调顺序时改漏。
        ListItemLayout[] rows = {
                content.findViewById(R.id.setting_item_dynamic_color),
                content.findViewById(R.id.setting_item_auto_boot),
                content.findViewById(R.id.setting_item_show_toast),
                content.findViewById(R.id.setting_item_hide_recents),
                content.findViewById(R.id.setting_item_show_system),
        };
        MaterialCheckBox[] toggles = {
                content.findViewById(R.id.setting_checkbox_dynamic_color),
                content.findViewById(R.id.setting_checkbox_auto_boot),
                content.findViewById(R.id.setting_checkbox_show_toast),
                content.findViewById(R.id.setting_checkbox_hide_recents),
                content.findViewById(R.id.setting_checkbox_show_system),
        };
        boolean[] current = {
                sp.getBoolean(AppConstants.KEY_DYNAMIC_COLOR, true),
                sp.getBoolean(AppConstants.KEY_AUTO_BOOT, true),
                sp.getBoolean(AppConstants.KEY_SHOW_TOAST, true),
                sp.getBoolean(AppConstants.KEY_HIDE_RECENTS, false),
                sp.getBoolean(AppConstants.KEY_SHOW_SYSTEM_APPS, false),
        };

        for (int i = 0; i < rows.length; i++) {
            rows[i].updateAppearance(i, rows.length);
            toggles[i].setChecked(current[i]);
            final MaterialCheckBox rowToggle = toggles[i];
            // 首行上沿、末行下沿那 1dp 不用在这里处理：弹窗的五行在 dialog_settings.xml 里
            // 是各自独立的静态块，第一行直接不写 layout_marginTop、最后一行不写 layout_marginBottom 即可。
            // （主列表 item.xml 只有一个、被 ListView 复用，才必须按位置在代码里收敛边距，
            //   见 applySegmentedEdgeMargins()。）
            rows[i].findViewById(R.id.card_view).setOnClickListener(v -> rowToggle.toggle());
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_title)
                .setView(content)
                .create();

        content.findViewById(R.id.settings_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.settings_confirm).setOnClickListener(v -> {
            // 按 id 取值而不是按数组下标 —— 弹窗里各项的顺序以后还可能调整，下标容易改漏。
            MaterialCheckBox cbDynamicColor = content.findViewById(R.id.setting_checkbox_dynamic_color);
            MaterialCheckBox cbAutoBoot = content.findViewById(R.id.setting_checkbox_auto_boot);
            MaterialCheckBox cbShowToast = content.findViewById(R.id.setting_checkbox_show_toast);
            MaterialCheckBox cbHideRecents = content.findViewById(R.id.setting_checkbox_hide_recents);
            MaterialCheckBox cbShowSystem = content.findViewById(R.id.setting_checkbox_show_system);
            boolean dynamicColorChanged =
                    cbDynamicColor.isChecked() != sp.getBoolean(AppConstants.KEY_DYNAMIC_COLOR, true);
            applySettings(
                    cbAutoBoot.isChecked(), cbShowToast.isChecked(),
                    cbHideRecents.isChecked(), cbShowSystem.isChecked(), cbDynamicColor.isChecked());
            dialog.dismiss();
            // 动态取色是在 Activity 建主题那一刻套上去的 overlay（见 DynamicColors），
            // 光改 SharedPreferences 不会让当前界面变色，得重建一次 Activity。
            // 放在 dismiss() 之后重建，免得连弹窗一起重建。
            if (dynamicColorChanged) {
                recreate();
            }
        });

        dialog.show();
    }

    /**
     * 把弹窗里攒下的五项设置一次性写进 SharedPreferences，并执行必须现场做的那两件副作用。
     * <p>
     * 原来勾一下就直接写、直接执行；现在推迟到「确认」这一刻，但副作用不能省：
     * · 从最近任务中隐藏 —— AppTask.setExcludeFromRecents 得真调一次（只落盘不调，本次会话不生效）；
     * · 显示系统应用 —— 过滤条件变了，列表要重刷（refreshServiceList() 自己会避开滑柄形变窗口）。
     * 另三项是「下次读取时生效」：开机自启由 StartReceiver 开机时读，保活提示由 DaemonService 弹提示时读，
     * 动态取色由 DynamicColors 在 Activity 建主题前读（需要重建 Activity，由调用方负责）—— 这里落盘即可。
     * 只在值真的变了时才触发副作用，避免点一次「确认」白刷一遍列表。
     */
    private void applySettings(boolean autoBoot, boolean showToast, boolean hideRecents,
                               boolean showSystemApps, boolean dynamicColor) {
        boolean hideChanged = hideRecents != sp.getBoolean(AppConstants.KEY_HIDE_RECENTS, false);
        boolean showSystemChanged = showSystemApps != sp.getBoolean(AppConstants.KEY_SHOW_SYSTEM_APPS, false);

        sp.edit()
                .putBoolean(AppConstants.KEY_AUTO_BOOT, autoBoot)
                .putBoolean(AppConstants.KEY_SHOW_TOAST, showToast)
                .putBoolean(AppConstants.KEY_HIDE_RECENTS, hideRecents)
                .putBoolean(AppConstants.KEY_SHOW_SYSTEM_APPS, showSystemApps)
                .putBoolean(AppConstants.KEY_DYNAMIC_COLOR, dynamicColor)
                .apply();

        if (hideChanged) {
            applyHideFromRecents(hideRecents);
        }
        if (showSystemChanged) {
            refreshServiceList();
        }
    }

    /**
     * 让桌面图标跟随「跟随壁纸取色」开关。
     * <p>
     * 桌面图标是 APK 里的静态资源，一个应用内复选框改不了它 —— 想让它跟着设置变，只能准备
     * 两套图标、各挂一个 activity-alias（见 AndroidManifest.xml 的 .LauncherDynamic /
     * .Launcher），再按设置启用其中一个。
     * <p>
     * 顺序要命：先启用目标、再停用另一个。反过来的话中间会有"两个都停用"的空档，
     * 那一瞬间应用在桌面上是彻底消失的。
     * <p>
     * 切换用 DONT_KILL_APP：改组件启用状态默认会把本进程杀掉，而这里只是换个图标。
     * <p>
     * 只在 onCreate 调一次就够：这个设置项只能在本界面改，而点「确认」后值有变化会 recreate()，
     * 会重新走一遍 onCreate。
     */
    private void syncLauncherIcon() {
        boolean dynamicColor = sp.getBoolean(AppConstants.KEY_DYNAMIC_COLOR, true);
        setLauncherAliasEnabled(".LauncherDynamic", dynamicColor);
        setLauncherAliasEnabled(".Launcher", !dynamicColor);
    }

    /**
     * 按需切换某个桌面入口别名的启用状态。已经是目标状态就不动 —— 省一次 IPC，
     * 也避免每次进应用都去惊动启动器刷新一遍图标。
     */
    private void setLauncherAliasEnabled(String aliasSuffix, boolean enabled) {
        ComponentName alias = new ComponentName(getPackageName(), getPackageName() + aliasSuffix);
        PackageManager pm = getPackageManager();
        int want = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            if (pm.getComponentEnabledSetting(alias) != want) {
                pm.setComponentEnabledSetting(alias, want, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception e) {
            // 切图标失败不该影响启动；下次进界面还会再同步一次
            Log.w(TAG, "切换桌面图标别名失败: " + aliasSuffix, e);
        }
    }

    private void showServiceDescriptionDialog(String title, CharSequence description) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(description)
                .setPositiveButton(R.string.dialog_close, null)
                .show();
    }


    /**
     * 刷新必须避开的时间窗：MaterialSwitch 自己那套形变的总时长。
     * <p>
     * 滑柄位移是 SwitchCompat 里写死的 250ms ObjectAnimator（连 interpolator 都没开放），
     * 形状是 150ms + 100ms 两段 pathData 变形，轨道变色按行程比例插值 —— 三条都在 250ms 收尾。
     * 这段时长长在库里，本侧没有合法办法改；能做的只有别在这 250ms 里插手，让它一帧不掉地跑完。
     */
    private static final long SWITCH_MORPH_MS = 260L;

    /** 锁按钮动画进行中：期间挂起列表刷新，别让重绑掉帧把动画切成幻灯片。 */
    private boolean lockAnimInFlight;
    /** 刷新被挂起了，等窗口过去补一次。 */
    private boolean pendingAdapterRefresh;
    /** 整个列表重建被挂起了（窗口内 refreshServiceList() 被拦下），优先级高于单纯的重绑。 */
    private boolean pendingFullRefresh;
    /** 上一次拨开关的时刻：滑柄的形变从这一刻起跑满 SWITCH_MORPH_MS。 */
    private long lastToggleAt;
    /** 补刷已经排进消息队列了，避免重复 post。 */
    private boolean refreshScheduled;

    /**
     * 列表刷新的统一入口，顺带负责"别在动画里重绑"。
     * <p>
     * 有两段时间要避开：滑柄自己的形变窗口（拨动后 SWITCH_MORPH_MS），以及锁按钮弹簧还没收完时。
     * 落在窗口里就攒着、排一次延时补刷，而不是马上 notifyDataSetChanged()：重绑会让 ListView
     * 把可见项全部重新 measure / layout / 绑定，这段活只要压在形变的尾巴上，滑柄就会在最后几帧
     * 一顿。此前几版卡就卡在这儿 —— 1400 刚度那两版弹簧收尾时刻（阻尼 0.6 约 183ms、
     * 1.0 约 167ms）都落在滑柄 250ms 形变的窗口内，重绑正好打在尾巴上；换回慢弹簧后
     * alpha 要 280ms 上下，重绑本来就落在窗口外了，但窗口留着不吃亏：它是按 250ms 这个
     * 库内时长算的保险，不依赖弹簧参数挑得好不好。
     */
    private void requestAdapterRefresh() {
        if (adapter == null) {
            return;
        }
        long sinceToggle = SystemClock.uptimeMillis() - lastToggleAt;
        if (lockAnimInFlight || sinceToggle < SWITCH_MORPH_MS) {
            pendingAdapterRefresh = true;
            if (!refreshScheduled) {
                refreshScheduled = true;
                mainHandler.postDelayed(this::flushAdapterRefresh,
                        Math.max(0L, SWITCH_MORPH_MS - sinceToggle));
            }
        } else {
            pendingAdapterRefresh = false;
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 窗口到点后的补刷。锁按钮弹簧还没收完就继续等 —— 它收尾时会自己再调一次这里。
     */
    private void flushAdapterRefresh() {
        refreshScheduled = false;
        if (lockAnimInFlight || adapter == null) {
            return;
        }
        if (pendingFullRefresh) {
            pendingFullRefresh = false;
            // 整个列表重来一遍（此刻已经出了滑柄形变窗口，不会再被拦下）
            refreshServiceList();
            return;
        }
        if (pendingAdapterRefresh) {
            pendingAdapterRefresh = false;
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 锁定按钮「出现 / 消失」动画：material 1.14 自带的 {@link MaterialFade} + {@link TransitionManager}，
     * 参数全用库默认、一个值都不改 ——
     * · 进场 {@code motionDurationMedium4} = <b>400ms</b> + Emphasized decelerate + 0.8 → 1 缩放
     *   （{@code FadeProvider.setIncomingEndThreshold(0.3f)} + {@code ScaleProvider.setIncomingStartScale(0.8f)}）；
     * · 退场 {@code motionDurationShort3} = <b>150ms</b> + Emphasized accelerate，只淡出不缩放
     *   （{@code ScaleProvider.setScaleOnDisappear(false)}）。
     * 时长/缓动都由库按主题属性下发：{@code TransitionUtils.maybeApplyThemeDuration} 只在
     * {@code transition.getDuration() == -1}（本方法不设时长）时才套主题值 ⇒ 走的就是库默认。
     * <p>
     * 刷新由同一道门闸把关（{@code lockAnimInFlight} + {@link #requestAdapterRefresh()}）：
     * 转场期间列表重绑被攒着，转场收尾才放行，避免重绑把动画切成一帧一顿。
     * <p>
     * 历史（2026-10-02）：这里曾并存过一版手写三弹簧（SpringAnimation，官方 standard token
     * 700/0.9 + 800/1.0，收尾≈300ms）与一版把时长覆写成 250ms（对齐开关滑柄）的库版；
     * 用户最终决定「只保留库默认版、不做修改」，<b>弹簧代码已整段删除</b>
     * （源码与 APK 留档：memory/2026-10-02/lock-anim/）。
     * <p>
     * 为什么 2026-09-19 这版在实机上「一帧都没播」——根因不在它自身：
     * {@code beginDelayedTransition} 记下起点后要等「下一次布局之前」才起步，而当年拨开关的链路
     * （写 Settings → ContentObserver 回调 → notifyDataSetChanged → ListView 重布局）恰好就是
     * 那次布局，转场还没起步就被冲掉了。现在重绑已经被 requestAdapterRefresh() 门闸压住
     * （lockAnimInFlight 期间攒着不刷），所以这里只要把门闸关上、转场结束再放行即可。
     * <p>
     */
    private void animateLockButtonVisibility(MaterialButton lockButton, boolean show) {
        // getParent() 给的是 ViewParent，这里需要 ViewGroup 当转场的场景根。
        ViewParent parent = lockButton.getParent();
        if (!(parent instanceof ViewGroup)) {
            // 拿不到场景根（理论上不会发生）：退回直接设可见性，不动画。
            lockButton.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            return;
        }

        MaterialFade fade = new MaterialFade();
        fade.addTarget(lockButton);

        // 转场期间不许重绑列表，否则这次布局会把还没起步的转场冲掉（就是当年那个 bug）。
        lockAnimInFlight = true;
        fade.addListener(new TransitionListenerAdapter() {
            @Override
            public void onTransitionEnd(@NonNull Transition transition) {
                lockAnimInFlight = false;
                flushAdapterRefresh();
            }

            @Override
            public void onTransitionCancel(@NonNull Transition transition) {
                lockAnimInFlight = false;
                flushAdapterRefresh();
            }
        });

        // 先 beginDelayedTransition 再改可见性：转场自己记录前后两个状态。
        TransitionManager.beginDelayedTransition((ViewGroup) parent, fade);
        lockButton.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * 主列表（layout/item.xml）首尾外沿那 1dp —— 由 ServiceAdapter 每次绑定调用。
     * <p>
     * 卡片自身是「上 1dp + 下 1dp」margin，相邻两项之间因此是 2dp（这正是分段列表要的间隙）；
     * 但同一对 1dp 也出现在第一项的上面与最后一项的下面，于是列表顶部（顶栏下沿）与列表底部
     * 各多出 1dp。这里按位置把外沿那 1dp 去掉，中间的 2dp 保持不变：
     * 首项 topMargin = 0、末项 bottomMargin = 0，其余仍为 1dp。
     * <p>
     * 为什么必须在代码里做：item.xml 只有一份布局、被 ListView 按位置复用，XML 里没法表达
     * "只有第一项不要上边距"。设置弹窗（dialog_settings.xml）不是复用的，五行各自独立成块，
     * 所以那边直接在 XML 里省掉第一行的 layout_marginTop、最后一行的 layout_marginBottom 即可，
     * 不需要走这里。
     * <p>
     * 只在值真的变了时才 setLayoutParams，避免每次绑定都触发一次多余的布局。
     */
    private void applySegmentedEdgeMargins(View card, int position, int count) {
        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) params;
        int oneDp = Math.round(getResources().getDisplayMetrics().density);
        int top = position == 0 ? 0 : oneDp;
        int bottom = position == count - 1 ? 0 : oneDp;
        if (lp.topMargin != top || lp.bottomMargin != bottom) {
            lp.topMargin = top;
            lp.bottomMargin = bottom;
            card.setLayoutParams(lp);
        }
    }

    class ServiceAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return serviceList.size();
        }

        @Override
        public Object getItem(int position) {
            return serviceList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @SuppressLint("InflateParams")
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item, parent, false);
                holder = new ViewHolder();
                holder.cardView = convertView.findViewById(R.id.card_view);
                holder.serviceNameTv = convertView.findViewById(R.id.service_name);
                holder.serviceDescTv = convertView.findViewById(R.id.service_desc);
                holder.serviceIconIv = convertView.findViewById(R.id.service_icon);
                holder.serviceSwitch = convertView.findViewById(R.id.service_switch);
                holder.lockButton = convertView.findViewById(R.id.lock_button);
                holder.listItemLayout = convertView.findViewById(R.id.list_item_layout);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // 官方 M3 Expressive Lists：ListItemLayout 按位置下发 state_first / state_middle / state_last / state_single，
            // ListItemCardView 靠 duplicateParentState 自动切换圆角与容器色
            holder.listItemLayout.updateAppearance(position, getCount());

            // 间距：卡片自带上下各 1dp（相邻两项之间＝2dp）；但外沿那 1dp（第一项上面、最后一项下面）
            // 会让列表顶部与底部各多出 1dp，这里按位置去掉。详见 applySegmentedEdgeMargins()。
            applySegmentedEdgeMargins(holder.cardView, position, getCount());

            AccessibilityServiceInfo info = serviceList.get(position);
            String id = info.getId();
            ComponentName cn = ComponentName.unflattenFromString(id);
            PackageManager pm = getPackageManager();
            AppMeta meta = cn == null ? null : getAppMeta(cn.getPackageName());

            holder.serviceIconIv.setImageResource(android.R.drawable.sym_def_app_icon);
            String title = id;
            CharSequence serviceLabel = info.getResolveInfo() != null
                    ? info.getResolveInfo().loadLabel(pm)
                    : null;
            if (!TextUtils.isEmpty(serviceLabel)) {
                title = serviceLabel.toString();
            } else if (meta != null && meta.found && !TextUtils.isEmpty(meta.label)) {
                title = meta.label.toString();
            }
            if (meta != null && meta.found) {
                Drawable icon = copyIcon(meta.icon);
                if (icon != null) {
                    holder.serviceIconIv.setImageDrawable(icon);
                }
            }

            // 原始的服务描述（用于 dialog），保留读取逻辑
            CharSequence description = info.loadDescription(pm);
            CharSequence fullDescription = TextUtils.isEmpty(description)
                    ? getString(R.string.service_description_fallback)
                    : description;

            // 将列表项中的描述替换为 "由（应用名称）提供"，使用你新增的字符串资源 provided_by
            String providerAppName;
            if (meta != null && meta.found && !TextUtils.isEmpty(meta.label)) {
                providerAppName = meta.label.toString();
            } else if (!TextUtils.isEmpty(title)) {
                providerAppName = title;
            } else {
                providerAppName = getString(R.string.app_name);
            }
            String providerText = getString(R.string.provided_by, providerAppName);

            holder.serviceNameTv.setText(title);
            holder.serviceDescTv.setText(providerText);
            holder.serviceDescTv.setContentDescription(providerText); // 无障碍友好

            boolean isEnabled = isServiceEnabledSnapshot(id);
            boolean isDaemon = DaemonListStore.containsId(daemonListStr, id);
            final String dialogTitle = title;
            final CharSequence dialogDescription = fullDescription;

            holder.cardView.setOnClickListener(v -> showServiceDescriptionDialog(dialogTitle, dialogDescription));

            holder.serviceSwitch.setOnCheckedChangeListener(null);
            holder.serviceSwitch.setChecked(isEnabled);

            // 动画进行中（tag 非空）时不要动它，否则重绑会把淡入淡出掐掉；
            // 动画结束后 tag 会被清掉，届时这里恢复正常。
            if (holder.lockButton.getTag(R.id.lock_button) == null) {
                holder.lockButton.setAlpha(1f);
                holder.lockButton.setScaleX(1f);
                holder.lockButton.setScaleY(1f);
                holder.lockButton.setVisibility(isEnabled ? View.VISIBLE : View.INVISIBLE);
            }
            holder.lockButton.setIconResource(isDaemon ? R.drawable.ic_lock : R.drawable.ic_lock_open);
            holder.lockButton.setContentDescription(
                    getString(isDaemon ? R.string.lock_button_desc_locked : R.string.lock_button_desc_unlocked)
            );

            holder.serviceSwitch.setOnClickListener(v -> {
                // 滑柄从这一刻起自己形变 250ms：记下时刻，这段窗口内不重绑列表
                // （见 requestAdapterRefresh），否则形变尾巴会掉帧。
                lastToggleAt = SystemClock.uptimeMillis();
                if (!PermissionUtils.hasSecureSettingsPermission(MainActivity.this)) {
                    holder.serviceSwitch.setChecked(!holder.serviceSwitch.isChecked());
                    PermissionUtils.showPermissionDialog(MainActivity.this);
                    return;
                }
                if (holder.serviceSwitch.isChecked()) {
                    // 动画先挂上：写设置是 IPC，丢到后台线程，别跟开关自己 250ms 的滑柄动画抢帧
                    animateLockButtonVisibility(holder.lockButton, true);
                    final Context appContext = getApplicationContext();
                    runOffAnimationFrames(() ->
                            AccessibilityUtils.applyServiceToggle(appContext, id, true, true));
                } else {
                    // 同上，先把动画挂起来。
                    animateLockButtonVisibility(holder.lockButton, false);
                    if (isDaemon) {
                        updateDaemonList(id, false);
                    }
                    final Context appContext = getApplicationContext();
                    runOffAnimationFrames(() ->
                            AccessibilityUtils.applyServiceToggle(appContext, id, false, false));
                }
            });

            holder.lockButton.setOnClickListener(v -> {
                if (!PermissionUtils.hasSecureSettingsPermission(MainActivity.this)) {
                    PermissionUtils.showPermissionDialog(MainActivity.this);
                    return;
                }
                boolean newStatus = !DaemonListStore.containsId(daemonListStr, id);
                updateDaemonList(id, newStatus);
                if (newStatus) {
                    startDaemonService();
                    // 保活服务同样是写设置（IPC），一样走后台线程
                    final Context appContext = getApplicationContext();
                    runOffAnimationFrames(() ->
                            AccessibilityUtils.tryEnableKeepAliveService(appContext));
                }
            });
            return convertView;
        }

        private void updateDaemonList(String id, boolean add) {
            daemonListStr = add
                    ? DaemonListStore.addId(daemonListStr, id)
                    : DaemonListStore.removeId(daemonListStr, id);
            sp.edit().putString(AppConstants.KEY_DAEMON_LIST, daemonListStr).apply();
            refreshServiceList();
        }
    }

    static class ViewHolder {
        MaterialCardView cardView;
        ListItemLayout listItemLayout;
        TextView serviceNameTv;
        TextView serviceDescTv;
        ImageView serviceIconIv;
        MaterialSwitch serviceSwitch;
        MaterialButton lockButton;
    }
}
