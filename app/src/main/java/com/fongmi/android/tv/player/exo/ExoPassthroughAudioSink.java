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

    private Format formatOfLastConfig;
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
    public void setListener(AudioSink.Listener listener) {
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
    public void configure(AudioSink.AudioSinkConfig audioSinkConfig) throws AudioSink.ConfigurationException {
        Format format = audioSinkConfig.format;
        formatOfLastConfig = format;
        if (MimeTypes.AUDIO_TRUEHD.equals(format.sampleMimeType)) {
            // TrueHD 伪装：DTS(7) 轨道 + 原始数据直写（AudioSink 接口无 initialize，
            // track 在 configure 阶段创建）。
            masquerade = true;
            encoding = C.ENCODING_DTS;
            sampleRate = 48000;
            channelMask = AudioFormat.CHANNEL_OUT_5POINT1;
            framesWritten = 0;
            createMasqueradeTrack();
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "sink masquerade truehd->dts(5.1/48k)");
            }
            return;
        }
        masquerade = false;
        delegate.configure(audioSinkConfig);
    }



    private void createMasqueradeTrack() throws AudioSink.ConfigurationException {
        try {
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding);
            if (minBuf <= 0) {
                minBuf = 64 * 1024;
            }
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
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                throw new AudioSink.ConfigurationException("masquerade track not initialized", formatOfLastConfig);
            }
        } catch (Throwable t) {
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "sink masquerade create failed: %s", t.getMessage());
            }
            throw new AudioSink.ConfigurationException("masquerade track create failed", formatOfLastConfig);
        }
    }
    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
            throws AudioSink.InitializationException, AudioSink.WriteException {
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
    public boolean getSkipSilenceEnabled() {
        return masquerade ? false : delegate.getSkipSilenceEnabled();
    }


    @Override
    public androidx.media3.common.AudioAttributes getAudioAttributes() {
        return masquerade ? androidx.media3.common.AudioAttributes.DEFAULT : delegate.getAudioAttributes();
    }
    @Override
    public void setAudioAttributes(androidx.media3.common.AudioAttributes audioAttributes) {
        if (!masquerade) {
            delegate.setAudioAttributes(audioAttributes);
        }
    }

    @Override
    public long getAudioTrackBufferSizeUs() {
        if (masquerade) {
            return track != null ? track.getBufferSizeInFrames() * 1_000_000L / sampleRate : 0;
        }
        return delegate.getAudioTrackBufferSizeUs();
    }

    @Override
    public void enableTunnelingV21() {
        if (!masquerade) {
            delegate.enableTunnelingV21();
        }
    }

    @Override
    public void disableTunneling() {
        if (!masquerade) {
            delegate.disableTunneling();
        }
    }
    @Override
    public void setVolume(float volume) {
        if (!masquerade) {
            delegate.setVolume(volume);
        }
    }

    @Override
    public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
        if (!masquerade) {
            delegate.setAuxEffectInfo(auxEffectInfo);
        }
    }
    @Override
    public void pause() {
        if (masquerade) {
            if (track != null) {
                track.pause();
            }
            return;
        }
        delegate.pause();
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
    public void playToEndOfStream() throws AudioSink.WriteException {
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
