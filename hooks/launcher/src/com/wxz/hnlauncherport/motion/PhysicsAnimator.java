package com.wxz.hnlauncherport.motion;

import android.view.Choreographer;

import com.wxz.hnlauncherport.Port;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal multi-DOF spring physics engine mirroring the V17 MotionEngine
 * behavior used by the return-home animation:
 *   - per-axis spring:  a = -k*(x - target) - c*v
 *   - semi-implicit Euler integration at Choreographer frame rate
 *   - per-axis max speed clamp
 *   - aspect-ratio spring coupling between scaleX/scaleY
 *   - settled when all axes are within their end thresholds
 * Callbacks run on the Choreographer thread (UI thread of the host app).
 */
public final class PhysicsAnimator {

    public interface FrameListener {
        void onFrame(float x, float y, float scaleX, float scaleY, float alpha);
    }

    public interface EndListener {
        void onEnd(boolean canceled);
    }

    private static final class Axis {
        float pos;
        float vel;
        float target;
        float stiffness;
        float damping;
        float maxSpeed;
        float endThreshold;
        boolean settled;

        void step(float dt) {
            // semi-implicit Euler
            float accel = -stiffness * (pos - target) - damping * vel;
            vel += accel * dt;
            if (maxSpeed > 0) {
                if (vel > maxSpeed) vel = maxSpeed;
                if (vel < -maxSpeed) vel = -maxSpeed;
            }
            pos += vel * dt;
            if (Math.abs(pos - target) < endThreshold && Math.abs(vel) < endThreshold * 20f) {
                pos = target;
                vel = 0f;
                settled = true;
            } else {
                settled = false;
            }
        }
    }

    private final Axis x = new Axis();
    private final Axis y = new Axis();
    private final Axis scaleX = new Axis();
    private final Axis scaleY = new Axis();
    private final Axis alpha = new Axis();

    private float aspectTarget;
    private float aspectStiffness;
    private float aspectDamping;

    private final List<FrameListener> frameListeners = new ArrayList<>();
    private final List<EndListener> endListeners = new ArrayList<>();
    private final Choreographer choreographer;
    private long lastFrameNanos;
    private boolean running;
    private float alphaFrom = 1f, alphaTo = 1f;
    private long elapsedNanos;

    public PhysicsAnimator() {
        choreographer = Choreographer.getInstance();
    }

    public void addFrameListener(FrameListener l) {
        frameListeners.add(l);
    }

    public void addEndListener(EndListener l) {
        endListeners.add(l);
    }

    /** Configure a movement axis (positions in px, velocities in px/s). */
    public void setMovement(float fromX, float fromY, float velX, float velY,
            float toX, float toY, float endThreshold) {
        configure(x, fromX, velX, toX, MotionParams.MOVEMENT_STIFFNESS,
                MotionParams.MOVEMENT_DAMPING, MotionParams.MOVEMENT_MAX_SPEED_X, endThreshold);
        configure(y, fromY, velY, toY, MotionParams.MOVEMENT_STIFFNESS,
                MotionParams.MOVEMENT_DAMPING, 0f, endThreshold);
    }

    /** Configure scale axes. Velocity transfer: linear velocity projected onto scale. */
    public void setScale(float fromScaleX, float fromScaleY, float toScaleX, float toScaleY,
            float velScaleX, float velScaleY, float endThreshold) {
        configure(scaleX, fromScaleX, velScaleX, toScaleX, MotionParams.SCALE_STIFFNESS,
                MotionParams.SCALE_DAMPING, 0f, endThreshold);
        configure(scaleY, fromScaleY, velScaleY, toScaleY, MotionParams.SCALE_STIFFNESS,
                MotionParams.SCALE_DAMPING, 0f, endThreshold);
        setAspectSpring(MotionParams.ASPECT_HERTZ, MotionParams.ASPECT_DAMPING);
    }

    /** Keep scaleX/scaleY aspect glued toward the from-aspect during flight. */
    public void setAspectSpring(float hertz, float damping) {
        aspectTarget = scaleX.pos != 0f ? scaleY.pos / scaleX.pos : 1f;
        aspectStiffness = MotionParams.stiffness(hertz);
        aspectDamping = damping;
    }

    public void setAlpha(float from, float to, long durationMs) {
        alphaFrom = from;
        alphaTo = to;
        alpha.pos = from;
        alpha.target = to;
        alpha.stiffness = 0f;
        alpha.damping = 0f;
        alpha.maxSpeed = 0f;
        alpha.endThreshold = 0f;
        // linear time ramp driven by elapsed nanos
    }

    private static void configure(Axis a, float from, float vel, float to,
            float k, float c, float maxSpeed, float threshold) {
        a.pos = from;
        a.vel = vel;
        a.target = to;
        a.stiffness = k;
        a.damping = c;
        a.maxSpeed = maxSpeed;
        a.endThreshold = threshold;
        a.settled = false;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        elapsedNanos = 0;
        lastFrameNanos = 0;
        choreographer.postFrameCallback(frameCallback);
    }

    public void cancel() {
        if (running) {
            running = false;
            choreographer.removeFrameCallback(frameCallback);
            notifyEnd(true);
        }
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) {
                return;
            }
            if (lastFrameNanos == 0) {
                lastFrameNanos = frameTimeNanos;
                choreographer.postFrameCallback(this);
                return;
            }
            float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = frameTimeNanos;
            elapsedNanos += (long) (dt * 1_000_000_000L);
            // clamp dt to avoid explosive steps after long stalls
            if (dt > 0.032f) dt = 0.032f;
            if (dt <= 0f) {
                choreographer.postFrameCallback(this);
                return;
            }

            x.step(dt);
            y.step(dt);
            scaleX.step(dt);
            scaleY.step(dt);
            applyAspectCoupling(dt);

            float alphaNow;
            float durNanos = alphaDurationMs * 1_000_000f;
            if (durNanos > 0) {
                float t = elapsedNanos / durNanos;
                if (t > 1f) t = 1f;
                alphaNow = alphaFrom + (alphaTo - alphaFrom) * t;
            } else {
                alphaNow = alphaTo;
            }

            for (int i = 0; i < frameListeners.size(); i++) {
                try {
                    frameListeners.get(i).onFrame(x.pos, y.pos, scaleX.pos, scaleY.pos, alphaNow);
                } catch (Throwable t) {
                    Port.log("frame listener threw", t);
                }
            }

            if (x.settled && y.settled && scaleX.settled && scaleY.settled) {
                running = false;
                notifyEnd(false);
                return;
            }
            choreographer.postFrameCallback(this);
        }
    };

    private void applyAspectCoupling(float dt) {
        if (aspectStiffness <= 0f) {
            return;
        }
        float ratio = scaleX.pos != 0f ? scaleY.pos / scaleX.pos : 1f;
        float err = ratio - aspectTarget;
        // pull scaleY toward the aspect target lightly
        float corr = -aspectStiffness * err * dt;
        if (corr > 0.02f) corr = 0.02f;
        if (corr < -0.02f) corr = -0.02f;
        scaleY.pos += scaleX.pos * corr * dt * 60f;
    }

    private long alphaDurationMs;

    public void setAlphaDuration(long durationMs) {
        this.alphaDurationMs = durationMs;
    }

    private void notifyEnd(boolean canceled) {
        for (int i = 0; i < endListeners.size(); i++) {
            try {
                endListeners.get(i).onEnd(canceled);
            } catch (Throwable t) {
                Port.log("end listener threw", t);
            }
        }
    }
}
