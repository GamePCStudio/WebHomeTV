package com.fongmi.android.tv.player.exo;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import com.fongmi.android.tv.setting.NexioPlayerSettings;
import com.github.catvod.crawler.SpiderDebug;

import java.nio.ByteBuffer;

/**
 * WebHomeTV.ExoNexio: Kodi-style IEC passthrough sink (ported from the proven
 * WebHomeTV.N1 ExoPassthroughAudioSink, NEXIO/Amlogic platform behaviour).
 *
 * <p>Why a wrapper instead of an AudioTrack builder tweak: on Android 7.1
 * Amlogic boxes the HDMI EDID does not advertise TrueHD, so Media3 marks the
 * sink as unsupported during format negotiation and the renderer decodes to
 * PCM before any AudioTrack exists. The DTS-HD path does reach AudioTrack,
 * but the media3 default buffer (250ms x4 for DTS-HD at ~18Mbps) asks
 * AudioFlinger for a 4MB allocation that fails with ENOMEM. Both must be
 * intercepted at configure() time, outside DefaultAudioSink.
 *
 * <p>Behaviour while the NEXIO IEC toggle is on (default on this fork):
 * <ul>
 *   <li>TrueHD / TrueHD (Atmos): masquerade as a DTS 5.1/48kHz track and write
 *       the raw bitstream; the Amlogic HAL content-sniffs the actual stream and
 *       bitstreams it over HDMI (Kodi 16BIT passthrough route).</li>
 *   <li>DTS / DTS-HD / DTS-HD MA: native encoding label with a capped (<=512KB)
 *       buffer so allocation succeeds; the raw DTS-HD frame stream is written
 *       directly.</li>
 *   <li>Everything else (AC3/E-AC3/PCM/offload/tunneling) delegates untouched.</li>
 * </ul>
 */
@UnstableApi
public final class ExoNexioPassthroughSink implements AudioSink {

    private static final int MAX_INTERCEPT_BUFFER_BYTES = 512 * 1024;

    private final DefaultAudioSink delegate;

    private boolean masquerade;
    private int sampleRate;
    private int channelMask;
    private long framesWritten;
    @Nullable private AudioTrack track;
    @Nullable private Format formatOfLastConfig;

    public ExoNexioPassthroughSink(DefaultAudioSink delegate) {
        this.delegate = delegate;
    }

    private static boolean shouldIntercept(@Nullable Format format) {
        if (format == null || format.sampleMimeType == null) return false;
        return MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType)
                || MimeTypes.AUDIO_DTS.equals(format.sampleMimeType)
                || MimeTypes.AUDIO_DTS_HD.equals(format.sampleMimeType);
    }

    @Override
    public void configure(AudioSinkConfig audioSinkConfig) throws ConfigurationException {
        Format format = audioSinkConfig.format;
        formatOfLastConfig = format;
        if (!NexioPlayerSettings.isIecPassthroughEnabled() || !shouldIntercept(format)) {
            masquerade = false;
            delegate.configure(audioSinkConfig);
            return;
        }
        boolean truehd = MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType);
        // DTS-labeled raw track: DTS core rate for the HDMI carrier, content sniffed by HAL.
        masquerade = true;
        sampleRate = 48000;
        channelMask = AudioFormat.CHANNEL_OUT_5POINT1;
        framesWritten = 0;
        createInterceptTrack();
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("exo-nexio", "sink intercept %s -> DTS(5.1/48k) raw bitstream, buffer<=%dB",
                    truehd ? "TrueHD" : "DTS-HD", MAX_INTERCEPT_BUFFER_BYTES);
        }
    }

    private void createInterceptTrack() throws ConfigurationException {
        try {
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, C.ENCODING_DTS);
            if (minBuf <= 0) minBuf = 64 * 1024;
            int size = Math.min(Math.max(minBuf * 4, 64 * 1024), MAX_INTERCEPT_BUFFER_BYTES);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .setEncoding(C.ENCODING_DTS)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(size)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                throw new ConfigurationException("nexio intercept track not initialized", formatOfLastConfig);
            }
        } catch (ConfigurationException e) {
            throw e;
        } catch (Throwable t) {
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-nexio", "sink intercept create failed: %s", t.getMessage());
            }
            throw new ConfigurationException("nexio intercept track create failed", formatOfLastConfig);
        }
    }

    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
            throws InitializationException, WriteException {
        if (masquerade && track != null) {
            int bytes = buffer.remaining();
            int written = track.write(buffer, bytes, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new WriteException(written, formatOfLastConfig, false);
            }
            // 2ch * 16bit = 4 bytes per carrier frame (same accounting as the N1 sink).
            framesWritten += written / 4;
            return !buffer.hasRemaining();
        }
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
    }

    @Override
    public void play() {
        if (masquerade) {
            if (track != null) track.play();
            return;
        }
        delegate.play();
    }

    @Override
    public void pause() {
        if (masquerade) {
            if (track != null) track.pause();
            return;
        }
        delegate.pause();
    }

    @Override
    public void flush() {
        if (masquerade) {
            if (track != null) {
                track.pause();
                track.flush();
            }
            framesWritten = 0;
            return;
        }
        delegate.flush();
    }

    @Override
    public void reset() {
        if (masquerade) {
            if (track != null) {
                track.pause();
                track.flush();
                track.release();
                track = null;
            }
            framesWritten = 0;
            return;
        }
        delegate.reset();
    }

    @Override
    public void release() {
        if (masquerade && track != null) {
            track.pause();
            track.flush();
            track.release();
            track = null;
        }
        delegate.release();
    }

    @Override
    public boolean supportsFormat(Format format) {
        if (NexioPlayerSettings.isIecPassthroughEnabled() && shouldIntercept(format)) return true;
        return delegate.supportsFormat(format);
    }

    @Override
    public @SinkFormatSupport int getFormatSupport(Format format) {
        if (NexioPlayerSettings.isIecPassthroughEnabled() && shouldIntercept(format)) {
            return SINK_FORMAT_SUPPORTED_DIRECTLY;
        }
        return delegate.getFormatSupport(format);
    }

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
        if (masquerade) {
            return track == null ? 0 : framesWritten * 1_000_000L / sampleRate;
        }
        return delegate.getCurrentPositionUs(sourceEnded);
    }

    @Override
    public void setListener(Listener listener) {
        delegate.setListener(listener);
    }

    @Override
    public boolean getSkipSilenceEnabled() {
        return masquerade ? false : delegate.getSkipSilenceEnabled();
    }

    @Override
    public androidx.media3.common.AudioAttributes getAudioAttributes() {
        return masquerade ? androidx.media3.common.AudioAttributes.DEFAULT : delegate.getAudioAttributes();
    }

    @Override
    public void setAudioAttributes(androidx.media3.common.AudioAttributes audioAttributes) {
        if (!masquerade) delegate.setAudioAttributes(audioAttributes);
    }

    @Override
    public void setAudioSessionId(int audioSessionId) {
        if (!masquerade) delegate.setAudioSessionId(audioSessionId);
    }

    @Override
    public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
        if (!masquerade) delegate.setAuxEffectInfo(auxEffectInfo);
    }

    @Override
    public void enableTunnelingV21() {
        if (!masquerade) delegate.enableTunnelingV21();
    }

    @Override
    public void disableTunneling() {
        if (!masquerade) delegate.disableTunneling();
    }

    @Override
    public void setVolume(float volume) {
        if (!masquerade) delegate.setVolume(volume);
    }

    @Override
    public long getAudioTrackBufferSizeUs() {
        if (masquerade) {
            return track != null ? track.getBufferSizeInFrames() * 1_000_000L / sampleRate : 0;
        }
        return delegate.getAudioTrackBufferSizeUs();
    }

    @Override
    public void setPlaybackParameters(PlaybackParameters playbackParameters) {
        if (!masquerade) delegate.setPlaybackParameters(playbackParameters);
    }

    @Override
    public PlaybackParameters getPlaybackParameters() {
        return masquerade ? PlaybackParameters.DEFAULT : delegate.getPlaybackParameters();
    }

    @Override
    public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
        if (!masquerade) delegate.setSkipSilenceEnabled(skipSilenceEnabled);
    }

    @Override
    public void handleDiscontinuity() {
        if (masquerade) {
            flush();
            return;
        }
        delegate.handleDiscontinuity();
    }

    @Override
    public void playToEndOfStream() throws WriteException {
        if (!masquerade) delegate.playToEndOfStream();
    }

    @Override
    public boolean isEnded() {
        return masquerade ? false : delegate.isEnded();
    }

    @Override
    public boolean hasPendingData() {
        if (masquerade) {
            return track != null && track.getPlaybackHeadPosition() < framesWritten;
        }
        return delegate.hasPendingData();
    }
}
