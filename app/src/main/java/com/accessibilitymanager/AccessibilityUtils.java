package com.accessibilitymanager;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * 无障碍服务工具类
 * <p>
 * 负责解析、读取和写入系统设置中的 ENABLED_ACCESSIBILITY_SERVICES 字符串。
 * 包含开启、关闭指定服务以及静默开启保活组件的逻辑。
 */
public class AccessibilityUtils {
    private static final String TAG = "AccessibilityUtils";
    private static final String SETTING_KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    private static final TextUtils.SimpleStringSplitter COLON_SPLITTER = new TextUtils.SimpleStringSplitter(':');

    /**
     * 获取当前系统已开启的无障碍服务列表
     *
     * @param context 上下文
     * @return 开启的服务 ComponentName 集合
     */
    public static Set<ComponentName> getEnabledServices(Context context) {
        String settingValue = Settings.Secure.getString(context.getContentResolver(), SETTING_KEY);
        Set<ComponentName> enabledServices = new HashSet<>();

        if (TextUtils.isEmpty(settingValue)) {
            return enabledServices;
        }

        COLON_SPLITTER.setString(settingValue);
        while (COLON_SPLITTER.hasNext()) {
            String componentNameString = COLON_SPLITTER.next();
            ComponentName enabledService = ComponentName.unflattenFromString(componentNameString);
            if (enabledService != null) {
                enabledServices.add(enabledService);
            }
        }
        return enabledServices;
    }

    /**
     * 将服务集合写入系统设置 (需要 WRITE_SECURE_SETTINGS 权限)
     *
     * @param context  上下文
     * @param services 要开启的所有服务的集合
     */
    public static void setEnabledServices(Context context, Set<ComponentName> services) {
        StringBuilder sb = new StringBuilder();
        for (ComponentName componentName : services) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(componentName.flattenToString());
        }
        try {
            Settings.Secure.putString(context.getContentResolver(), SETTING_KEY, sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "写入安全设置失败，可能无权限", e);
        }
    }

    /**
     * 判断某个服务是否已开启
     */
    public static boolean isServiceEnabled(Context context, String serviceId) {
        ComponentName target = ComponentName.unflattenFromString(serviceId);
        if (target == null) return false;
        return getEnabledServices(context).contains(target);
    }

    /**
     * 开启指定服务 (追加到现有列表)
     */
    public static void enableService(Context context, String serviceId) {
        ComponentName componentName = ComponentName.unflattenFromString(serviceId);
        if (componentName == null) return;

        Set<ComponentName> enabledServices = getEnabledServices(context);
        if (enabledServices.add(componentName)) {
            setEnabledServices(context, enabledServices);
        }
    }

    /**
     * 关闭指定服务 (从现有列表移除)
     */
    public static void disableService(Context context, String serviceId) {
        ComponentName componentName = ComponentName.unflattenFromString(serviceId);
        if (componentName == null) return;

        Set<ComponentName> enabledServices = getEnabledServices(context);
        if (enabledServices.remove(componentName)) {
            setEnabledServices(context, enabledServices);
        }
    }

    /**
     * 尝试静默开启本应用的保活服务
     * <p>
     * 仅在已有权限且服务未开启时执行。
     */
    public static void tryEnableKeepAliveService(Context context) {
        String serviceName = context.getPackageName() + "/" + KeepAliveAccessibilityService.class.getName();

        if (!isServiceEnabled(context, serviceName)) {
            try {
                enableService(context, serviceName);
                Log.i(TAG, "已自动静默开启保活服务: " + serviceName);
            } catch (Exception e) {
                Log.e(TAG, "自动开启保活服务失败", e);
            }
        }
    }

    /**
     * 一次读完、至多一次写完地应用"开关某个服务，并顺带确保保活服务已开启"。
     * <p>
     * 改前主界面是 {@link #enableService} + {@link #tryEnableKeepAliveService} 两步走，
     * 各自读一次、写一次设置，一次拨动最多 5 次 IPC，而且全压在主线程上——正好压在
     * MaterialSwitch 那 250ms 的滑柄动画（以及锁按钮的形变动画）的帧上，于是看着卡。
     * 这里合并成一次读 + 至多一次写，再由调用方整段丢到后台线程执行。
     *
     * @param serviceId       要开关的服务组件名
     * @param enable          true 开启，false 关闭
     * @param ensureKeepAlive 开启时是否顺带确保保活服务也开着（关闭时不处理，与改前一致）
     * @return true 表示设置确实被改写
     */
    public static boolean applyServiceToggle(Context context, String serviceId, boolean enable,
                                             boolean ensureKeepAlive) {
        ComponentName componentName = ComponentName.unflattenFromString(serviceId);
        if (componentName == null) {
            return false;
        }

        Set<ComponentName> enabledServices = getEnabledServices(context);
        boolean changed = enable
                ? enabledServices.add(componentName)
                : enabledServices.remove(componentName);

        if (ensureKeepAlive) {
            // 保活服务跟主开关共用这一次读，能少一次 IPC
            ComponentName keepAlive = ComponentName.unflattenFromString(
                    context.getPackageName() + "/" + KeepAliveAccessibilityService.class.getName());
            if (keepAlive != null && enabledServices.add(keepAlive)) {
                changed = true;
                Log.i(TAG, "已自动静默开启保活服务: " + keepAlive.flattenToString());
            }
        }

        if (changed) {
            setEnabledServices(context, enabledServices);
        }
        return changed;
    }
}