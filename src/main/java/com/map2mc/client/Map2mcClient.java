package com.map2mc.client;

import com.map2mc.Map2mcConfig;
import com.map2mc.film.DirectorState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Client entrypoint for Phase A. The camera override itself lives in
 * {@link com.map2mc.client.mixin.CameraUpdateMixin}; this initializer only manages the clean
 * recording state: while the director is filming, hide the HUD (the "clean camera state" the
 * recording workflow wants), restoring the prior setting when filming ends. Spectator mode is set
 * server-side by {@code DirectorCamera}.
 */
@Environment(EnvType.CLIENT)
public final class Map2mcClient implements ClientModInitializer {

    private boolean wasActive = false;
    private boolean priorHudHidden = false;
    private int priorViewDistance = -1;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient mc) {
        boolean active = DirectorState.active();
        if (active && !wasActive) {
            priorHudHidden = mc.options.hudHidden;
            mc.options.hudHidden = true; // clean recording frame (F1 stays user-overridable)
            // Raise render distance so the build doesn't clip out / vanish into distance fog. (Shader
            // volumetric fog is the user's Iris setting — see RECORDING.md §E — not ours to change.)
            priorViewDistance = mc.options.getViewDistance().getValue();
            mc.options.getViewDistance().setValue(Math.max(priorViewDistance, Map2mcConfig.filmViewDistance));
        } else if (!active && wasActive) {
            mc.options.hudHidden = priorHudHidden;
            if (priorViewDistance > 0) {
                mc.options.getViewDistance().setValue(priorViewDistance);
            }
        }
        wasActive = active;
    }
}
