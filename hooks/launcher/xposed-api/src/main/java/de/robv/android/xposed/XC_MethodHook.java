package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result = null;
        private Throwable throwable = null;
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.throwable = null; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable t) { this.throwable = t; this.result = null; }
        public boolean hasThrowable() { return throwable != null; }
        public Object getArg(int index) { return args[index]; }
    }

    public class Unhook {
        public Member getHookedMethod() { return null; }
        public void unhook() {}
    }
}
