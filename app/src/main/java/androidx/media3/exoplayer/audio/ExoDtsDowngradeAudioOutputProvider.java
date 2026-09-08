package androidx.media3.exoplayer.audio;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

/**
 * Audio output provider wrapper that forces DTS-HD / DTS-HD MA / DTS:X (DTS-UHD P2) passthrough
 * down to the DTS core layer while the force-downgrade toggle is enabled.
 *
 * <p>The stock {@link AudioCapabilities} already downgrades DTS-HD and DTS-UHD P2 to the DTS core
 * when the device does NOT report support for the higher encoding. There is no public API to force
 * that path on devices that falsely advertise DTS-HD support, so this provider masquerades the
 * track as plain DTS (channel count clamped to 5.1) before the stock provider evaluates it. The
 * AudioTrack still receives the original DTS-HD/DTS-X bitstream bytes; HDMI sinks detect the real
 * content inside the DTS-framed stream, mirroring the known-good Amlogic DTS core output behavior
 * from the WebHomeTV DTS-n1-passthrough-fix.
 *
 * <p>This class lives in the {@code androidx.media3.exoplayer.audio} package because
 * {@link AudioTrackAudioOutputProvider.Builder} and several nested {@link AudioOutputProvider}
 * types it forwards are package-private.
 */
@UnstableApi
public final class ExoDtsDowngradeAudioOutputProvider extends ForwardingAudioOutputProvider {

    /** DTS core passthrough only carries up to 5.1 (6 channels). */
    private static final int DTS_CORE_MAX_CHANNEL_COUNT = 6;

    private final State state;

    private ExoDtsDowngradeAudioOutputProvider(AudioTrackAudioOutputProvider delegate, State state) {
        super(delegate);
        this.state = state;
    }

    /** Creates a provider that applies the downgrade whenever {@link State#isEnabled()} returns true. */
    public static AudioOutputProvider create(Context context, State state) {
        return new ExoDtsDowngradeAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(context).build(), state);
    }

    @Override
    public FormatSupport getFormatSupport(FormatConfig formatConfig) {
        Format rewritten = rewrite(formatConfig.format);
        return rewritten == null ? super.getFormatSupport(formatConfig) : super.getFormatSupport(withFormat(formatConfig, rewritten));
    }

    @Override
    public OutputConfig getOutputConfig(FormatConfig formatConfig) throws ConfigurationException {
        Format rewritten = rewrite(formatConfig.format);
        return rewritten == null ? super.getOutputConfig(formatConfig) : super.getOutputConfig(withFormat(formatConfig, rewritten));
    }

    private FormatConfig withFormat(FormatConfig config, Format rewritten) {
        return new FormatConfig.Builder(rewritten)
                .setAudioAttributes(config.audioAttributes)
                .setPreferredDevice(config.preferredDevice)
                .setEnableHighResolutionPcmOutput(config.enableHighResolutionPcmOutput)
                .setEnablePlaybackParameters(config.enablePlaybackParameters)
                .setEnableOffload(config.enableOffload)
                .setAudioSessionId(config.audioSessionId)
                .setVirtualDeviceId(config.virtualDeviceId)
                .setEnableTunneling(config.enableTunneling)
                .setPreferredBufferSize(config.preferredBufferSize)
                .build();
    }

    @Nullable
    private Format rewrite(@Nullable Format format) {
        if (!state.isEnabled() || format == null || format.sampleMimeType == null) return null;
        int encoding = MimeTypes.getEncoding(format.sampleMimeType, format.codecs);
        boolean downgrade = encoding == C.ENCODING_DTS_HD || encoding == C.ENCODING_DTS_HD_MA || encoding == C.ENCODING_DTS_UHD_P2;
        if (!downgrade) return null;
        int channelCount = format.channelCount == Format.NO_VALUE ? DTS_CORE_MAX_CHANNEL_COUNT : Math.min(format.channelCount, DTS_CORE_MAX_CHANNEL_COUNT);
        Format.Builder builder = format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS).setCodecs(null).setChannelCount(channelCount);
        if (format.channelMask != Format.NO_VALUE) builder.setChannelMask(Util.getAudioTrackChannelConfig(channelCount));
        return builder.build();
    }

    /** Read-through indirection so every capability query reflects the current toggle value. */
    public interface State {
        boolean isEnabled();
    }
}
