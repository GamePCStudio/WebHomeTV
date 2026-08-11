package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.text.SubtitleDecoderFactory;
import androidx.media3.extractor.text.SubtitleDecoder;

/**
 * 自定义字幕解码器工厂：仅对 SSA/ASS（text/x-ssa）使用 {@link EnhancedSsaDecoder}（修复 MKV
 * 内嵌 ASS 列格式错位与编码问题），其余字幕格式完全委托 Media3 默认工厂，行为不变。
 */
@UnstableApi
public final class EnhancedSubtitleDecoderFactory implements SubtitleDecoderFactory {

    @Override
    public boolean supportsFormat(Format format) {
        return SubtitleDecoderFactory.DEFAULT.supportsFormat(format);
    }

    @Override
    public SubtitleDecoder createDecoder(Format format) {
        @Nullable String mimeType = format.sampleMimeType;
        if (mimeType != null && MimeTypes.TEXT_SSA.equals(mimeType)) {
            return new EnhancedSsaDecoder(format.initializationData);
        }
        return SubtitleDecoderFactory.DEFAULT.createDecoder(format);
    }
}
