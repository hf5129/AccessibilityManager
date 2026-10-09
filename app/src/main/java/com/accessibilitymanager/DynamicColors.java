package com.accessibilitymanager;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.material.color.DynamicColorsOptions;

/**
 * 应用入口：只做一件事 —— 按「设置」弹窗里的开关决定要不要启用动态取色。
 * <p>
 * 「动态取色」（Android 12+ 的 Monet / Material You）会按用户壁纸生成整套调色板，
 * 连中性色都带上壁纸的色相；开启后本应用所有 ?attr/color* 都跟着变。因为本应用的配色
 * 全走 M3 token（页面 ?attr/colorSurface、列表行 ?attr/colorSurfaceContainer、
 * 弹窗卡片 ?attr/colorSurfaceContainerHighest…），所以开启后**层级关系原样保留**，
 * 变的只是色相与彩度 —— 不需要改任何布局或样式。真实取值可用 material-color-utilities
 * 验证：surface / containerLow / containerHighest / bright 的 tone 恒为 98|6、96|10、90|22、98|24，
 * 与种子（壁纸）无关。
 * <p>
 * 为什么必须在 Application 里接：
 * {@link com.google.android.material.color.DynamicColors#applyToActivitiesIfAvailable}
 * 的做法是注册 ActivityLifecycleCallbacks，在**每个 Activity 建立主题之前**给它套一层
 * 动态配色 overlay。所以它必须在 Activity 起来之前注册好 —— 放到 MainActivity 里调的话，
 * 第一次进界面拿到的还是静态基准色。
 * <p>
 * 开关是怎么生效的：overlay 是在 Activity 创建时套上去的，所以改完设置需要重建 Activity
 * （MainActivity 在「确认」后检测到该值变化会自己 recreate()）。
 * 关掉开关、或系统是 Android 11 及以下（没有 Monet）时，条件为 false，
 * 一切都保持本应用原来的静态基准配色。
 * <p>
 * ★ 名字与 material 库的类冲突，注意别改回去（2026-10-09 由 App.java 改名，类名 App → DynamicColors）：
 * 本类与 com.google.android.material.color.DynamicColors **同名**。同一文件里
 * 「本包的同名 top-level 类型」优先于 single-type import，所以这里**不能** import 那个库类
 * （import 会与文件内声明的类型撞名，直接编译失败），调用一律写全限定名 ——
 * 见下面 onCreate 里的 {@code com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(...)}。
 * 与此配套：AndroidManifest 的 application android:name 已改成 .DynamicColors。
 */
public class DynamicColors extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        final SharedPreferences sp =
                getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE);

        // 全限定名是必须的：本类就叫 DynamicColors，写 DynamicColors.applyToActivitiesIfAvailable
        // 会解析到本类（本包类型优先于 import），编译不过。
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this,
                new DynamicColorsOptions.Builder()
                        // 只有开关打开时才套动态配色；系统不支持动态取色时库自己会跳过。
                        .setPrecondition((activity, theme) ->
                                sp.getBoolean(AppConstants.KEY_DYNAMIC_COLOR, true))
                        .build());
    }
}
