package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import com.github.catvod.crawler.SpiderDebug;

import java.util.Collections;
import java.util.List;

/**
 * 解码器查询兜底 MediaCodecSelector。
 *
 * <p>部分旧系统/第三方固件（如斐讯 N1 的 Amlogic Android 7.1.2）MediaCodecList 对
 * TrueHD/DTS-HD 等 MIME 没有可用声明（或声明非标准），导致 media3 查询返回空列表，
 * 报「没有该 MIME 的系统音频 decoder」。本类在系统查询为空或异常时，为直通（源码输出）
 * 相关 MIME 合成一个 raw decoder 信息。
 *
 * <p>安全前提：直通（bypass）模式下 MediaCodecAudioRenderer 不实际创建解码器
 * （"Direct playback with codec bypass"），合成信息仅用于通过 decoder 门禁检查；
 * 合成的 codec 名称沿用系统真实存在的 OMX.google.raw.decoder，即使极端情况下被实例化
 * 也能被系统找到。
 */
public final class ExoPassthroughMediaCodecSelector implements MediaCodecSelector {

    private static final String SYNTHETIC_RAW_DECODER_NAME = "OMX.google.raw.decoder";

    @Override
    public List<MediaCodecInfo> getDecoderInfos(
            String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
            throws MediaCodecUtil.DecoderQueryException {
        if (requiresSecureDecoder || requiresTunnelingDecoder) {
            return MediaCodecUtil.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        }
        try {
            List<MediaCodecInfo> decoderInfos =
                    MediaCodecUtil.getDecoderInfos(
                            mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (!decoderInfos.isEmpty()) {
                return decoderInfos;
            }
        } catch (MediaCodecUtil.DecoderQueryException e) {
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "selector query failed mime=%s err=%s",
                        mimeType, e.getClass().getSimpleName());
            }
            if (!isPassthroughMime(mimeType)) {
                throw e;
            }
        }
        if (isPassthroughMime(mimeType)) {
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "selector synthetic mime=%s", mimeType);
            }
            return Collections.singletonList(syntheticRawDecoder(mimeType));
        }
        return MediaCodecUtil.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
    }

    /**
     * 合成 raw decoder 信息。capabilities 通过反射构造一个不受限的音频能力
     * （8 声道 + 常用采样率），使 media3 的 isFormatSupported 返回 true ——
     * 直接传 null 会让 isAudioSampleRateSupportedV21/isAudioChannelCountSupportedV21
     * 判定失败（NoSupport），渲染器只能拿 EXCEEDS_CAPABILITIES 而被 FFmpeg 软解抢占。
     */
    private static MediaCodecInfo syntheticRawDecoder(String mimeType) {
        return MediaCodecInfo.newInstance(
                SYNTHETIC_RAW_DECODER_NAME,
                mimeType,
                mimeType,
                createUnrestrictedAudioCapabilities(),
                /* hardwareAccelerated= */ false,
                /* softwareOnly= */ true,
                /* vendor= */ false,
                /* forceDisableAdaptive= */ false,
                /* forceSecure= */ false);
    }

    /**
     * 反射构造 android.media.MediaCodecInfo.CodecCapabilities（含不受限的
     * AudioCapabilities）。仅用于合成 decoder 信息，bypass 直通不会真正创建 codec。
     * 适配不同 Android 版本的构造器签名（7.x 与 8+ 不同）；失败时回退 null。
     */
    @Nullable
    private static android.media.MediaCodecInfo.CodecCapabilities createUnrestrictedAudioCapabilities() {
        try {
            Object audioCaps = newInstanceBestEffort(
                    Class.forName("android.media.AudioCapabilities"),
                    new Object[]{8, new int[]{32000, 44100, 48000, 88200, 96000, 176400, 192000}});
            if (audioCaps == null) {
                if (SpiderDebug.isEnabled()) {
                    SpiderDebug.log("exo-passthrough", "synthetic audio caps ctor not found");
                }
                return null;
            }
            android.media.MediaCodecInfo.CodecCapabilities caps = null;
            for (java.lang.reflect.Constructor<?> ctor : android.media.MediaCodecInfo.CodecCapabilities.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                Object[] args = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    if (params[i] == android.media.MediaCodecInfo.CodecProfileLevel[].class) {
                        args[i] = new android.media.MediaCodecInfo.CodecProfileLevel[0];
                    } else if (params[i] == String[].class) {
                        args[i] = new String[0];
                    } else if (params[i] == int.class) {
                        args[i] = 0;
                    } else if (params[i] == boolean.class) {
                        args[i] = false;
                    } else {
                        args[i] = null;
                    }
                }
                try {
                    ctor.setAccessible(true);
                    caps = (android.media.MediaCodecInfo.CodecCapabilities) ctor.newInstance(args);
                    break;
                } catch (Throwable ignored) {
                    // 尝试下一个构造器
                }
            }
            if (caps == null) {
                if (SpiderDebug.isEnabled()) {
                    SpiderDebug.log("exo-passthrough", "synthetic codec caps ctor not found");
                }
                return null;
            }
            java.lang.reflect.Field field = android.media.MediaCodecInfo.CodecCapabilities.class.getDeclaredField("mAudioCapabilities");
            field.setAccessible(true);
            field.set(caps, audioCaps);
            return caps;
        } catch (Throwable t) {
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log("exo-passthrough", "synthetic caps failed: %s", t.getMessage());
            }
            return null;
        }
    }

    /**
     * 用首选参数尝试实例化；失败则遍历所有构造器，用类型默认值填充参数尝试。
     * 适配不同 Android 版本/厂商定制的内部类签名差异。
     */
    @Nullable
    private static Object newInstanceBestEffort(Class<?> clazz, Object[] preferredArgs) {
        for (java.lang.reflect.Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != preferredArgs.length) continue;
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                if (preferredArgs[i] != null && !params[i].isInstance(preferredArgs[i])) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            try {
                ctor.setAccessible(true);
                return ctor.newInstance(preferredArgs);
            } catch (Throwable ignored) {
            }
        }
        for (java.lang.reflect.Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                if (params[i] == int[].class) {
                    args[i] = new int[]{32000, 44100, 48000, 88200, 96000, 176400, 192000};
                } else if (params[i] == int.class) {
                    args[i] = 8;
                } else if (params[i] == boolean.class) {
                    args[i] = false;
                } else {
                    args[i] = null;
                }
            }
            try {
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 源码输出（直通）相关 MIME：系统查询为空/异常时允许合成兜底。 */
    private static boolean isPassthroughMime(@Nullable String mimeType) {
        return MimeTypes.AUDIO_RAW.equals(mimeType)
                || MimeTypes.AUDIO_AC3.equals(mimeType)
                || MimeTypes.AUDIO_E_AC3.equals(mimeType)
                || MimeTypes.AUDIO_E_AC3_JOC.equals(mimeType)
                || MimeTypes.AUDIO_AC4.equals(mimeType)
                || MimeTypes.AUDIO_DTS.equals(mimeType)
                || MimeTypes.AUDIO_DTS_HD.equals(mimeType)
                || MimeTypes.AUDIO_DTS_HD_MA.equals(mimeType)
                || MimeTypes.AUDIO_MEDIA3_DTS_HD_MA_CORELESS.equals(mimeType)
                || MimeTypes.AUDIO_DTS_EXPRESS.equals(mimeType)
                || MimeTypes.AUDIO_DTS_UHD_P2.equals(mimeType)
                || MimeTypes.AUDIO_TRUEHD.equals(mimeType);
    }
}
