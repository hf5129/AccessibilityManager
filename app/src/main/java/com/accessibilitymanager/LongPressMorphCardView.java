package com.accessibilitymanager;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.listitem.ListItemCardView;

/**
 * 只有「按住」时圆角才形变的列表卡片；轻点圆角完全不动。
 * <p>
 * 圆角换档由卡片的形状状态列表驱动，而这个类把那份列表换成了自己的
 * （{@code res/xml/app_list_item_shape_state_list.xml}，通过 {@code Widget.App.ListItemCardView.Segmented}
 * 样式生效）：里面删掉了官方的 {@code android:state_pressed} 条目、换成自定义的
 * {@code app:state_longPressMorph}。于是：
 * <ul>
 *   <li>轻点：{@code state_pressed} 一切照旧 —— 水波、状态层全部由框架原生驱动，动画和圆角遮罩
 *       都是系统自己的；但形状列表里没有这一条，所以圆角不动；
 *   <li>按住到长按阈值：把 {@code state_longPressMorph} 并进 drawable state → 形状列表换到
 *       {@code ?attr/listItemShapeAppearancePressed}（即系统本来那一档，弹簧动画也还在）；
 *   <li>抬手：状态撤掉 → 形状恢复成由列表位置（首/中/尾/单）决定的那一档。
 * </ul>
 * 全程不调用 MaterialCardView 的形状 API（那样会把 helper 里的形状状态列表整个替换掉，
 * 之后 material 不再按状态回填，表现为「形变后不还原」），也不屏蔽任何状态。
 * <p>
 * 关于「按住多久才形变」：官方 material 这份形状状态列表里**没有任何时间逻辑**（一按下就换），
 * 而框架决定「多快算按下」的常量是 {@code ViewConfiguration}：TAP_TIMEOUT=100ms、
 * DOUBLE_TAP_TIMEOUT=300ms、LONG_PRESS_TIMEOUT=500ms（且可被系统设置
 * {@code Settings.Secure.LONG_PRESS_TIMEOUT} 覆盖）。这里取 {@code getDoubleTapTimeout()}
 * （官方 300ms）：比一次轻点（通常 ≤150ms）长，轻点绝不形变；又比长按阈值短 40%，手感更快。
 * 计时从「手指按下」起算，所以 ListView 里（框架要等 TAP_TIMEOUT 才下发 pressed）和
 * 普通布局里（立刻下发）手感一致。
 */
public class LongPressMorphCardView extends ListItemCardView {

    /** 与 res/values/attrs.xml 中的 state_longPressMorph 对应。 */
    private static final int[] STATE_LONG_PRESS_MORPH = {R.attr.state_longPressMorph};

    /** 手指按下之后多久允许形变（取框架的双击窗口常量，官方 300ms）。 */
    private final long holdToMorphMs;

    /** 本次触摸按下的时刻（uptimeMillis），用于把计时统一到「手指按下」起算。 */
    private long downAt;

    /** 长按阈值到了吗；到了才把自定义状态并进去。 */
    private boolean morphAllowed;

    private final Runnable allowMorph = () -> {
        morphAllowed = true;
        // drawable state 里多出 state_longPressMorph → 形状状态列表换到「按下」那一档
        refreshDrawableState();
    };

    public LongPressMorphCardView(@NonNull Context context) {
        super(context);
        holdToMorphMs = computeHoldToMorph(context);
    }

    public LongPressMorphCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        holdToMorphMs = computeHoldToMorph(context);
    }

    public LongPressMorphCardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        holdToMorphMs = computeHoldToMorph(context);
    }

    private static long computeHoldToMorph(Context context) {
        return ViewConfiguration.get(context).getDoubleTapTimeout();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downAt = SystemClock.uptimeMillis();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        final int[] state = super.onCreateDrawableState(extraSpace + 1);
        if (morphAllowed) {
            mergeDrawableStates(state, STATE_LONG_PRESS_MORPH);
        }
        return state;
    }

    @Override
    public void setPressed(boolean pressed) {
        if (pressed) {
            if (!morphAllowed) {
                removeCallbacks(allowMorph);
                // 框架在可滚动容器（ListView）里要等 TAP_TIMEOUT 才下发 pressed，在普通布局里
                // 则立刻下发；按「手指按下」折算剩余时间，两处手感才一致。
                long elapsed = downAt > 0 ? SystemClock.uptimeMillis() - downAt : 0;
                postDelayed(allowMorph, Math.max(0, holdToMorphMs - elapsed));
            }
        } else {
            removeCallbacks(allowMorph);
            morphAllowed = false;
            downAt = 0;
        }
        super.setPressed(pressed);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(allowMorph);
        super.onDetachedFromWindow();
    }
}
