package com.map2mc.orbit;

import com.map2mc.place.Bounds;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure orbit-path geometry (unit-tested, no Minecraft runtime). Produces a slow circular camera
 * path around a build that always points at its center, rising gently from first to last keyframe,
 * with times that compress the build's real duration onto a short render duration.
 *
 * <p>Angle/look conventions match Minecraft: forward for (yaw,pitch) is
 * {@code (-sin yaw·cos pitch, -sin pitch, cos yaw·cos pitch)}, so yaw 0 faces +Z. Positions sit on
 * a circle of {@code radius} in the XZ plane; {@link #poseAt} is the single source the keyframe
 * table and the live "fly" mode both sample (so they can't drift apart).
 */
public final class Orbit {

    private Orbit() {
    }

    /**
     * Camera pose at orbit fraction {@code frac} in [0,1] (0 = start, 1 = full loop back to start).
     *
     * @param cx,cz       build horizontal center
     * @param aimY        the Y the camera looks at (typically the build's mid-height)
     * @param radius      orbit radius in the XZ plane
     * @param camYStart   camera height at frac 0
     * @param camYEnd     camera height at frac 1 (camYEnd > camYStart = gentle rise)
     * @param renderMs    total camera/render duration
     * @param buildMs     the build's real duration (for the replay-time mapping)
     */
    /**
     * Yaw/pitch (degrees, Minecraft convention) for a camera at {@code (camX,camY,camZ)} looking at
     * {@code (tgtX,tgtY,tgtZ)}. The single source of the look-at math, shared by the orbit and the
     * Phase A director (which aims at the construction frontier rather than the orbit centre).
     *
     * @return {@code {yaw, pitch}}
     */
    public static float[] lookAt(double camX, double camY, double camZ,
                                 double tgtX, double tgtY, double tgtZ) {
        double dx = tgtX - camX, dy = tgtY - camY, dz = tgtZ - camZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, horiz));
        return new float[]{yaw, pitch};
    }

    /**
     * Camera pose on the orbit circle around {@code (cx,cz)} at {@code radius} (height ramping
     * camYStart→camYEnd with {@code frac}), but AIMED at an arbitrary target {@code (aimX,aimY,aimZ)}.
     * This is the generalization the director uses to orbit the build centre while tracking the
     * construction frontier. Pass {@code aim = (cx, aimY, cz)} to look at the orbit centre.
     */
    public static OrbitKeyframe poseAtAiming(double cx, double cz, double radius,
                                             double camYStart, double camYEnd, double frac,
                                             double aimX, double aimY, double aimZ,
                                             long renderMs, long buildMs) {
        double angle = 2.0 * Math.PI * frac;
        double x = cx + radius * Math.cos(angle);
        double z = cz + radius * Math.sin(angle);
        double y = camYStart + (camYEnd - camYStart) * frac;

        float[] look = lookAt(x, y, z, aimX, aimY, aimZ);
        long renderT = Math.round(renderMs * frac);
        long replayT = Math.round(buildMs * frac);
        return new OrbitKeyframe(x, y, z, look[0], look[1], 0f, renderT, replayT);
    }

    /** Camera pose on the orbit, looking at the orbit centre {@code (cx, aimY, cz)}. */
    public static OrbitKeyframe poseAt(double cx, double cz, double aimY, double radius,
                                       double camYStart, double camYEnd, double frac,
                                       long renderMs, long buildMs) {
        return poseAtAiming(cx, cz, radius, camYStart, camYEnd, frac, cx, aimY, cz, renderMs, buildMs);
    }

    /** N keyframes evenly spaced around the full circle (the last closes the loop). */
    public static List<OrbitKeyframe> compute(double cx, double cz, double aimY, double radius,
                                              double camYStart, double camYEnd, int keyframes,
                                              long renderMs, long buildMs) {
        int n = Math.max(2, keyframes);
        List<OrbitKeyframe> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double frac = (double) i / (n - 1);
            out.add(poseAt(cx, cz, aimY, radius, camYStart, camYEnd, frac, renderMs, buildMs));
        }
        return out;
    }

    /** Sensible orbit parameters derived from a build's bounding box. */
    public record Params(double cx, double cz, double aimY, double radius,
                         double camYStart, double camYEnd) {

        /**
         * @param radiusOverride a positive radius to pin, or ≤0 to derive from the build size
         * @param radiusFactor   multiplier on the build's horizontal half-extent when deriving
         */
        public static Params fromBounds(Bounds b, double radiusOverride, double radiusFactor) {
            double cx = (b.minX() + b.maxX() + 1) / 2.0;
            double cz = (b.minZ() + b.maxZ() + 1) / 2.0;
            double baseY = b.minY();
            double topY = b.maxY() + 1.0;
            double span = Math.max(1.0, topY - baseY);
            double aimY = baseY + span * 0.5;            // look at mid-height
            double radius = radiusOverride > 0 ? radiusOverride
                    : Math.max(4.0, b.horizontalRadius() * radiusFactor + span * 0.25);
            double camYStart = baseY + span * 0.30;       // start low-ish
            double camYEnd = topY + span * 0.15;          // rise to just above the top
            return new Params(cx, cz, aimY, radius, camYStart, camYEnd);
        }
    }
}
