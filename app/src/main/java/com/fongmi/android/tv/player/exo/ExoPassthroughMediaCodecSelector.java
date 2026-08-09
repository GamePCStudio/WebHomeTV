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
     * 合成 raw decoder 信息。capabilities 传 null：media3 的 isFormatSupported 对 null
     * capabilities 直接视为支持（与官方对 MTK R9 设备的 workaround 一致）。
     */
    private static MediaCodecInfo syntheticRawDecoder(String mimeType) {
        return MediaCodecInfo.newInstance(
                SYNTHETIC_RAW_DECODER_NAME,
                mimeType,
                mimeType,
                /* capabilities= */ null,
                /* hardwareAccelerated= */ false,
                /* softwareOnly= */ true,
                /* vendor= */ false,
                /* forceDisableAdaptive= */ false,
                /* forceSecure= */ false);
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
