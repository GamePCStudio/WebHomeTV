package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/**
 * WebHomeTV.ExoNexio fork: Java-side mirror of the NEXIO player defaults
 * (github.com/johnneerdael/nexio, app/src/main/java/com/nexio/tv/data/local/
 * PlayerSettingsDataStore.kt -> BufferSettings / PlayerSettings).
 *
 * Values are kept in sync with the NEXIO Kotlin data store defaults so the
 * ExoPlayer integration behaves like NEXIO out of the box.
 */
public final class NexioPlayerSettings {

    /** NEXIO BufferSettings.DEFAULT_MIN_BUFFER_MS (20s). */
    public static final int NEXIO_MIN_BUFFER_MS = 20_000;
    /** NEXIO BufferSettings.DEFAULT_MAX_BUFFER_MS (50s). */
    public static final int NEXIO_MAX_BUFFER_MS = 50_000;
    /** NEXIO BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_MS (3s). */
    public static final int NEXIO_BUFFER_FOR_PLAYBACK_MS = 3_000;
    /** NEXIO BufferSettings.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS (5s). */
    public static final int NEXIO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000;
    /** NEXIO BufferSettings.DEFAULT_TARGET_BUFFER_SIZE_MB (350MB, 0 = media3 default). */
    public static final int NEXIO_TARGET_BUFFER_SIZE_MB = 350;
    /** NEXIO BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS (0). */
    public static final int NEXIO_BACK_BUFFER_DURATION_MS = 0;

    private static final String KEY_IEC_PASSTHROUGH = "nexio_iec_passthrough";
    private static final String KEY_DV7_TO_DV81 = "nexio_dv7_to_dv81";
    private static final String KEY_FIREOS_FALLBACK = "nexio_fireos_fallback";

    private NexioPlayerSettings() {
    }

    /**
     * NEXIO experimentalDtsIecPassthroughEnabled, fork default ON.
     *
     * Unlike upstream NEXIO (default off), this fork targets Amlogic/FireOS
     * TV boxes where TrueHD cannot reach the AudioTrack layer at all (EDID
     * negotiation) and DTS-HD MA passthrough fails on the media3 default
     * 4MB AudioTrack allocation. The Kodi-style IEC route is the only working
     * passthrough path on these devices, so it ships enabled.
     */
    public static boolean isIecPassthroughEnabled() {
        return Prefers.getBoolean(KEY_IEC_PASSTHROUGH, true);
    }

    public static void putIecPassthroughEnabled(boolean enabled) {
        Prefers.put(KEY_IEC_PASSTHROUGH, enabled);
    }

    /**
     * NEXIO experimentalDv7ToDv81Enabled default = false (DV7 HEVC HDR10 base
     * layer preferred; DV8.1 realtime conversion is opt-in).
     */
    public static boolean isDv7ToDv81Enabled() {
        return Prefers.getBoolean(KEY_DV7_TO_DV81, false);
    }

    public static void putDv7ToDv81Enabled(boolean enabled) {
        Prefers.put(KEY_DV7_TO_DV81, enabled);
    }

    /**
     * NEXIO fireOsCompatibilityFallbackEnabled default = false.
     * Enables the supervised IEC startup delay workaround on FireOS devices.
     */
    public static boolean isFireOsFallbackEnabled() {
        return Prefers.getBoolean(KEY_FIREOS_FALLBACK, false);
    }

    public static void putFireOsFallbackEnabled(boolean enabled) {
        Prefers.put(KEY_FIREOS_FALLBACK, enabled);
    }

    /** Effective LoadControl buffer durations, aligned with NEXIO defaults. */
    public static int minBufferMs() {
        return NEXIO_MIN_BUFFER_MS;
    }

    public static int maxBufferMs() {
        return NEXIO_MAX_BUFFER_MS;
    }

    public static int bufferForPlaybackMs() {
        return NEXIO_BUFFER_FOR_PLAYBACK_MS;
    }

    public static int bufferForPlaybackAfterRebufferMs() {
        return NEXIO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS;
    }

    /** NEXIO target buffer in bytes, or C.LENGTH_UNSET when 0. */
    public static int targetBufferBytes() {
        int mb = NEXIO_TARGET_BUFFER_SIZE_MB;
        return mb <= 0 ? androidx.media3.common.C.LENGTH_UNSET : mb * 1024 * 1024;
    }

    public static int backBufferDurationMs() {
        return NEXIO_BACK_BUFFER_DURATION_MS;
    }
}
