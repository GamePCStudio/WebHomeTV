package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioOutputProvider;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider;

import com.github.catvod.crawler.SpiderDebug;

import java.util.HashMap;
import java.util.Map;

/**
 * 音频直通（源码输出）增强 Provider，解决两类问题：
 *
 * <p>1. 低版本系统（API &lt; 29）直通能力错位：media3 在 API &lt; 29 上信任
 * ACTION_HDMI_AUDIO_PLUG 广播的 EXTRA_ENCODINGS，不做事先探测。部分固件（如斐讯 N1 的
 * Amlogic Android 7.1.2）上报支持 DTS/AC3，但 AudioTrack 实际创建失败，导致播放报错
 * （ERROR_CODE_AUDIO_TRACK_INIT_FAILED / WRITE_FAILED）。本类对 API &lt; 29 的设备
 * 用 AudioTrack 逐编码实测，实测不过就不宣称支持直通，自动回退解码输出。
 *
 * <p>2. DTS-HD / DTS-HD MA 音轨降级为 DTS core 源码输出：把 DTS-HD 系格式映射为
 * audio/vnd.dts 再交给系统判定，功放/电视只按 DTS core 接收（兼容只支持 DTS 的设备，
 * 避免 DTS-HD 直通在部分功放上无声或失败）。无 core 的 DTS-HD MA coreless 流不降级。
 */
@UnstableApi
public final class ExoPassthroughAudioOutputProvider extends ForwardingAudioOutputProvider {

    /** 探测用的统一采样率与声道布局（DTS/AC3 直通最常见 48kHz 5.1）。 */
    private static final int PROBE_SAMPLE_RATE_HZ = 48_000;
    private static final int PROBE_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_5POINT1;

    private final Map<Integer, Boolean> probedSupport = new HashMap<>();

    public ExoPassthroughAudioOutputProvider(Context context) {
        super(new AudioTrackAudioOutputProvider.Builder(context).build());
    }

    @Override
    public FormatSupport getFormatSupport(FormatConfig formatConfig) {
        FormatConfig mapped = maybeDowngradeDtsHd(formatConfig);
        FormatSupport support = super.getFormatSupport(mapped);
        if (support.supportLevel == FORMAT_SUPPORTED_DIRECTLY
                && isProbeablePassthrough(mapped.format)
                && !isActuallySupported(mapped.format)) {
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "probe rejected mime=%s sdk=%d",
                        mapped.format.sampleMimeType, Build.VERSION.SDK_INT);
            }
            return support.buildUpon().setFormatSupportLevel(FORMAT_UNSUPPORTED).build();
        }
        return support;
    }

    @Override
    public OutputConfig getOutputConfig(FormatConfig formatConfig) throws ConfigurationException {
        return super.getOutputConfig(maybeDowngradeDtsHd(formatConfig));
    }

    /**
     * 把 DTS-HD / DTS-HD MA / DTS Express 映射为 DTS（core）格式，走 DTS 直通判定与输出。
     * DTS-HD MA coreless（无 core 层）无法降级，保持原样。
     */
    private static FormatConfig maybeDowngradeDtsHd(FormatConfig config) {
        Format format = config.format;
        String mime = format.sampleMimeType;
        boolean isDtsHd = MimeTypes.AUDIO_DTS_HD.equals(mime)
                || MimeTypes.AUDIO_DTS_HD_MA.equals(mime)
                || MimeTypes.AUDIO_DTS_EXPRESS.equals(mime);
        if (!isDtsHd || MimeTypes.AUDIO_MEDIA3_DTS_HD_MA_CORELESS.equals(mime)) {
            return config;
        }
        int channelCount = format.channelCount == Format.NO_VALUE
                ? 6
                : Math.min(format.channelCount, 6); // DTS core 最高 5.1（6 声道）
        Format downgraded = format.buildUpon()
                .setSampleMimeType(MimeTypes.AUDIO_DTS)
                .setCodecs("dts")
                .setChannelCount(channelCount)
                .build();
        return new FormatConfig.Builder(downgraded)
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

    /**
     * API 29+ 系统自身已用 AudioTrack.isDirectPlaybackSupported() 实测，直接信任；
     * API &lt; 29 用本类自带的 AudioTrack 探测结果。
     */
    private boolean isActuallySupported(Format format) {
        if (Build.VERSION.SDK_INT >= 29) return true;
        int encoding = MimeTypes.getEncoding(format.sampleMimeType, format.codecs);
        if (encoding == C.ENCODING_INVALID) return true;
        Boolean cached = probedSupport.get(encoding);
        if (cached != null) return cached;
        boolean supported = probeEncoding(encoding);
        probedSupport.put(encoding, supported);
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("exo-passthrough", "probe encoding=%d supported=%s", encoding, supported);
        }
        return supported;
    }

    /**
     * 用 AudioTrack 实测设备能否创建指定直通编码的轨道。
     * 与 ExoPlayer 2.x 时代 AudioCapabilities 的探测方式一致，失败即视为不支持直通。
     */
    private static boolean probeEncoding(int encoding) {
        int minBufferSize = AudioTrack.getMinBufferSize(PROBE_SAMPLE_RATE_HZ, PROBE_CHANNEL_MASK, encoding);
        if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
            return false;
        }
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(PROBE_SAMPLE_RATE_HZ)
                            .setChannelMask(PROBE_CHANNEL_MASK)
                            .setEncoding(encoding)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minBufferSize * 4, 1024))
                    .build();
            return track.getState() == AudioTrack.STATE_INITIALIZED;
        } catch (Throwable t) {
            return false;
        } finally {
            if (track != null) {
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 仅在直通编码（环绕声源码输出）上做探测，PCM 一律不探测。 */
    private static boolean isProbeablePassthrough(Format format) {
        String mime = format.sampleMimeType;
        return MimeTypes.AUDIO_AC3.equals(mime)
                || MimeTypes.AUDIO_E_AC3.equals(mime)
                || MimeTypes.AUDIO_E_AC3_JOC.equals(mime)
                || MimeTypes.AUDIO_AC4.equals(mime)
                || MimeTypes.AUDIO_DTS.equals(mime)
                || MimeTypes.AUDIO_DTS_HD.equals(mime)
                || MimeTypes.AUDIO_DTS_HD_MA.equals(mime)
                || MimeTypes.AUDIO_MEDIA3_DTS_HD_MA_CORELESS.equals(mime)
                || MimeTypes.AUDIO_DTS_EXPRESS.equals(mime)
                || MimeTypes.AUDIO_DTS_UHD_P2.equals(mime)
                || MimeTypes.AUDIO_TRUEHD.equals(mime);
    }
}
