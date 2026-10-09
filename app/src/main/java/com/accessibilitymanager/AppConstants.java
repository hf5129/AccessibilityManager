package com.accessibilitymanager;

/**
 * 全局常量管理类
 * <p>
 * 用于统一管理 SharedPreferences 的 Key、文件名以及其他静态配置，
 * 避免硬编码导致的拼写错误，方便团队协作与维护。
 */
public class AppConstants {
    /**
     * SharedPreferences 文件名
     */
    public static final String PREFS_NAME = "app_config";

    /**
     * 保活列表 Key (格式: "pkg/cls:pkg2/cls2:")
     */
    public static final String KEY_DAEMON_LIST = "daemon_list";

    /**
     * 是否开机自启 Key
     */
    public static final String KEY_AUTO_BOOT = "auto_boot";

    /**
     * 是否显示保活Toast Key
     */
    public static final String KEY_SHOW_TOAST = "show_toast";

    /**
     * 是否隐藏后台 Key
     */
    public static final String KEY_HIDE_RECENTS = "hide_recents";

    /**
     * 是否显示系统应用 Key
     */
    public static final String KEY_SHOW_SYSTEM_APPS = "show_system_apps";

    /**
     * 是否跟随壁纸取色（动态取色 / Monet / Material You）Key
     * <p>
     * 默认 true：Android 12+ 上开启，未开启或系统不支持时自动回落成静态基准色（浅 #FEF7FF / 深 #141218）。
     * 由 DynamicColors（Application 子类）在每次 Activity 建主题前读取，改动后需重建 Activity 才生效。
     */
    public static final String KEY_DYNAMIC_COLOR = "dynamic_color";
}