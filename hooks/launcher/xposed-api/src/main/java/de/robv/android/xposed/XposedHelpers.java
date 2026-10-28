package de.robv.android.xposed;

import java.lang.reflect.Method;

public final class XposedHelpers {
    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) { return null; }
    public static Class<?> findClassOrNull(String className, ClassLoader classLoader) { return null; }
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) { return null; }

    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static Object getObjectFieldOrNull(Object obj, String fieldName) { return null; }
    public static void setObjectField(Object obj, String fieldName, Object value) {}
    public static Object getStaticObjectField(Class<?> c, String fieldName) { return null; }
    public static void setStaticObjectField(Class<?> c, String fieldName, Object value) {}
    public static Object setAdditionalInstanceField(Object obj, String fieldName, Object value) { return null; }
    public static Object getAdditionalInstanceField(Object obj, String fieldName) { return null; }
    public static int getIntField(Object obj, String fieldName) { return 0; }
    public static void setIntField(Object obj, String fieldName, int value) {}
    public static int getStaticIntField(Class<?> c, String fieldName) { return 0; }
    public static void setStaticIntField(Class<?> c, String fieldName, int value) {}
    public static boolean getBooleanField(Object obj, String fieldName) { return false; }
    public static void setBooleanField(Object obj, String fieldName, boolean value) {}
    public static float getFloatField(Object obj, String fieldName) { return 0; }
    public static void setFloatField(Object obj, String fieldName, float value) {}

    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> c, String methodName, Object... args) { return null; }
    public static Object newInstance(Class<?> c, Object... args) { return null; }

    public static Method findMethodExact(Class<?> c, String name, Object... parameterTypes) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> c, String methodName,
            Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook hookAllMethods(Class<?> c, String methodName, XC_MethodHook cb) { return null; }
}
