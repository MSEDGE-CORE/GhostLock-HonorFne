package com.wxz.hnlauncherport.calendar;

import com.wxz.hnlauncherport.Port;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 阻止日历课程表闪退 (KEY_CALENDAR_COURSE_FIX):
 * 日历 8.0.221 的 CourseEditActivity.updateScheduleDisplay 调用
 * ArrayList.getFirst() (Java 21 / API 35 新增), 本机 Android 14 的
 * ArrayList 无此方法 → NoSuchMethodError 闪退 (dropbox 21:28:59 起 13s 连崩 3 次).
 *
 * 修复: 对 CourseEditActivity 的 updateScheduleDisplay / u0 做方法级包裹 —
 * beforeHookedMethod 里 invokeOriginalMethod 调原方法, 异常 (NoSuchMethodError
 * 及一切运行时错误) 吞掉只打日志并返回默认值, 不崩溃.
 * 类/方法加载失败时 log 警告后跳过, 不影响其他 hook. 不 hook 混淆类
 * (h6.g / androidx.core.app.d), 只 hook 未混淆的 CourseEditActivity.
 */
public final class CalendarFix {
    private static final String ACTIVITY_CLS =
            "com.hihonor.calendar.coursetable.ui.activity.CourseEditActivity";

    public static void install(ClassLoader cl) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(ACTIVITY_CLS, cl);
        } catch (Throwable t) {
            Port.log("CalendarFix: CourseEditActivity not found, skip");
            return;
        }
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            String name = m.getName();
            if (!"updateScheduleDisplay".equals(name) && !"u0".equals(name)) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Port.on(Port.KEY_CALENDAR_COURSE_FIX)) {
                        return; // 开关关: 原样执行 (含原生崩溃行为)
                    }
                    try {
                        param.setResult(XposedBridge.invokeOriginalMethod(
                                m, param.thisObject, param.args));
                    } catch (Throwable t) {
                        Port.log("CalendarFix: swallowed " + t.getClass().getName()
                                + ": " + t.getMessage() + " in " + m.getName());
                        param.setResult(defaultValue(m));
                    }
                }
            });
            n++;
            Port.log("CalendarFix: hooked " + name);
        }
        if (n == 0) {
            Port.log("CalendarFix: no target methods found, skip");
        }
    }

    /** 原方法被替换后需返回默认值: 基本类型返回零值, void/引用返回 null. */
    private static Object defaultValue(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt == boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == byte.class) {
            return (byte) 0;
        }
        if (rt == char.class) {
            return (char) 0;
        }
        if (rt == short.class) {
            return (short) 0;
        }
        if (rt == int.class) {
            return 0;
        }
        if (rt == long.class) {
            return 0L;
        }
        if (rt == float.class) {
            return 0f;
        }
        if (rt == double.class) {
            return 0d;
        }
        return null;
    }

    private CalendarFix() {}
}
