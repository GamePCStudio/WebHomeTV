package com.fongmi.android.tv.setting;

import android.content.Intent;
import android.provider.Settings;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

public class PlayerSetting {

    public static final int EXO = 0;
    public static final int IJK = 1;
    public static final int MPV = 2;
    public static final int RENDER_SURFACE = 0;
    public static final int RENDER_TEXTURE = 1;
    public static final int MPV_RENDER_OPENGL = 0;
    public static final int MPV_RENDER_VULKAN = 1;

    public static int getPlayer() {
        int player = Prefers.getInt("player", EXO);
        if (isPlayer(player)) return player;
        putPlayer(EXO);
        return EXO;
    }

    public static void putPlayer(int player) {
        Prefers.put("player", sanitizePlayer(player));
    }

    public static boolean isPlayer(int player) {
        return player == EXO || player == IJK || player == MPV;
    }

    public static int sanitizePlayer(int player) {
        return player == IJK || player == MPV ? player : EXO;
    }

    public static int nextPlayer(int player) {
        return switch (sanitizePlayer(player)) {
            case EXO -> IJK;
            case IJK -> MPV;
            default -> EXO;
        };
    }

    public static int getMpvRender() {
        int render = Prefers.getInt("mpv_render", MPV_RENDER_OPENGL);
        return render == MPV_RENDER_VULKAN ? MPV_RENDER_VULKAN : MPV_RENDER_OPENGL;
    }

    public static void putMpvRender(int render) {
        Prefers.put("mpv_render", render == MPV_RENDER_VULKAN ? MPV_RENDER_VULKAN : MPV_RENDER_OPENGL);
    }

    public static boolean isExoEnhanced() {
        return Prefers.getBoolean("exo_4k_compat");
    }

    public static int getRender() {
        return Math.min(Math.max(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE), RENDER_TEXTURE);
    }

    public static void putRender(int render) {
        int value = Math.min(Math.max(render, RENDER_SURFACE), RENDER_TEXTURE);
        Prefers.put("render", value);
        if (isTunnel() && value == RENDER_TEXTURE) Prefers.put("tunnel", false);
    }

    public static int getSize() {
        return Prefers.getInt("size", 2);
    }

    public static void putSize(int size) {
        Prefers.put("size", size);
    }

    public static int getScale() {
        return Prefers.getInt("scale");
    }

    public static void putScale(int scale) {
        Prefers.put("scale", scale);
    }

    public static int getBuffer() {
        return Math.min(Math.max(Prefers.getInt("buffer"), 1), 10);
    }

    public static void putBuffer(int buffer) {
        Prefers.put("buffer", buffer);
    }

    public static int getBackground() {
        return Prefers.getInt("background", 2);
    }

    public static void putBackground(int background) {
        Prefers.put("background", background);
    }

    public static boolean isBackgroundOff() {
        return getBackground() == 0;
    }

    public static boolean isBackgroundOn() {
        return getBackground() == 1 || getBackground() == 2;
    }

    public static boolean isBackgroundPiP() {
        return getBackground() == 2;
    }

    public static float getSpeed() {
        return Math.min(Math.max(Prefers.getFloat("speed", 3), 2), 5);
    }

    public static void putSpeed(float speed) {
        Prefers.put("speed", speed);
    }

    public static float getDefaultSpeed() {
        return Math.min(Math.max(Prefers.getFloat("default_speed", 1.0f), 0.25f), 5.0f);
    }

    public static void putDefaultSpeed(float speed) {
        Prefers.put("default_speed", speed);
    }

    public static boolean isCaption() {
        return Prefers.getBoolean("caption");
    }

    public static void putCaption(boolean caption) {
        Prefers.put("caption", caption);
    }

    public static boolean hasCaption() {
        return new Intent(Settings.ACTION_CAPTIONING_SETTINGS).resolveActivity(App.get().getPackageManager()) != null;
    }

    public static boolean isTunnel() {
        return Prefers.getBoolean("tunnel");
    }

    public static void putTunnel(boolean tunnel) {
        Prefers.put("tunnel", tunnel);
    }

    public static boolean isAudioPrefer() {
        return Prefers.getBoolean("audio_prefer");
    }

    public static void putAudioPrefer(boolean audioPrefer) {
        Prefers.put("audio_prefer", audioPrefer);
    }

    public static boolean isVideoPrefer() {
        return Prefers.getBoolean("video_prefer");
    }

    public static void putVideoPrefer(boolean videoPrefer) {
        Prefers.put("video_prefer", videoPrefer);
    }

    public static boolean isPreferAAC() {
        return Prefers.getBoolean("prefer_aac");
    }

    public static void putPreferAAC(boolean preferAAC) {
        Prefers.put("prefer_aac", preferAAC);
    }

    public static float getSubtitleTextSize() {
        return Prefers.getFloat("subtitle_text_size");
    }

    public static void putSubtitleTextSize(float value) {
        Prefers.put("subtitle_text_size", value);
    }

    public static float getSubtitlePosition() {
        return Prefers.getFloat("subtitle_position");
    }

    public static void putSubtitlePosition(float value) {
        Prefers.put("subtitle_position", value);
    }
}
