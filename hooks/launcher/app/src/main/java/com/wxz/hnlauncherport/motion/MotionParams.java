package com.wxz.hnlauncherport.motion;

/**
 * V17 MotionEngine (CloseAnimSceneManager) default parameters, from the
 * decompiled 荣耀桌面 17.0.20.191 analysis:
 *   movement hertz/damping  = 1.8 / 19.68
 *   scale hertz/damping     = 1.8 / 19.68
 *   aspectScale hertz/damp  = 2.45 / 39.2
 *   reverseThrow/overRebound/zeroDistance = 27.68 / -31.17 / 32.69
 *   movementMaxSpeedX       = 10419 px/s
 *   alpha start/end         = 0.58 / 0.83
 * Honor's spring parametrization: stiffness k = (2*PI*hertz)^2,
 * damping coefficient c = damping (same units as the classic path's
 * SpringInterpolator(stiffness, damping, ...) zeta argument).
 */
public final class MotionParams {
    // X / Y / scale share identical hertz and damping so every axis has the
    // same damping ratio zeta = c / (2*sqrt(k)) = 0.85:
    //   k = (2*PI*1.5)^2 = 88.83, sqrt(k) = 9.4248
    //   c = 0.85 * 2 * 9.4248 = 16.02
    // Underdamped (zeta < 1): visible initial-inertia glide + mild overshoot.
    public static final float MOVEMENT_HERTZ = 1.5f;
    public static final float MOVEMENT_DAMPING = 16.02f;
    public static final float SCALE_HERTZ = 1.4f;
    // scale per tuning: hertz 1.4, damping 14:
    //   k = (2*PI*1.4)^2 = 77.37, sqrt(k) = 8.796
    //   zeta = 14 / (2*8.796) = 0.796 (underdamped, moderate overshoot)
    public static final float SCALE_DAMPING = 15f;
    // wfsSyn scale spring (跟手 on, curve never rendered - synthesized v0):
    // per tuning.
    public static final float SCALE_DAMPING_SYN = 16f;
    public static final float ASPECT_HERTZ = 2.45f;
    public static final float ASPECT_DAMPING = 39.2f;
    public static final float REVERSE_THROW = 27.68f;
    public static final float OVER_REBOUND = -31.17f;
    public static final float ZERO_DISTANCE = 32.69f;
    public static final float MOVEMENT_MAX_SPEED_X = 10419f;
    public static final float ALPHA_START = 0.58f;
    public static final float ALPHA_END = 0.83f;

    // Return-to-capsule springs (V17 MotionController.adjustParamInitValue
    // capsule branch :4716-4721 - hardcoded, bypasses CloseAnimSceneManager):
    //   movementDamping 23.86 / movementHertz 2.0
    //   scaleDamping    26.26 / scaleHertz    2.2
    public static final float CAPSULE_MOVEMENT_HERTZ = 0.5f;
    public static final float CAPSULE_MOVEMENT_DAMPING = 5.6f;
    public static final float CAPSULE_SCALE_HERTZ = 0.5f;
    public static final float CAPSULE_SCALE_DAMPING = 4f;
    // Classic capsule value threshold (AnimConstants.sCapsuleValueThresholdReturnHome)
    public static final float CAPSULE_VALUE_THRESHOLD = 0.003f;

    // Swipe-up release into RECENTS (V17 HomeToOverviewModelCreator
    // sSpringBackHertz[1]/sSpringBackDamping[1] - the scene's phase-4
    // model is registered with param 1, i.e. the springback set):
    //   hertz 1.8191284 / linearDamping 15.814455
    //   k = (2*PI*1.8191)^2 = 130.7, zeta = 15.81/(2*sqrt(130.7)) = 0.69
    //   -> underdamped, mild overshoot into the capsule settle.
    public static final float SWIPE_TO_RECENTS_HERTZ = 1.8191284f;
    public static final float SWIPE_TO_RECENTS_DAMPING = 15.814455f;

    public static float stiffness(float hertz) {
        double w = 2.0 * Math.PI * hertz;
        return (float) (w * w);
    }

    public static final float MOVEMENT_STIFFNESS = stiffness(MOVEMENT_HERTZ);  // ~127.9
    public static final float SCALE_STIFFNESS = stiffness(SCALE_HERTZ);        // ~127.9
    public static final float CAPSULE_MOVEMENT_STIFFNESS = stiffness(CAPSULE_MOVEMENT_HERTZ); // ~157.9
    public static final float CAPSULE_SCALE_STIFFNESS = stiffness(CAPSULE_SCALE_HERTZ);       // ~191.0

    private MotionParams() {}
}
