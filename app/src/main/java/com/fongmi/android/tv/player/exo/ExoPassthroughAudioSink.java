package com.fongmi.android.tv.player.exo;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import com.github.catvod.crawler.SpiderDebug;

import java.nio.ByteBuffer;

/**
 * 音频直通伪装 AudioSink。
 *
 * <p>N1（Android 7.1.2）的 AudioTrack 拒绝 TrueHD（14）与 IEC61937（12）编码，而 media3 的
 * bypass 直通要求 encoding 非 PCM 且数据能被帧解析（PCM 伪装抛 "Unexpected audio encoding"，
 * DTS 伪装卡死在帧解析）。本类在 TrueHD 播放时完全绕过 DefaultAudioSink 的帧解析逻辑：
 * 用 DTS(7) 编码创建 AudioTrack（系统唯一可用的非 PCM 轨道），把 TrueHD 原始数据直接写入，
 * 依赖 Amlogic HAL 对 DTS 流的内容检测实现 HDMI 直通（Kodi 16BIT passthrough 思路的 media3 实现）。
 *
 * <p>其余格式全部委托给 DefaultAudioSink，行为不变。
 */
@UnstableApi
public final class ExoPassthroughAudioSink implements AudioSink {

    private final DefaultAudioSink delegate;

    private boolean masquerade;
    private AudioTrack track;
    private int sampleRate;
    private int channelMask;
    private int encoding;
    private long framesWritten;

    public ExoPassthroughAudioSink(DefaultAudioSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public void setListener(Listener listener) {
        delegate.setListener(listener);
    }

    @Override
    public boolean supportsFormat(Format format) {
        return delegate.supportsFormat(format);
    }

    @Override
    public int getFormatSupport(Format format) {
        return delegate.getFormatSupport(format);
    }

    @Override
    public void configure(AudioSinkConfig audioSinkConfig) throws ConfigurationException {
        Format format = audioSinkConfig.format;
        if (MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType)) {
            // TrueHD 伪装：DTS(7) 轨道 + 原始数据直写。
            masquerade = true;
            encoding = C.ENCODING_DTS;
            sampleRate = 48000;
            channelMask = AudioFormat.CHANNEL_OUT_5POINT1;
            framesWritten = 0;
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "sink masquerade truehd->dts(5.1/48k)");
            }
            return;
        }
        masquerade = false;
        delegate.configure(audioSinkConfig);
    }

    @Override
    public void initialize() throws InitializationException {
        if (masquerade) {
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding);
            if (minBuf <= 0) {
                minBuf = 64 * 1024;
            }
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelMask)
                                .setEncoding(encoding)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(Math.max(minBuf * 4, 64 * 1024))
                        .build();
            } catch (Throwable t) {
                if (SpiderDebug.isEnabled()) {
                    SpiderDebug.log("exo-passthrough", "sink masquerade init failed: %s", t.getMessage());
                }
                throw new InitializationException("masquerade track init failed", 0, null, false, t);
            }
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                throw new InitializationException("masquerade track not initialized", 0, null, false, null);
            }
            framesWritten = 0;
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "sink masquerade track initialized");
            }
            return;
        }
        delegate.initialize();
    }

    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
            throws InitializationException, WriteException {
        if (masquerade && track != null) {
            int bytes = buffer.remaining();
            int written = track.write(buffer, bytes, AudioTrack.WRITE_BLOCKING);
            framesWritten += written / 4; // 2ch * 16bit = 4 bytes/frame
            buffer.position(buffer.position() + written);
            return !buffer.hasRemaining();
        }
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
    }

    @Override
    public void play() {
        if (masquerade) {
            if (track != null) {
                track.play();
            }
            return;
        }
        delegate.play();
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
        if (!masquerade) {
            delegate.playToEndOfStream();
        }
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

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
        if (masquerade) {
            if (track == null || framesWritten == 0) {
                return CURRENT_POSITION_NOT_SET;
            }
            return framesWritten * 1_000_000L / sampleRate;
        }
        return delegate.getCurrentPositionUs(sourceEnded);
    }

    @Override
    public void setPlaybackParameters(PlaybackParameters playbackParameters) {
        if (!masquerade) {
            delegate.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public PlaybackParameters getPlaybackParameters() {
        return masquerade ? PlaybackParameters.DEFAULT : delegate.getPlaybackParameters();
    }

    @Override
    public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
        if (!masquerade) {
            delegate.setSkipSilenceEnabled(skipSilenceEnabled);
        }
    }

    @Override
    public void setAudioSessionId(int audioSessionId) {
        if (!masquerade) {
            delegate.setAudioSessionId(audioSessionId);
        }
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
        if (masquerade) {
            if (track != null) {
                track.pause();
                track.flush();
                track.release();
                track = null;
            }
        }
        delegate.release();
    }
}
