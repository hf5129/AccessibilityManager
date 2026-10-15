# 本项目的 R8 规则
#
# 前置事实（已用脚本全工程核过）：本项目**没有任何反射**——
# Class.forName / getDeclaredMethod / getField / newInstance / getIdentifier / loadClass 全部 0 处。
# 因此不需要为"反射入口"加 keep；下面只保留三类：可读性、自定义 View、以及第三方兜底。

# ── 1. 崩溃栈可读（发布包仍能定位到行号，mapping.txt 在 app/build/outputs/mapping/release/）──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 2. 在 layout XML 里按类名实例化的自定义 View ──
# AGP 默认规则已包含这些构造函数，这里显式再写一份：默认规则随 AGP 版本变动时不至于被摘掉。
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ── 3. 系统按清单实例化的组件（R8 默认已按清单保留，这里对无障碍服务再兜一道）──
-keep class com.accessibilitymanager.KeepAliveAccessibilityService { *; }

# ── 4. Shizuku：内部走 binder/反射，AAR 自带 consumer rules，这里再兜底一次 ──
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
