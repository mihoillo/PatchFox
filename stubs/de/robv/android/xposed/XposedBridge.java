package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** Compile-only stub of the Xposed API (provided by LSPosed at runtime). */
public class XposedBridge {
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return null;
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    public static void log(String text) {}

    public static void log(Throwable t) {}
}
