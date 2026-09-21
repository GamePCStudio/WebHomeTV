package com.fongmi.android.tv.player.exo;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;

import com.fongmi.android.tv.setting.NexioPlayerSettings;
import com.github.catvod.crawler.SpiderDebug;

/**
 * WebHomeTV.ExoNexio fork: integration layer between the NEXIO player defaults
 * (github.com/johnneerdael/nexio + github.com/johnneerdael/media) and the
 * WebHomeTV ExoPlayer pipeline.
 *
 * <p>Scope, mirroring how the base project already consumes NEXIO technology
 * (ExoDv5* ports of the NEXIO media fork): the upstream fork is a full Media3
 * source tree that cannot be composite-built against this Gradle 9/AGP 9
 * project, so instead the NEXIO behaviour is reproduced through the stable
 * Media3 seams:
 * <ul>
 *   <li>LoadControl defaults from NEXIO BufferSettings (20s/50s/3s/5s/350MB/0s).</li>
 *   <li>Kodi-style IEC 61937 compressed audio direct route via
 *       {@link ExoCompressedAudioDirectPolicy} (vendor direct configs + DTS/AC3/E-AC3/TrueHD masquerade),
 *       equivalent to NEXIO's experimentalDtsIecPassthroughEnabled on the
 *       FireOS port of media3.</li>
 *   <li>DV7 -> P8.1 realtime RPU rewrite is provided by the base DV pipeline
 *       (libdovi mode 2 in ExoDv5Native); {@link #isDv7ToDv81Requested()} only
 *       reports the NEXIO toggle so the DV path can honour it.</li>
 * </ul>
 */
public final class ExoNexioIntegration {

    private static volatile boolean active = true;

    private ExoNexioIntegration() {
    }

    /** Master switch for the NEXIO integration on this fork. */
    public static boolean isActive() {
        return active;
    }

    /**
     * Build the NEXIO-aligned LoadControl used instead of the stock one when
     * the integration is active. Values mirror NEXIO BufferSettings defaults
     * (min 20s / max 50s / start 3s / rebuffer 5s / target 350MB / back 0s).
     */
    public static LoadControl buildNexioLoadControl() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        NexioPlayerSettings.minBufferMs(),
                        NexioPlayerSettings.maxBufferMs(),
                        NexioPlayerSettings.bufferForPlaybackMs(),
                        NexioPlayerSettings.bufferForPlaybackAfterRebufferMs())
                .setTargetBufferBytes(NexioPlayerSettings.targetBufferBytes())
                .setBackBuffer(NexioPlayerSettings.backBufferDurationMs(), false)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
    }

    /**
     * NEXIO experimentalDtsIecPassthroughEnabled: prefer the Kodi-style IEC
     * packed direct route for compressed audio when the user enables it.
     */
    public static boolean isIecPassthroughEnabled() {
        return NexioPlayerSettings.isIecPassthroughEnabled();
    }

    /** NEXIO fireOsCompatibilityFallbackEnabled toggle. */
    public static boolean isFireOsFallbackEnabled() {
        return NexioPlayerSettings.isFireOsFallbackEnabled();
    }

    /** NEXIO experimentalDv7ToDv81Enabled toggle (default off, opt-in). */
    public static boolean isDv7ToDv81Requested() {
        return NexioPlayerSettings.isDv7ToDv81Enabled();
    }

    /**
     * True when the NEXIO FireOS IEC workaround should masquerade TrueHD as
     * DTS for the Amlogic HAL content sniffing, matching the base
     * ExoPassthroughAudioSink approach used on the N1 fork.
     */
    public static boolean needsVendorDirectMasquerade(@Nullable Format format) {
        if (!isIecPassthroughEnabled() || format == null) return false;
        String mime = format.sampleMimeType;
        return MimeTypes.AUDIO_TRUEHD.equals(mime)
                || MimeTypes.AUDIO_DTS.equals(mime)
                || MimeTypes.AUDIO_DTS_HD.equals(mime)
                || MimeTypes.AUDIO_E_AC3_JOC.equals(mime);
    }

    /** Diagnostic tag used across the integration. */
    public static void log(String message) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-nexio", "%s", message);
    }

    /** Effective AudioTrack encoding hint for the IEC route, or C.ENCODING_INVALID. */
    public static int iecEncodingHint(@Nullable Format format) {
        if (format == null) return C.ENCODING_INVALID;
        String mime = format.sampleMimeType;
        if (MimeTypes.AUDIO_AC3.equals(mime)) return C.ENCODING_AC3;
        if (MimeTypes.AUDIO_E_AC3.equals(mime) || MimeTypes.AUDIO_E_AC3_JOC.equals(mime)) return C.ENCODING_E_AC3;
        if (MimeTypes.AUDIO_DTS.equals(mime) || MimeTypes.AUDIO_DTS_HD.equals(mime)) return C.ENCODING_DTS;
        if (MimeTypes.AUDIO_TRUEHD.equals(mime)) return C.ENCODING_DTS; // Amlogic HAL sniffing route
        return C.ENCODING_INVALID;
    }

    /** Guard used by ExoUtil to decide whether to apply the NEXIO overrides. */
    public static boolean shouldOverrideLoadControl() {
        return isActive();
    }

    /** Context qualifier kept for future FireOS-specific probes. */
    public static boolean isFireOs(Context context) {
        return context != null && android.os.Build.MANUFACTURER != null
                && android.os.Build.MANUFACTURER.toLowerCase().contains("amazon");
    }
}
