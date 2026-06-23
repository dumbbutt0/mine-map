package com.map2mc.orbit;

/**
 * One camera keyframe on the orbit path. Fields match ReplayMod's Position keyframe
 * ({@code x,y,z,yaw,pitch,roll}) so the exported table maps 1:1 onto what you type into the
 * ReplayMod GUI.
 *
 * @param x,y,z         camera position (world coords)
 * @param yaw,pitch     look angles (degrees, MC convention) aimed at the build center
 * @param roll          always 0 (we never bank the camera)
 * @param renderTimeMs  when this keyframe occurs on the camera/render timeline
 * @param replayTimeMs  the corresponding position in the recorded build's timeline (so a long build
 *                      compresses onto a short orbit)
 */
public record OrbitKeyframe(
        double x, double y, double z,
        float yaw, float pitch, float roll,
        long renderTimeMs, long replayTimeMs) {
}
