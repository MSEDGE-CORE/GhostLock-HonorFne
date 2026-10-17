package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String text) {}
    public static void log(Throwable t) {}

    public static XC_MethodHook.Unhook hookMethod(Member m, XC_MethodHook cb) { return null; }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c, String name, XC_MethodHook cb) { return null; }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> c, XC_MethodHook cb) { return null; }

    public static Object invokeOriginalMethod(Member m, Object thisObj, Object[] args) throws Throwable { return null; }
}
