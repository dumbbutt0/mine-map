package com.map2mc.place;

import com.map2mc.schem.Rotation4;
import net.minecraft.util.math.Vec3d;

/**
 * Pure geometry for the player-facing default ({@code /map2mc hero <file>} with no coordinates):
 * where is "in front of where I'm looking", and which way should the build turn to face me.
 *
 * <p>This is the testable seam for the "{@code ~}/{@code ^} resolution" gate. The command itself
 * uses vanilla {@code Vec3ArgumentType} to parse literal {@code ~}/{@code ^} args (trusted), but the
 * no-coordinate default and the "face the player" rotation are our own math — so they live here and
 * are unit-tested against a mock {@link SourcePose} (no Minecraft bootstrap needed; {@link Vec3d} is
 * a plain value type).
 */
public final class LookMath {

    private LookMath() {
    }

    /**
     * A command source reduced to what placement geometry needs. The real
     * {@code ServerCommandSource} adapts into this; tests construct it directly.
     *
     * @param eye   the source position (a player's feet, in practice)
     * @param yaw   horizontal look angle in degrees (MC convention: 0=+Z south, 90=-X west,
     *              180=-Z north, 270=+X east)
     * @param pitch vertical look angle in degrees (-90 up, +90 down)
     */
    public record SourcePose(Vec3d eye, float yaw, float pitch) {
    }

    /**
     * The point {@code distance} blocks ahead along the FULL look vector (the {@code ^ ^ ^distance}
     * local-coordinate semantics, including pitch).
     */
    public static Vec3d frontOfLook(SourcePose p, double distance) {
        double yaw = Math.toRadians(p.yaw());
        double pitch = Math.toRadians(p.pitch());
        double cosPitch = Math.cos(pitch);
        double fx = -Math.sin(yaw) * cosPitch;
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * cosPitch;
        return p.eye().add(fx * distance, fy * distance, fz * distance);
    }

    /**
     * The point {@code distance} blocks ahead along the HORIZONTAL look direction only (pitch
     * ignored). This is what the player-facing default uses, so looking up/down doesn't fling the
     * build into the sky or the ground — it lands on the surface ahead, then ground-snaps.
     */
    public static Vec3d frontOfLookHorizontal(SourcePose p, double distance) {
        double yaw = Math.toRadians(p.yaw());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        return p.eye().add(fx * distance, 0, fz * distance);
    }

    /**
     * The rotation that turns the build to face the player who placed it in front of themselves.
     *
     * <p>Convention: a schematic's "front" is its {@code +Z} (south) face. The build is dropped in
     * the look direction, so to face back toward the camera its front must point opposite the look
     * direction — i.e. rotate by the player's yaw quantized to 90°, plus 180°. Unit-tested as a
     * deterministic yaw→{@link Rotation4} mapping; the visual correctness of the +Z="front"
     * convention is confirmed in-client.
     */
    public static Rotation4 facingRotation(float yaw) {
        int quantized = Math.floorMod(Math.round(yaw / 90f) * 90, 360);
        return Rotation4.fromDegrees(Math.floorMod(quantized + 180, 360));
    }
}
