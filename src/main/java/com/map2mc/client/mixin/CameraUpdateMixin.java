package com.map2mc.client.mixin;

import com.map2mc.film.DirectorState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase A — per-render-frame camera override. When the director is filming (see
 * {@link com.map2mc.film.DirectorCamera}), this replaces the camera each RENDER FRAME with the
 * director pose interpolated by the render partial-tick ({@code tickDelta}), so motion is smooth at
 * render fps instead of juddering at the 20 Hz teleport rate. Injected at the TAIL of
 * {@code Camera.update} so it overrides the vanilla position/rotation the base method just set.
 *
 * <p><b>Chunk-culling safety:</b> {@code DirectorCamera} also teleports the spectator camera-entity
 * to (almost) this same pose every server tick, so the overridden render viewpoint never strays more
 * than one tick of orbit motion from the entity that drives chunk loading and the frustum origin —
 * no extra near-plane pop is expected at the Phase A default radii.
 *
 * <p>{@code setPos}/{@code setRotation} are {@code protected} on {@code Camera}; they're reached via
 * {@code @Shadow} (this mixin is woven into {@code Camera}, so {@code this} IS the camera).
 */
@Environment(EnvType.CLIENT)
@Mixin(Camera.class)
public abstract class CameraUpdateMixin {

    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V",
            at = @At("TAIL"))
    private void map2mc$applyDirector(World area, Entity focused, boolean thirdPerson,
                                      boolean inverseY, float tickDelta, CallbackInfo ci) {
        DirectorState.Snapshot s = DirectorState.snapshot();
        if (s == null) {
            return; // not filming → leave the vanilla camera alone
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !mc.player.getUuid().equals(s.filmingPlayer())) {
            return; // only override the filming player's own view
        }
        if (mc.world == null || !mc.world.getRegistryKey().equals(s.worldKey())) {
            return; // wrong dimension
        }

        DirectorState.Pose p0 = s.prev();
        DirectorState.Pose p1 = s.cur();
        double x = p0.x() + (p1.x() - p0.x()) * tickDelta;
        double y = p0.y() + (p1.y() - p0.y()) * tickDelta;
        double z = p0.z() + (p1.z() - p0.z()) * tickDelta;
        float yaw = MathHelper.lerpAngleDegrees(tickDelta, p0.yaw(), p1.yaw());
        float pitch = p0.pitch() + (p1.pitch() - p0.pitch()) * tickDelta;

        this.setPos(x, y, z);
        this.setRotation(yaw, pitch);
    }
}
