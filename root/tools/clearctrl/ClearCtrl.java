import java.lang.reflect.Method;

// Clears a stale IActivityController registered as monkey
// (mControllerIsAMonkey=true in ATMS), which makes
// ActivityManager.isUserAMonkey() return true forever and breaks
// fingerprint settings/enrollment + lockscreen fingerprint unlock.
// Root required. Run via:
//   CLASSPATH=/data/local/tmp/clearctrl.jar app_process / ClearCtrl
public class ClearCtrl {
    public static void main(String[] args) {
        try {
            // Double reflection to bypass hidden-API restrictions.
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod",
                    String.class, Class[].class);

            Class<?> iatm = (Class<?>) forName.invoke(null, "android.app.IActivityTaskManager");
            Class<?> iac = (Class<?>) forName.invoke(null, "android.app.IActivityController");
            Class<?> atmClass = (Class<?>) forName.invoke(null, "android.app.ActivityTaskManager");

            Method getService = (Method) getDeclaredMethod.invoke(atmClass, new Object[]{"getService", new Class[0]});
            Object service = getService.invoke(null);
            System.out.println("got IActivityTaskManager service");

            Method setController = (Method) getDeclaredMethod.invoke(iatm,
                    new Object[]{"setActivityController", new Class[]{iac, boolean.class}});
            setController.invoke(service, new Object[]{null, Boolean.FALSE});
            System.out.println("setActivityController(null, false) done");
        } catch (Throwable t) {
            System.out.println("FAIL: " + t);
            Throwable c = t.getCause();
            while (c != null) {
                System.out.println("CAUSE: " + c);
                StackTraceElement[] st = c.getStackTrace();
                for (int i = 0; i < st.length && i < 12; i++) {
                    System.out.println("  at " + st[i]);
                }
                c = c.getCause();
            }
        }
    }
}
