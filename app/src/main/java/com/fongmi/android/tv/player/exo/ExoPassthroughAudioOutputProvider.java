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
 * 音频直通（源码输出）增强 Provider。
 *
 * <p>针对低版本系统（API &lt; 29，如斐讯 N1 / Android 7.1.2）直通能力判定不可靠的问题，
 * 对环绕声直通编码采用「AudioTrack 实测决定制」：
 *
 * <ul>
 *   <li>getFormatSupport：直通编码不再信任系统 HDMI 广播（ACTION_HDMI_AUDIO_PLUG 的
 *       EXTRA_ENCODINGS 可能缺声明），以 AudioTrack 逐编码实测为准——实测通过即宣称
 *       FORMAT_SUPPORTED_DIRECTLY，实测不过则不支持（自动回退解码）。</li>
 *   <li>getOutputConfig：实测通过后自行构造直通 OutputConfig（编码/声道/缓冲区），
 *       不依赖系统 AudioCapabilities 的编码列表，避免 ConfigurationException。</li>
 *   <li>DTS-HD / DTS-HD MA / DTS Express 降级为 DTS core 源码输出（映射为
 *       audio/vnd.dts，声道压到 6ch），兼容只支持 DTS 的功放/电视；无 core 的
 *       coreless 流不降级。</li>
 * </ul>
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
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log("exo-passthrough", "evaluate mime=%s sdk=%d",
                    mapped.format.sampleMimeType, Build.VERSION.SDK_INT);
        }
        // API < 29：直通编码完全由 AudioTrack 实测决定（不信任 HDMI 广播声明）。
        if (Build.VERSION.SDK_INT < 29 && isProbeablePassthrough(mapped.format)) {
            boolean supported = isActuallySupported(mapped.format);
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "support mime=%s supported=%s sdk=%d",
                        mapped.format.sampleMimeType, supported, Build.VERSION.SDK_INT);
            }
            if (supported) {
                return new FormatSupport.Builder()
                        .setFormatSupportLevel(FORMAT_SUPPORTED_DIRECTLY)
                        .build();
            }
            return FormatSupport.UNSUPPORTED;
        }
        FormatSupport support = super.getFormatSupport(mapped);
        // API 29+（系统已真实探测）仍保留否决式保护：系统宣称支持但本类实测不过时不直通。
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
        FormatConfig mapped = maybeDowngradeDtsHd(formatConfig);
        // API < 29 且实测支持：自行构造直通配置，绕过系统 AudioCapabilities 编码列表。
        if (Build.VERSION.SDK_INT < 29
                && isProbeablePassthrough(mapped.format)
                && isActuallySupported(mapped.format)) {
            return buildPassthroughOutputConfig(mapped);
        }
        return super.getOutputConfig(mapped);
    }

    /**
     * 手工构造直通 OutputConfig。编码来自格式自身（DTS-HD 已降级为 ENCODING_DTS），
     * 声道掩码按声道数映射，缓冲区按 AudioTrack 最小缓冲放大。
     */
    private static OutputConfig buildPassthroughOutputConfig(FormatConfig config)
            throws ConfigurationException {
        Format format = config.format;
        int encoding = MimeTypes.getEncoding(format.sampleMimeType, format.codecs);
        if (encoding == C.ENCODING_INVALID) {
            throw new ConfigurationException("Unable to configure passthrough for: " + format);
        }
        int channelCount = format.channelCount == Format.NO_VALUE ? 6 : format.channelCount;
        int channelMask = getChannelMaskForPassthrough(channelCount);
        if (channelMask == AudioFormat.CHANNEL_INVALID) {
            throw new ConfigurationException("Unsupported passthrough channel count: " + channelCount);
        }
        int sampleRate = format.sampleRate == Format.NO_VALUE ? PROBE_SAMPLE_RATE_HZ : format.sampleRate;
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding);
        if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
            // Android 7.x 的 getMinBufferSize 不认 TRUEHD 等直通编码，改用固定缓冲。
            minBufferSize = PASSTHROUGH_FALLBACK_BUFFER_BYTES;
        }
        return new OutputConfig.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding)
                .setBufferSize(Math.max(minBufferSize * 4, 1024))
                .setAudioSessionId(config.audioSessionId)
                .setAudioAttributes(config.audioAttributes)
                .setIsOffload(false)
                .setIsTunneling(config.enableTunneling)
                .setUsePlaybackParameters(config.enablePlaybackParameters)
                .setUseOffloadGapless(false)
                .setVirtualDeviceId(config.virtualDeviceId)
                .build();
    }

    private static int getChannelMaskForPassthrough(int channelCount) {
        switch (channelCount) {
            case 1:
                return AudioFormat.CHANNEL_OUT_MONO;
            case 2:
                return AudioFormat.CHANNEL_OUT_STEREO;
            case 3:
            case 4:
                return AudioFormat.CHANNEL_OUT_QUAD;
            case 5:
            case 6:
                return AudioFormat.CHANNEL_OUT_5POINT1;
            case 7:
            case 8:
                return AudioFormat.CHANNEL_OUT_7POINT1;
            default:
                return AudioFormat.CHANNEL_INVALID;
        }
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

    /** 直通编码固定缓冲（AOSP 7.x 的 getMinBufferSize 不支持 TRUEHD 等编码时使用）。 */
    private static final int PASSTHROUGH_FALLBACK_BUFFER_BYTES = 64 * 1024;

    /**
     * 用 AudioTrack 实测设备能否创建指定直通编码的轨道。
     * 与 ExoPlayer 2.x 时代 AudioCapabilities 的探测方式一致，失败即视为不支持直通。
     * 注意：Android 7.x 的 getMinBufferSize 对 TRUEHD 编码返回 ERROR_BAD_VALUE，
     * 此时改用固定缓冲继续创建（直通写入不依赖 min buffer 精确值）。
     */
    private static boolean probeEncoding(int encoding) {
        int minBufferSize;
        try {
            minBufferSize = AudioTrack.getMinBufferSize(PROBE_SAMPLE_RATE_HZ, PROBE_CHANNEL_MASK, encoding);
        } catch (Throwable t) {
            // Android 7.x 的 getMinBufferSize 对部分直通编码（如 TRUEHD）可能抛异常/返回 ERROR。
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "probe minBuffer threw encoding=%d err=%s fallback buffer", encoding, t.getMessage());
            }
            minBufferSize = PASSTHROUGH_FALLBACK_BUFFER_BYTES;
        }
        if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "probe minBuffer unsupported encoding=%d fallback buffer", encoding);
            }
            minBufferSize = PASSTHROUGH_FALLBACK_BUFFER_BYTES;
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
                    .setBufferSizeInBytes(Math.max(minBufferSize * 4, PASSTHROUGH_FALLBACK_BUFFER_BYTES))
                    .build();
            return track.getState() == AudioTrack.STATE_INITIALIZED;
        } catch (Throwable t) {
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "probe failed encoding=%d err=%s", encoding, t.getMessage());
            }
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

    /** 仅在直通编码（环绕声源码输出）上做探测/强制，PCM 一律不处理。 */
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
