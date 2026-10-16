package com.accessibilitymanager;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.animation.TimeInterpolator;
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
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.PathInterpolator;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.transition.Transition;
import androidx.transition.TransitionListenerAdapter;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.listitem.ListItemLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.motion.MotionUtils;
import com.google.android.material.transition.MaterialFade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private List<ServiceRow> serviceList;
    private RecyclerView listView;
    private MoveOnTopItemAnimator listItemAnimator;
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
     * 应用元数据缓存：应用名 / 是否系统应用。
     * <p>
     * PackageManager.getApplicationInfo()、loadLabel() 都是 IPC，而同一份数据在一次刷新里会
     * 被反复取（过滤逐项取一遍、绑定又逐行取一遍）。缓存后每个包只查一次，之后只做内存查表。
     * <p>
     * 图标不在其中：loadIcon() 是最贵的那一项，单独走 iconCache + loadServiceIcon() 懒加载，
     * 只有真正绑到屏幕上的行才去取。
     */
    private final Map<String, AppMeta> appMetaCache =
            Collections.synchronizedMap(new HashMap<String, AppMeta>());

    /**
     * 图标缓存：键＝包名，值＝图标的 ConstantState。
     * <p>
     * 存 ConstantState 而不是 Drawable 实例：同一个包可能对应多个服务（多个条目），
     * 而一个 Drawable 只有一份 callback/state，两个 ImageView 直接共用会互相踩。
     * 每个 ImageView 各自 newDrawable() 一份副本，既省 IPC 又不串状态。
     */
    private final LruCache<String, Drawable.ConstantState> iconCache = new LruCache<>(64);

    /**
     * 后台 IPC 线程池（守护线程，随进程退出）：图标懒加载、服务元数据预热、
     * 「已开启服务」快照读取、桌面图标别名同步、隐藏最近任务，都走这条池子。
     * <p>
     * loadIcon() 是 PackageManager IPC，也是列表里唯一的重活：原先它跟着 getAppMeta()
     * 在主线程上按服务数逐个取，全压在首帧之前。现在只给"真正可见的行"取，且都在这里做。
     */
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "service-icon");
        t.setDaemon(true);
        return t;
    });

    /** 图标淡入的兜底时长：m3_sys_motion_duration_short4 = 200ms（主题里取不到时才用）。 */
    private static final int ICON_FADE_FALLBACK_MS = 200;

    /** 兜底缓动：M3 emphasized decelerate 曲线 cubic-bezier(0.05, 0.7, 0.1, 1.0)。 */
    private static final TimeInterpolator M3_EMPHASIZED_DECELERATE =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);

    /**
     * 列表行 = 服务信息 + 该行当前的显示状态快照。
     * <p>
     * DiffUtil 靠这两个布尔值判断「这一行的内容变了没有」：变了才重绑（内容变化不播交叉淡入，
     * 见 initListView 里的 setSupportsChangeAnimations(false)）；位置变化则交给 item 动画去做。
     */
    private static final class ServiceRow {
        final AccessibilityServiceInfo info;
        final boolean enabled;
        final boolean daemon;

        ServiceRow(AccessibilityServiceInfo info, boolean enabled, boolean daemon) {
            this.info = info;
            this.enabled = enabled;
            this.daemon = daemon;
        }
    }

    /** DiffUtil 回调：身份＝服务 id，内容＝该行的显示状态。 */
    private static final class ServiceRowDiff extends DiffUtil.ItemCallback<ServiceRow> {
        @Override
        public boolean areItemsTheSame(@NonNull ServiceRow oldItem, @NonNull ServiceRow newItem) {
            return oldItem.info.getId().equals(newItem.info.getId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull ServiceRow oldItem, @NonNull ServiceRow newItem) {
            return oldItem.enabled == newItem.enabled && oldItem.daemon == newItem.daemon;
        }
    }

    /**
     * 一个包的应用元数据；found = false 表示查不到这个包。
     * <p>
     * 这里**没有图标**：图标是这条链上最贵的 IPC，不能跟着元数据在主线程同步取，
     * 由 loadServiceIcon() 按需加载。
     */
    private static final class AppMeta {
        final boolean found;
        final CharSequence label;
        final boolean isSystem;

        AppMeta(boolean found, CharSequence label, boolean isSystem) {
            this.found = found;
            this.label = label;
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
        // ② 桌面图标跟随「跟随壁纸取色」：两组图标各挂一个 activity-alias，这里按设置同步启用状态。
        // 同步要 2~4 次 PackageManager IPC，但与首帧无关（图标晚一帧同步没人看得出来），
        // 所以直接丢后台线程 —— 首帧不必等它。写入用的是 DONT_KILL_APP，不会重启本进程。
        iconExecutor.execute(this::syncLauncherIcon);

        // 初始化“隐藏后台”状态
        boolean hideRecents = sp.getBoolean(AppConstants.KEY_HIDE_RECENTS, false);
        if (hideRecents) {
            // ③「最近任务里不出现」与首帧无关，而且等 task 建好之后再设置更确定
            //（onCreate 早期 task 状态还在建立）。getAppTasks()/setExcludeFromRecents
            // 都是 IPC，一并丢后台 —— 这个 API 允许非主线程调用。
            iconExecutor.execute(() -> applyHideFromRecents(true));
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
        // 「设置」入口：原来走溢出菜单（onCreateOptionsMenu + menu/arrange.xml），
        // 菜单里只有这一条、点了也是弹设置弹窗 —— 那层壳只有坏处（还捎带库私有菜单项布局的
        // RtlOverlay 16dp 依赖），所以改成布局里一个普通图标按钮。
        toolbar.findViewById(R.id.action_settings).setOnClickListener(v -> showSettingsDialog());
    }

    /**
     * 配置沉浸式状态栏和导航栏
     */
    private void initImmersiveStatusBar() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 这一处要留着：Q(29) 高于 minSdk 26
            window.setNavigationBarContrastEnforced(false);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        // 确保布局延伸到状态栏和导航栏下方
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        // 浅色模式下让状态栏图标变深色，避免白图标糊在浅色背景上
        if (!isDarkMode()) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private boolean isDarkMode() {
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void initListView() {
        listView = findViewById(R.id.list);
        listView.setLayoutManager(new LinearLayoutManager(this));
        // item 动画用 RecyclerView 自带的 DefaultItemAnimator：新增淡入+位移、删除淡出+收起、
        // 顺序变化走位移动画（点锁定后置顶就是靠它）。关掉「内容变化」的交叉淡入 —— 那在列表里
        // 看着就是闪一下；内容变化直接重绑即可（DiffUtil 只在显示状态真的变了时才判定为变化）。
        listItemAnimator = new MoveOnTopItemAnimator();
        listItemAnimator.setSupportsChangeAnimations(false);
        // 动画落定后只重刷「按位置算的两样东西」（分段圆角 + 边距），不做整表重绑 ——
        // 整表重绑会把可见行的图标、文字、监听器全部再走一遍，落在落定那一帧上会掉帧。
        listItemAnimator.setRebindNeeded(this::refreshPositionDependentAppearance);
        listView.setItemAnimator(listItemAnimator);
        // 让「用户刚操作的那一项」最后绘制 = 画在最上层。RecyclerView 默认按子视图下标绘制
        // （下标小的先画、在下面）：锁定置顶后那一项的下标变成 0，就会被后面的项盖住、看着像
        // 从别人下面穿过；取消锁定是往下移、下标变大，天然画在上面 —— 这个回调把两边拉成一致。
        // 只在它位移期间生效，动画结束就恢复默认顺序。
        listView.setChildDrawingOrderCallback((childCount, drawingPosition) -> {
            RecyclerView.ViewHolder onTop = listItemAnimator.onTopHolder();
            if (onTop == null) {
                return drawingPosition; // 没有正在位移的目标项，保持默认顺序
            }
            int onTopIndex = listView.indexOfChild(onTop.itemView);
            if (onTopIndex < 0) {
                return drawingPosition;
            }
            if (drawingPosition == childCount - 1) {
                return onTopIndex; // 最后一个绘制位留给它
            }
            return drawingPosition < onTopIndex ? drawingPosition : drawingPosition + 1;
        });

        // 服务列表本身是一次 IPC（要到 AccessibilityManagerService 去取），改前是在 onCreate 里
        // 同步取的，首帧得等它。现在先给个空列表把界面搭起来，数据交给 loadServicesAsync()
        // 在后台取 —— 取完连同「每个包的元数据」一起回主线程刷一次，所以这里不再立刻 refresh。
        allServices = new ArrayList<>();

        adapter = new ServiceAdapter();
        listView.setAdapter(adapter);
        loadServicesAsync();
    }

    /**
     * 后台取「已安装的无障碍服务」并预热 per-package 元数据缓存，然后回主线程刷列表。
     * <p>
     * 这里把两笔最贵的 IPC 一起做掉：
     * 1) {@code am.getInstalledAccessibilityServiceList()}：改前在 onCreate 主线程同步执行，压在首帧上；
     * 2) 每个包的 {@code getApplicationInfo()}／{@code loadLabel()}（过滤系统应用要用）和每个服务的
     *    {@code ServiceInfo.loadLabel()}（绑定要用）：改前都是主线程上「首次遇到才算」，一次刷新
     *    就要几十趟 IPC。预热之后，主线程那几趟刷新只查内存里的缓存。
     * <p>
     * 代价：首帧列表是空的，几百毫秒后一次性补齐。
     */
    private void loadServicesAsync() {
        final Context appContext = getApplicationContext();
        // 元数据也是 PackageManager IPC，和图标共用这条池子
        iconExecutor.execute(() -> {
            List<AccessibilityServiceInfo> loaded = new ArrayList<>();
            AccessibilityManager am =
                    (AccessibilityManager) appContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                loaded.addAll(am.getInstalledAccessibilityServiceList());
            }
            for (AccessibilityServiceInfo info : loaded) {
                ComponentName cn = ComponentName.unflattenFromString(info.getId());
                if (cn != null) {
                    getAppMeta(cn.getPackageName()); // 预热：应用名 + 是否系统应用
                }
                getServiceLabel(info); // 预热：服务自身 label
            }
            // 「已开启的服务」快照也在这趟后台里读掉：首帧之后那一帧不用再等这次 IPC
            final List<AccessibilityServiceInfo> result = loaded;
            final Set<ComponentName> snapshot = AccessibilityUtils.getEnabledServices(appContext);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                allServices = result;
                enabledServiceSnapshot = snapshot;
                refreshServiceList();
            });
        });
    }

    /**
     * 重新加载列表：先按“显示系统应用”开关过滤，再按保活名单置顶排序。
     * 规则：非系统应用始终显示；系统应用仅在勾选开关时显示，
     * 但已开启无障碍权限且已锁定（保活名单）的系统应用始终保留。
     */
    private void refreshServiceList() {
        // 滑柄自己的形变窗口内不干这活：过滤排序 + 让列表把可见项全部重绑，整段压在这 250ms
        // 上滑柄就会掉帧。所以攒着，等窗口过去由 flushAdapterRefresh() 再刷 ——
        // 那时 lastToggleAt 已经出了窗口，这里会直接放行。
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
        // 这里**不再读 Settings**：快照改由后台线程读（见 refreshSnapshotThenList /
        // loadServicesAsync / runOffAnimationFrames），主线程只做过滤排序。
        // 读一次 Settings 虽然只是一次 IPC，但要十几毫秒量级，原先正好压在"列表刚出内容"
        // 那一帧上。需要最新快照的三条路径都会先更新快照再调本方法，所以状态不会过期。
        List<ServiceRow> filtered = new ArrayList<>();
        for (AccessibilityServiceInfo info : allServices) {
            if (shouldShowService(info, showSystem)) {
                // 该行的显示状态在这里快照：DiffUtil 靠它决定这一行要不要重绑
                String id = info.getId();
                filtered.add(new ServiceRow(info, isServiceEnabledSnapshot(id),
                        DaemonListStore.containsId(daemonListStr, id)));
            }
        }
        serviceList = filtered;
        sortServices();
        if (adapter != null) {
            requestAdapterRefresh();
        }
    }

    /**
     * 后台读一次「已开启的无障碍服务」快照，再回主线程刷新列表。
     * <p>
     * 用在"系统设置被外部改动"这类必须看到最新状态的路径上：读在后台线程（和图标、元数据
     * 共用同一条池子），主线程只做过滤排序。
     */
    private void refreshSnapshotThenList() {
        final Context appContext = getApplicationContext();
        iconExecutor.execute(() -> {
            final Set<ComponentName> snapshot = AccessibilityUtils.getEnabledServices(appContext);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                enabledServiceSnapshot = snapshot;
                refreshServiceList();
            });
        });
    }

    /** 查快照，不读 Settings。 */
    private boolean isServiceEnabledSnapshot(String serviceId) {
        ComponentName cn = ComponentName.unflattenFromString(serviceId);
        return cn != null && enabledServiceSnapshot.contains(cn);
    }

    /**
     * 服务自身 label 的缓存：键＝服务 id。
     * <p>
     * ServiceInfo.loadLabel() 同样是 IPC（通常等于应用名，但少数服务有独立 label，
     * 所以不能拿 appMetaCache 里的应用名顶替）。改前这行写在 onBindViewHolder 里，
     * 每次绑定都取一次 —— 滚动重绑、每次刷新都算一遍。缓存后一个服务只取一次。
     * 值可能是 null（该服务没有 label），所以用 containsKey 判断"查过没有"。
     */
    private final Map<String, CharSequence> serviceLabelCache =
            Collections.synchronizedMap(new HashMap<String, CharSequence>());

    private CharSequence getServiceLabel(AccessibilityServiceInfo info) {
        String serviceId = info.getId();
        if (serviceLabelCache.containsKey(serviceId)) {
            return serviceLabelCache.get(serviceId);
        }
        CharSequence label = info.getResolveInfo() != null
                ? info.getResolveInfo().loadLabel(getPackageManager())
                : null;
        serviceLabelCache.put(serviceId, label);
        return label;
    }

    /**
     * 取应用元数据（应用名 + 是否系统应用），带缓存。
     * <p>
     * 每个包只在首次遇到时查一次 PackageManager，之后都是查表。
     * 刻意不取图标：那是列表里最贵的 IPC，见 loadServiceIcon()。
     */
    private AppMeta getAppMeta(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return new AppMeta(false, null, false);
        }
        AppMeta cached = appMetaCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        AppMeta meta;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            meta = new AppMeta(true, ai.loadLabel(pm),
                    (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
        } catch (PackageManager.NameNotFoundException e) {
            meta = new AppMeta(false, null, false);
        }
        appMetaCache.put(packageName, meta);
        return meta;
    }

    /**
     * 懒加载一行的应用图标：先摆占位图，命中缓存立刻换真图标，否则丢到后台线程去取。
     * <p>
     * 这是启动速度那次优化的核心一步：图标是列表里唯一的重量级 IPC，而首帧只需要文字。
     * 列表只会绑定真正显示出来的行，所以这里天然只给可见行走一遍；滚动时按需补。
     * <p>
     * 复用防护：把"这一行现在要显示哪个包"记在 ImageView 的 tag 上，后台结果回来时对不上
     * 就丢掉 —— 否则快速滚动时 convertView 被复用，图标会串行。
     */
    private void loadServiceIcon(ImageView iconView, String packageName) {
        if (iconView == null) {
            return;
        }
        // 本行可能刚被复用：把上一行没跑完的淡入收掉并复位 alpha，免得残留半透明
        iconView.animate().cancel();
        iconView.setAlpha(1f);
        // 先占位，再登记本行的归属（复用后 tag 会变，旧任务自然失配）
        iconView.setTag(R.id.service_icon, packageName);
        iconView.setImageResource(android.R.drawable.sym_def_app_icon);
        if (TextUtils.isEmpty(packageName)) {
            return;
        }

        Drawable.ConstantState cached = iconCache.get(packageName);
        if (cached != null) {
            // 命中缓存就直接换上、不淡入：滚动回来时不该再淡一次
            iconView.setImageDrawable(cached.newDrawable(getResources()));
            return;
        }

        iconExecutor.execute(() -> {
            Drawable.ConstantState loaded = null;
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                Drawable icon = ai.loadIcon(pm);
                if (icon != null) {
                    loaded = icon.getConstantState();
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // 包不在了（已卸载/被禁用），保持占位图
            }
            if (loaded == null) {
                return; // 取不到就不必回主线程了
            }
            final Drawable.ConstantState state = loaded;
            mainHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                // 结果回来时这一行可能已经被复用成别的包，对不上就丢弃
                if (!packageName.equals(iconView.getTag(R.id.service_icon))) {
                    return;
                }
                iconCache.put(packageName, state);
                // M3 淡入：图标淡进来，而不是"啪"地跳出来。
                // 时长/缓动都从主题的 M3 token 取（motionDurationShort4 + emphasized decelerate），
                // 主题里没有才回落到上面的标准值。
                iconView.setAlpha(0f);
                iconView.setImageDrawable(state.newDrawable(getResources()));
                iconView.animate()
                        .alpha(1f)
                        .setDuration(MotionUtils.resolveThemeDuration(iconView.getContext(),
                                com.google.android.material.R.attr.motionDurationShort4, ICON_FADE_FALLBACK_MS))
                        .setInterpolator(MotionUtils.resolveThemeInterpolator(iconView.getContext(),
                                com.google.android.material.R.attr.motionEasingEmphasizedDecelerateInterpolator, M3_EMPHASIZED_DECELERATE))
                        .start();
            });
        });
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
        // 锁定（在保活名单里）的置顶。Collections.sort 是稳定排序，所以其余项保持原有顺序 ——
        // 这一点对动画很重要：只有被锁的那一项位置变了，DiffUtil 就只报一个 move，
        // 动画干净利落，不会整列乱跳。
        Collections.sort(serviceList, (o1, o2) -> Boolean.compare(o2.daemon, o1.daemon));
    }

    private void initSettingsObserver() {
        settingsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                if (adapter != null) {
                    // 外部（系统/别的应用）动了设置：先在后台读一次最新快照再刷
                    refreshSnapshotThenList();
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                true, settingsObserver);
    }

    private void initShizukuListener() {
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
     * 写完**在同一后台线程**回读一次快照，再回主线程刷新列表（读取不再落回主线程）。
     * <p>
     * 原来这些 IPC 都直接跑在拨动那一帧上，正好跟 MaterialSwitch 250ms 的滑柄动画、
     * 锁按钮的形变动画抢帧——动画丢帧看着就是卡。挪到后台后动画帧就干净了；
     * 写完回主线程刷新的另一个好处是：万一写入失败（没权限），界面也能被纠正回真实状态。
     * （回主线程时若仍在滑柄形变窗口里，这次刷新会被 refreshServiceList() 拦下，等窗口过去再补。）
     */
    private void runOffAnimationFrames(@NonNull final Runnable settingsWork) {
        final Context appContext = getApplicationContext();
        final Runnable work = () -> {
            settingsWork.run();
            // 写完设置就地（仍在后台线程）读一次快照：这样界面上看到的就是写入后的真实状态，
            // 而回主线程那一趟只做过滤排序，不再有 IPC 落在动画/首帧上。
            final Set<ComponentName> snapshot = AccessibilityUtils.getEnabledServices(appContext);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    enabledServiceSnapshot = snapshot;
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

        Intent intent = new Intent(this, DaemonService.class);
        startForegroundService(intent);
    }

    /**
     * 设置应用是否从最近任务列表中隐藏
     */
    private void applyHideFromRecents(boolean hide) {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // quitSafely：已经排进队列的那次设置写入仍会执行完，不丢用户刚拨的状态
        // 图标后台加载：已排队的任务不再有意义（界面都要没了），直接丢掉
        iconExecutor.shutdownNow();
        if (toggleThread != null) {
            toggleThread.quitSafely();
        }
        if (settingsObserver != null) {
            getContentResolver().unregisterContentObserver(settingsObserver);
        }
        if (shizukuPermissionListener != null) {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        }
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
     * .LauncherStatic），再按设置启用其中一个。
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
        setLauncherAliasEnabled(".LauncherStatic", !dynamicColor);
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

    /**
     * 取服务描述，只在用户点开弹窗时才调用。
     * <p>
     * AccessibilityServiceInfo.loadDescription() 要走 PackageManager 读该服务在清单里的
     * meta-data，是实打实一次 IPC + XML 解析。原先它在 getView 里每绑一行就取一次，
     * 而结果显示出来根本用不到（只有点击弹窗才用）—— 这是启动路径上最后一个逐行 IPC。
     * 挪到点击时做，代价只是弹窗晚几毫秒，看不出来。
     */
    private CharSequence loadServiceDescription(AccessibilityServiceInfo info) {
        CharSequence description = info.loadDescription(getPackageManager());
        return TextUtils.isEmpty(description)
                ? getString(R.string.service_description_fallback)
                : description;
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
    // 用户要求先试 250（= 库内时长本身，不留余量）。若掉帧时偶发"掰回"，就改回 260（250 + 10ms）。
    private static final long SWITCH_MORPH_MS = 250L;

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
    /** 本次"攒着"从什么时候开始：总推迟时间不超过 MAX_REFRESH_DEFER_MS。 */
    private long pendingSinceAt;
    /** 推迟上限：外部改动风暴时也不能永远不刷。 */
    private static final long MAX_REFRESH_DEFER_MS = 1000L;
    /** 锁按钮转场的序号：兜底放行只对"自己那一次"生效。 */
    private int lockAnimToken;
    /** 转场兜底放行的时限：进场 400ms + 余量。 */
    private static final long LOCK_ANIM_FALLBACK_MS = 500L;

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
            submitServiceList();
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
            submitServiceList();
        }
    }

    /**
     * 把当前 serviceList 交给 ListAdapter：DiffUtil 算出新增 / 删除 / 移动，RecyclerView 据此播动画。
     * <p>
     * 「补刷」不在这里做 —— 时机交给动画器（见 MoveOnTopItemAnimator#setRebindNeeded）：
     * 卡片的圆角与边距是按位置算的（ListItemLayout 的 state_first/middle/last/single +
     * applySegmentedEdgeMargins），而被移动的行不会自动重绑，必须在动画落定之后补一次。
     * 早先在这里用 ItemAnimator.isRunning(listener) 等动画，是错的：提交那一刻动画还没排上，
     * listener 会立刻触发，反而把「要置顶的那一项」提前清空了。
     */
    private void submitServiceList() {
        if (adapter == null) {
            return;
        }
        // 这次提交会不会改掉某个**可见行**的开关 checked？会的话先等滑柄形变跑完再落地。
        // 用户自己拨动时 lastToggleAt 已经记过；守护进程/系统改状态时没人记过，
        // 于是重绑会直接打在形变中的滑柄上 —— 就是"关掉的一瞬间闪一下又弹回开启"。
        if (visibleSwitchWillChange()) {
            long now = SystemClock.uptimeMillis();
            long morphLeft = SWITCH_MORPH_MS - (now - lastToggleAt);
            if (pendingSinceAt == 0L) {
                pendingSinceAt = now;
                if (morphLeft <= 0) {
                    // 外部改动：没人记过时刻，就记成"刚刚"，让这一次提交自己等满一个形变时长
                    lastToggleAt = now;
                    morphLeft = SWITCH_MORPH_MS;
                }
            }
            long capLeft = MAX_REFRESH_DEFER_MS - (now - pendingSinceAt);
            if (morphLeft > 0 && capLeft > 0) {
                pendingAdapterRefresh = true;
                if (!refreshScheduled) {
                    refreshScheduled = true;
                    mainHandler.postDelayed(this::flushAdapterRefresh, Math.min(morphLeft, capLeft));
                }
                return;
            }
        }
        pendingSinceAt = 0L;
        adapter.submitList(new ArrayList<>(serviceList));
    }

    /**
     * 这次提交会不会改变某个**可见行**的开关 checked 状态。
     * <p>
     * 用来把"重绑打断滑柄形变"覆盖到用户拨动之外的情况。屏幕外的行不算 ——
     * 它们下次绑定时自然会拿到新值，不值得为它们推迟整表。
     */
    private boolean visibleSwitchWillChange() {
        if (listView == null || serviceList == null) {
            return false;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child == null) {
                continue;
            }
            RecyclerView.ViewHolder holder = listView.getChildViewHolder(child);
            if (!(holder instanceof ViewHolder)) {
                continue;
            }
            ViewHolder vh = (ViewHolder) holder;
            for (ServiceRow row : serviceList) {
                if (row.info.getId().equals(vh.serviceId)) {
                    if (vh.serviceSwitch.isChecked() != row.enabled) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
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
        int target = show ? View.VISIBLE : View.INVISIBLE;
        // getParent() 给的是 ViewParent，这里需要 ViewGroup 当转场的场景根。
        ViewParent parent = lockButton.getParent();
        if (!(parent instanceof ViewGroup)) {
            // 拿不到场景根（理论上不会发生）：退回直接设可见性，不动画。
            lockButton.setVisibility(target);
            return;
        }
        // 这里刻意**不**做"可见性已等于目标就跳过"的判断：
        // 跳过会让上一次还在跑的转场继续跑到底、把可见性设成相反状态（实机现象：快速连点后
        // 开关是开、锁按钮却不见了），而且跳过期间观感是"一闪一闪"而不是连贯。
        // 照常起新转场即可 —— androidx 会先 cancel 旧转场，新转场从当前插值位置接着走，
        // 观感与快速点击开关一致。

        MaterialFade fade = new MaterialFade();
        fade.addTarget(lockButton);

        // 转场期间不许重绑列表，否则这次布局会把还没起步的转场冲掉（就是当年那个 bug）。
        // 同时在按钮上打 tag：onBindViewHolder 里那道守卫据此跳过 alpha/scale/可见性的重置，
        // 免得重绑把还没播完的淡入淡出掐掉（转场收尾时在 releaseLockAnimGate() 里清掉）。
        lockButton.setTag(R.id.lock_button, Boolean.TRUE);
        lockAnimInFlight = true;
        final int token = ++lockAnimToken;
        fade.addListener(new TransitionListenerAdapter() {
            @Override
            public void onTransitionEnd(@NonNull Transition transition) {
                releaseLockAnimGate(token, lockButton);
            }

            @Override
            public void onTransitionCancel(@NonNull Transition transition) {
                releaseLockAnimGate(token, lockButton);
            }
        });

        // 兜底：万一 end/cancel 都没来（空转场、该行被回收等），门闸会永久关着，
        // 那列表就再也不刷新了（"锁定状态点不动"就是这个）。最迟强制放行一次。
        mainHandler.postDelayed(() -> releaseLockAnimGate(token, lockButton), LOCK_ANIM_FALLBACK_MS);

        // 先 beginDelayedTransition 再改可见性：转场自己记录前后两个状态。
        TransitionManager.beginDelayedTransition((ViewGroup) parent, fade);
        lockButton.setVisibility(target);
    }

    /**
     * 放行锁按钮转场的门闸。带 token：只有"当前那一次"转场能放行，
     * 避免旧转场的回调或兜底把新转场的门闸提前打开。
     */
    private void releaseLockAnimGate(int token, MaterialButton lockButton) {
        if (token != lockAnimToken) {
            return;
        }
        lockButton.setTag(R.id.lock_button, null);
        if (!lockAnimInFlight) {
            return;
        }
        lockAnimInFlight = false;
        flushAdapterRefresh();
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

    /**
     * 动画落定后的轻量补刷：只把「按位置算的两样东西」重刷一遍 —— 分段圆角
     * （{@code ListItemLayout.updateAppearance} 下发的 state_first/middle/last/single）与顶/底边距。
     * <p>
     * 为什么需要：被移动的行不会自动重绑，而这两样都是按位置算的，不补就会停在旧位置那一档。
     * 为什么不直接 {@code notifyItemRangeChanged}：那会把可见行整行重绑（图标、文字、监听器全重来），
     * 压在动画落定那一帧上会明显掉帧；这里只碰这两样，代价小得多。
     * 只处理当前可见的行 —— 移出屏幕的行下次绑定时自然会算对。
     */
    private void refreshPositionDependentAppearance() {
        if (listView == null || adapter == null) {
            return;
        }
        final int count = adapter.getItemCount();
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child == null) {
                continue;
            }
            int position = listView.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) {
                continue;
            }
            ListItemLayout itemLayout = child.findViewById(R.id.list_item_layout);
            if (itemLayout != null) {
                itemLayout.updateAppearance(position, count);
            }
            View card = child.findViewById(R.id.card_view);
            if (card != null) {
                applySegmentedEdgeMargins(card, position, count); // 内部只在真的变了时才设 LayoutParams
            }
        }
    }

    /**
     * 列表适配器：ListAdapter + DiffUtil。增删/移动的动画由 RecyclerView 的 item 动画负责。
     */
    class ServiceAdapter extends ListAdapter<ServiceRow, ViewHolder> {

        ServiceAdapter() {
            super(new ServiceRowDiff());
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View item = LayoutInflater.from(MainActivity.this).inflate(R.layout.item, parent, false);
            return new ViewHolder(item);
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            super.onViewRecycled(holder);
            // 这一行要拿去绑别的服务了：把"转场中"标记清掉，
            // 否则新的一行会因为守卫而永远不重置 alpha/scale/可见性。
            holder.lockButton.setTag(R.id.lock_button, null);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ServiceRow row = getItem(position);
            // 官方 M3 Expressive Lists：ListItemLayout 按位置下发 state_first / state_middle / state_last / state_single，
            // ListItemCardView 靠 duplicateParentState 自动切换圆角与容器色
            holder.listItemLayout.updateAppearance(position, getItemCount());

            // 间距：卡片自带上下各 1dp（相邻两项之间＝2dp）；但外沿那 1dp（第一项上面、最后一项下面）
            // 会让列表顶部与底部各多出 1dp，这里按位置去掉。详见 applySegmentedEdgeMargins()。
            applySegmentedEdgeMargins(holder.cardView, position, getItemCount());

            AccessibilityServiceInfo info = row.info;
            String id = info.getId();
            holder.serviceId = id;
            ComponentName cn = ComponentName.unflattenFromString(id);
            AppMeta meta = cn == null ? null : getAppMeta(cn.getPackageName());

            // 图标懒加载：只摆占位图 + 挂后台任务，首帧不必等 PackageManager（见 loadServiceIcon）
            loadServiceIcon(holder.serviceIconIv, cn == null ? null : cn.getPackageName());
            String title = id;
            CharSequence serviceLabel = getServiceLabel(info);
            if (!TextUtils.isEmpty(serviceLabel)) {
                title = serviceLabel.toString();
            } else if (meta != null && meta.found && !TextUtils.isEmpty(meta.label)) {
                title = meta.label.toString();
            }
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

            // 这两个状态在构建列表时就快照进 ServiceRow 了（DiffUtil 靠它们判断这一行要不要重绑）
            boolean isEnabled = row.enabled;
            boolean isDaemon = row.daemon;
            final String dialogTitle = title;

            // 服务描述只有点开弹窗时才用得到：改成点击那一刻才去读（见 loadServiceDescription）
            holder.cardView.setOnClickListener(v -> showServiceDescriptionDialog(dialogTitle,
                    loadServiceDescription(info)));

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
                // 滑柄从这一刻起自己形变 SWITCH_MORPH_MS：记下时刻，这段窗口内不重绑列表
                // （见 requestAdapterRefresh），否则形变尾巴会掉帧。
                lastToggleAt = SystemClock.uptimeMillis();
                // 同时把"推迟上限"重新起算：那个上限是给"外部改动风暴"兜底的，
                // 不该用来切掉用户这一次的形变 —— 否则外部改动刚过、上限快耗尽时拨动，
                // 提交会正好落在形变中间，看着就是"第一次变快了"。
                pendingSinceAt = 0L;
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
        }

        private void updateDaemonList(String id, boolean add) {
            daemonListStr = add
                    ? DaemonListStore.addId(daemonListStr, id)
                    : DaemonListStore.removeId(daemonListStr, id);
            sp.edit().putString(AppConstants.KEY_DAEMON_LIST, daemonListStr).apply();
            // 这一行马上要换位置（锁定置顶 / 取消锁定落回原位）：告诉动画器，位移时把它画在最上层
            if (listItemAnimator != null) {
                listItemAnimator.expectOnTop(id);
            }
            refreshServiceList();
        }
    }

    /**
     * {@link DefaultItemAnimator} 的子类，只多做一件事：认出「用户刚操作过的那一项」，
     * 好让 RecyclerView 把它画在最上层（见 initListView 里的绘制顺序回调）。
     * <p>
     * 为什么只认那一项：列表整体上移时，被顶下去的行**也会**触发 animateMove（它们在滑动），
     * 若把"正在位移的行"统统置顶，反而会让这一项被那些顺带滑动的行盖住 —— 实测就是
     * "取消锁定后先从第一个选项下面过去"。所以这里按服务 id 认准它。
     */
    private static class MoveOnTopItemAnimator extends DefaultItemAnimator {

        /** 用户刚操作过的那一行（服务 id）；由 updateDaemonList() 设置。 */
        private String expectedServiceId;

        /** 该项正在位移时对应的 ViewHolder（要在绘制顺序里排到最后）。 */
        private RecyclerView.ViewHolder onTopHolder;

        void expectOnTop(String serviceId) {
            expectedServiceId = serviceId;
        }

        @Override
        public boolean animateMove(@NonNull RecyclerView.ViewHolder holder,
                                   int fromX, int fromY, int toX, int toY) {
            boolean started = super.animateMove(holder, fromX, fromY, toX, toY);
            if (started) {
                // 在动画真正开始前的这一帧就认出来，避免开头一帧画错层
                captureIfExpected(holder);
            }
            return started;
        }

        @Override
        public void onMoveStarting(@NonNull RecyclerView.ViewHolder item) {
            super.onMoveStarting(item);
            captureIfExpected(item);
        }

        @Override
        public void onMoveFinished(@NonNull RecyclerView.ViewHolder item) {
            super.onMoveFinished(item);
            if (onTopHolder == item) {
                // 这一项已经落位，期望值用完即清（下次用户操作会重新设置）
                onTopHolder = null;
                expectedServiceId = null;
            }
        }

        private void captureIfExpected(RecyclerView.ViewHolder item) {
            if (expectedServiceId != null
                    && item instanceof ViewHolder
                    && expectedServiceId.equals(((ViewHolder) item).serviceId)) {
                onTopHolder = item;
            }
        }

        /** 动画落定后的补刷动作（由 MainActivity 提供）：让按位置算的圆角与边距重算一次。 */
        private Runnable rebindNeeded;
        private boolean rebindPending;

        void setRebindNeeded(Runnable action) {
            rebindNeeded = action;
        }

        @Override
        public void onAnimationFinished(@NonNull RecyclerView.ViewHolder item) {
            super.onAnimationFinished(item);
            if (rebindPending || rebindNeeded == null) {
                return; // 同一批动画会结束很多次，合并成一次补刷
            }
            rebindPending = true;
            // 推到下一帧：补刷若和"落地"那一帧挤在一起会掉帧（实测落地前一刻会卡一下）
            item.itemView.post(() -> {
                rebindPending = false;
                rebindNeeded.run();
            });
        }

        /** 要画在最上层的那一项；没有位移时为 null。 */
        RecyclerView.ViewHolder onTopHolder() {
            return onTopHolder;
        }
    }

    /** RecyclerView 的行视图持有者。 */
    static class ViewHolder extends RecyclerView.ViewHolder {

        /** 这一行绑定的服务 id：绘制顺序回调据此认出"用户刚操作的那一项"。 */
        String serviceId;

        final MaterialCardView cardView;
        final ListItemLayout listItemLayout;
        final TextView serviceNameTv;
        final TextView serviceDescTv;
        final ImageView serviceIconIv;
        final MaterialSwitch serviceSwitch;
        final MaterialButton lockButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.card_view);
            listItemLayout = itemView.findViewById(R.id.list_item_layout);
            serviceNameTv = itemView.findViewById(R.id.service_name);
            serviceDescTv = itemView.findViewById(R.id.service_desc);
            serviceIconIv = itemView.findViewById(R.id.service_icon);
            serviceSwitch = itemView.findViewById(R.id.service_switch);
            lockButton = itemView.findViewById(R.id.lock_button);
        }
    }
}
