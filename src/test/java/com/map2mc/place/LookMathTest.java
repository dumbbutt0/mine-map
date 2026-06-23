package com.map2mc.place;

import com.map2mc.place.LookMath.SourcePose;
import com.map2mc.schem.Rotation4;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for the player-facing geometry. A {@link SourcePose} stands in for a command
 * source (the "mock command source" the gate asks for) so {@code ^ ^ ^N}-style resolution and the
 * face-the-player rotation are verified with no Minecraft bootstrap.
 */
class LookMathTest {

    private static final double EPS = 1e-9;
    private static final Vec3d ORIGIN = new Vec3d(0, 0, 0);

    private static void assertVec(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x, actual.x, EPS, "x");
        assertEquals(expected.y, actual.y, EPS, "y");
        assertEquals(expected.z, actual.z, EPS, "z");
    }

    @Test
    void frontHorizontalFollowsYaw() {
        // MC yaw: 0=+Z, 90=-X, 180=-Z, 270=+X. 10 blocks ahead, pitch ignored.
        assertVec(new Vec3d(0, 0, 10), LookMath.frontOfLookHorizontal(new SourcePose(ORIGIN, 0, 0), 10));
        assertVec(new Vec3d(-10, 0, 0), LookMath.frontOfLookHorizontal(new SourcePose(ORIGIN, 90, 0), 10));
        assertVec(new Vec3d(0, 0, -10), LookMath.frontOfLookHorizontal(new SourcePose(ORIGIN, 180, 0), 10));
        assertVec(new Vec3d(10, 0, 0), LookMath.frontOfLookHorizontal(new SourcePose(ORIGIN, 270, 0), 10));
    }

    @Test
    void frontHorizontalIgnoresPitch() {
        Vec3d flat = LookMath.frontOfLookHorizontal(new SourcePose(ORIGIN, 0, 45), 10);
        assertVec(new Vec3d(0, 0, 10), flat);
    }

    @Test
    void frontOfLookUsesPitch() {
        // pitch +90 = looking straight down -> 10 below; pitch -90 = up -> 10 above.
        assertVec(new Vec3d(0, -10, 0), LookMath.frontOfLook(new SourcePose(ORIGIN, 0, 90), 10));
        assertVec(new Vec3d(0, 10, 0), LookMath.frontOfLook(new SourcePose(ORIGIN, 0, -90), 10));
    }

    @Test
    void frontOfLookOffsetsFromEye() {
        Vec3d p = LookMath.frontOfLook(new SourcePose(new Vec3d(5, 70, -3), 0, 0), 10);
        assertVec(new Vec3d(5, 70, 7), p);
    }

    @Test
    void facingRotationQuantizesYawAndFlips() {
        // build is dropped in the look direction; its +Z "front" must point back at the camera (+180).
        assertEquals(Rotation4.CW180, LookMath.facingRotation(0f));
        assertEquals(Rotation4.CW270, LookMath.facingRotation(90f));
        assertEquals(Rotation4.NONE, LookMath.facingRotation(180f));
        assertEquals(Rotation4.CW90, LookMath.facingRotation(270f));
        // negative + wraparound
        assertEquals(Rotation4.CW90, LookMath.facingRotation(-90f));
        assertEquals(Rotation4.CW180, LookMath.facingRotation(359f)); // ~360 -> 0 -> +180
    }
}
