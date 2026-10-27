package com.google.wear.input;

import java.lang.reflect.Method;

/**
 * 兼容入口：wear-compose-foundation 在「手表 + API 34+」设备上直接引用
 * Google Wear SDK 的 com.google.wear.input.WearHapticFeedbackConstants，
 * 但部分国行手表（如小米手表）的 system/wear-sdk.jar 只提供小米同名类，
 * 缺少该类会导致应用启动即 NoClassDefFoundError。
 *
 * 行为：
 * - 父类加载器里存在真正的 Google 类时（国际版 Wear OS），类加载委托会优先
 *   加载系统实现，本类根本不会被用到；
 * - 仅在系统缺失 Google 类时加载本类：优先反射转调小米 wear-sdk 的同名
 *   方法（真实触感常量），失败则返回 -1（wear-compose 会把 -1 传给
 *   performHapticFeedback，等价于禁用该触感效果，不影响其它震动）。
 *
 * 本类不打包任何设备 SDK，仅做转调；禁止引入其它成员，签名必须保持
 * `static int getXxx()`，与 wear-compose 的调用点一致。
 */
public final class WearHapticFeedbackConstants {

    private static final String XIAOMI_CLASS =
            "com.xiaomi.miwear.input.WearHapticFeedbackConstants";

    /** 无可用常量时的回退值（禁用该触感）。 */
    private static final int NO_HAPTICS = -1;

    private static volatile int itemFocus = Integer.MIN_VALUE;
    private static volatile int scrollTick = Integer.MIN_VALUE;
    private static volatile int scrollLimit = Integer.MIN_VALUE;

    private WearHapticFeedbackConstants() {
    }

    public static int getScrollItemFocus() {
        int v = itemFocus;
        if (v == Integer.MIN_VALUE) {
            itemFocus = v = resolve("getScrollItemFocus");
        }
        return v;
    }

    public static int getScrollTick() {
        int v = scrollTick;
        if (v == Integer.MIN_VALUE) {
            scrollTick = v = resolve("getScrollTick");
        }
        return v;
    }

    public static int getScrollLimit() {
        int v = scrollLimit;
        if (v == Integer.MIN_VALUE) {
            scrollLimit = v = resolve("getScrollLimit");
        }
        return v;
    }

    /** 反射调用小米实现；任何不可用情况都缓存为 -1，避免反复抛错。 */
    private static int resolve(String method) {
        try {
            Class<?> cls = Class.forName(XIAOMI_CLASS);
            Method m = cls.getMethod(method);
            Object result = m.invoke(null);
            if (result instanceof Number) {
                return ((Number) result).intValue();
            }
            return NO_HAPTICS;
        } catch (Throwable t) {
            // 类缺失 / 方法缺失 / 链接错误：按「无该触感」处理
            return NO_HAPTICS;
        }
    }
}
