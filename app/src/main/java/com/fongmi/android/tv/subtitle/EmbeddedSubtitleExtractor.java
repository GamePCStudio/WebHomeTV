package com.fongmi.android.tv.subtitle;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.MatroskaExtractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内嵌字幕轨提取器(A2S / WebHomeTV.A2S 分支)。
 *
 * 独立于播放器解析媒体文件(MKV 优先),通过 Media3 MatroskaExtractor 管线
 * 截获所有文本字幕轨的原始字节,用于自动双语字幕合成。
 *
 * 只支持文本轨(SRT / ASS / SSA / VTT);图形轨(PGS / VobSub / DVB)会被跳过。
 */
@UnstableApi
public final class EmbeddedSubtitleExtractor {

    /** 提取结果:一条文本字幕轨的完整内容。 */
    public static final class SubtitleTrack {
        /** 轨道语言标签(原始值,可能为空)。 */
        public final String language;
        /** 归一化后的字幕格式:text/ssa | application/x-subrip | text/vtt,空表示无法识别。 */
        public final String mime;
        /** 完整字幕文件字节(头 + 全部样本)。 */
        public final byte[] data;

        SubtitleTrack(String language, String mime, byte[] data) {
            this.language = language == null ? "" : language;
            this.mime = mime == null ? "" : mime;
            this.data = data;
        }

        public boolean isText() {
            return isAss() || isSrt() || isVtt();
        }

        public boolean isAss() {
            return mime.contains("ssa");
        }

        public boolean isSrt() {
            return mime.contains("subrip") || mime.contains("srt");
        }

        public boolean isVtt() {
            return mime.contains("vtt");
        }
    }

    private EmbeddedSubtitleExtractor() {
    }

    /**
     * 解析媒体并返回全部文本字幕轨。
     *
     * @param context 上下文
     * @param url     媒体地址(file:// 或 http(s)://)
     * @param headers 附加请求头,可为空
     * @return 文本字幕轨列表(可能为空)
     * @throws IOException 读取失败
     */
    @NonNull
    public static List<SubtitleTrack> extractTextTracks(Context context, String url, @Nullable Map<String, String> headers) throws IOException {
        List<SubtitleTrack> tracks = new ArrayList<>();
        if (url == null || url.isEmpty()) return tracks;

        Uri uri = Uri.parse(url);
        DataSource dataSource = buildDataSource(context, headers);
        dataSource.open(new DataSpec.Builder().setUri(uri).build());
        try {
            ExtractorInput input = new DefaultExtractorInput(dataSource, 0, C.LENGTH_UNSET);
            MatroskaExtractor extractor = new MatroskaExtractor();
            Collector collector = new Collector();
            extractor.init(collector);
            PositionHolder holder = new PositionHolder();
            int result;
            while ((result = extractor.read(input, holder)) != Extractor.RESULT_END_OF_INPUT) {
                if (result == Extractor.RESULT_SEEK) {
                    input.seek(holder.position);
                }
            }
            tracks.addAll(collector.build());
        } finally {
            dataSource.close();
        }
        return tracks;
    }

    private static DataSource buildDataSource(Context context, @Nullable Map<String, String> headers) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        if (headers != null && !headers.isEmpty()) {
            http.setDefaultRequestProperties(headers);
        }
        DefaultDataSource.Factory factory = new DefaultDataSource.Factory(context, http);
        return factory.createDataSource();
    }

    /** 从 Format 恢复字幕 mime(兼容 ass-media 的 application/x-media3-cues 归一化)。 */
    private static String recoverMime(Format format) {
        if (format == null) return "";
        String mime = format.sampleMimeType;
        if (mime == null) return "";
        String lower = mime.toLowerCase(Locale.ROOT);
        if (lower.contains("ssa")) return MimeTypes.TEXT_SSA;
        if (lower.contains("subrip") || lower.contains("srt")) return MimeTypes.APPLICATION_SUBRIP;
        if (lower.contains("vtt")) return MimeTypes.TEXT_VTT;
        if (lower.contains("pgs")) return "application/pgs";
        if (lower.contains("vobsub")) return MimeTypes.APPLICATION_VOBSUB;
        if (lower.contains("dvbsub")) return MimeTypes.APPLICATION_DVBSUBS;
        // ass-media 归一化轨:原始 mime 保存在 codecs 字段
        if ("application/x-media3-cues".equals(lower) && format.codecs != null) {
            String codecs = format.codecs.toLowerCase(Locale.ROOT);
            if (codecs.contains("ssa")) return MimeTypes.TEXT_SSA;
            if (codecs.contains("subrip") || codecs.contains("srt")) return MimeTypes.APPLICATION_SUBRIP;
            if (codecs.contains("vtt")) return MimeTypes.TEXT_VTT;
            if (codecs.contains("pgs")) return "application/pgs";
        }
        return mime;
    }

    private static boolean isTextMime(String mime) {
        return mime.contains("ssa") || mime.contains("subrip") || mime.contains("srt") || mime.contains("vtt");
    }

    /** 收集器:只保留文本轨。 */
    private static final class Collector implements ExtractorOutput {

        private final Map<Integer, TrackCollector> collectors = new HashMap<>();

        @Override
        public TrackOutput track(int id, int type) {
            TrackCollector collector = new TrackCollector(type);
            collectors.put(id, collector);
            return collector;
        }

        @Override
        public void endTracks() {
        }

        @Override
        public void seekMap(SeekMap seekMap) {
        }

        List<SubtitleTrack> build() {
            List<SubtitleTrack> result = new ArrayList<>();
            for (TrackCollector c : collectors.values()) {
                SubtitleTrack track = c.build();
                if (track != null) result.add(track);
            }
            return result;
        }
    }

    /** 单轨收集:累积 Format + 全部样本字节。 */
    private static final class TrackCollector implements TrackOutput {

        private final int type;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        @Nullable
        private Format format;

        TrackCollector(int type) {
            this.type = type;
        }

        @Override
        public void format(Format format) {
            this.format = format;
        }

        @Override
        public int sampleData(ExtractorInput input, int length, boolean allowEndOfInput) throws IOException {
            byte[] tmp = new byte[Math.max(0, length)];
            int read = input.read(tmp, 0, length);
            if (read > 0) {
                buffer.write(tmp, 0, read);
            }
            return read == C.RESULT_END_OF_INPUT ? C.RESULT_END_OF_INPUT : read;
        }

        @Override
        public void sampleData(ByteBuffer data, int length) {
            byte[] tmp = new byte[Math.max(0, length)];
            data.get(tmp, 0, Math.min(length, data.remaining()));
            buffer.write(tmp, 0, length);
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, @Nullable CryptoData cryptoData) {
            // 文本轨样本已通过 sampleData 累积,无需额外处理
        }

        @Nullable
        SubtitleTrack build() {
            if (format == null || buffer.size() == 0) return null;
            String mime = recoverMime(format);
            if (!isTextMime(mime)) return null;
            return new SubtitleTrack(format.language, mime, buffer.toByteArray());
        }
    }
}
