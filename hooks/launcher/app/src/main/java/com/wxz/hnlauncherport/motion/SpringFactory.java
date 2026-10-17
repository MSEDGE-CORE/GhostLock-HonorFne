package com.wxz.hnlauncherport.motion;

import com.wxz.hnlauncherport.Port;

import java.lang.reflect.Constructor;

/**
 * Constructs honor's own
 * com.hihonor.dynamicanimation.interpolator.SpringInterpolator inside the
 * launcher process, so the classic V14 pipeline can consume Motion-tuned
 * springs without any code change on its side.
 *
 * Signature (both V14 and V17):
 *   SpringInterpolator(float stiffness, float damping, float endPos,
 *                      float startVelocity, float minVisibleChange)
 */
public final class SpringFactory {

    private static final String SPRING_CLS =
            "com.hihonor.dynamicanimation.interpolator.SpringInterpolator";

    private static Constructor<?> cachedCtor;
    private static boolean ctorResolved;

    public static Object make(ClassLoader cl, float stiffness, float damping,
            float endPos, float startVelocity, float minVisibleChange) {
        Constructor<?> ctor = resolve(cl);
        if (ctor == null) {
            return null;
        }
        try {
            return ctor.newInstance(stiffness, damping, endPos, startVelocity, minVisibleChange);
        } catch (Throwable t) {
            Port.log("spring newInstance failed", t);
            return null;
        }
    }

    private static Constructor<?> resolve(ClassLoader cl) {
        if (ctorResolved) {
            return cachedCtor;
        }
        ctorResolved = true;
        try {
            Class<?> cls = cl.loadClass(SPRING_CLS);
            for (Constructor<?> c : cls.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 5) {
                    boolean allFloat = true;
                    for (Class<?> t : p) {
                        if (t != float.class) {
                            allFloat = false;
                            break;
                        }
                    }
                    if (allFloat) {
                        cachedCtor = c;
                        return c;
                    }
                }
            }
            Port.log("no 5-float ctor on " + SPRING_CLS);
        } catch (Throwable t) {
            Port.log("resolve " + SPRING_CLS + " failed", t);
        }
        return null;
    }

    private SpringFactory() {}
}
