package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.text.SimpleSubtitleDecoder;
import androidx.media3.extractor.text.Subtitle;
import androidx.media3.extractor.text.ssa.SsaParser;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 增强版 SSA/ASS 字幕解码器（替代 Media3 默认 SsaDecoder 的 MKV 内嵌路径）。
 *
 * <p>修复两个已知问题：
 *
 * <ol>
 *   <li><b>MKV 内嵌 ASS 列格式错位</b>：Media3 的 MatroskaExtractor 给 MKV 内嵌 ASS 轨的
 *       initializationData[0] 是 11 列 Format（含 ReadOrder），并为每个 sample 前置
 *       "Dialogue: 0:00:00:00,&lt;blockDuration&gt;," 假时间。而 mkvmerge 等工具实际写入
 *       MKV 的 Dialogue 数据是标准 10 列（Layer,Start,End,Style,Name,MarginL,MarginR,
 *       MarginV,Effect,Text，无 ReadOrder）——两者拼在一起列错位，导致 Media3 默认
 *       SsaParser 把绝大多数行判为 "fewer columns than format" 而跳过，表现为
 *       "ASS 字幕完全不显示"（SRT 不受影响）。这里改用与 mkvmerge 一致的 10 列 Format，
 *       并在 decode 时剥掉 Media3 注入的假时间前缀，恢复标准 10 列 Dialogue 行。
 *   <li><b>编码容错</b>：GBK/GB18030 编码的 ASS 自动转 UTF-8；UTF-16 BOM 自动转 UTF-8，
 *       避免乱码或解码失败。
 * </ol>
 */
@UnstableApi
public class EnhancedSsaDecoder extends SimpleSubtitleDecoder {

    private static final String TAG = "EnhancedSsaDecoder";

    private static final String DIALOGUE_PREFIX = "Dialogue: ";

    /** 与 mkvmerge 实际封装一致的 10 列 Format（Layer 开头，无 ReadOrder）。 */
    private static final byte[] MKV_DIALOGUE_FORMAT_10 =
            Util.getUtf8Bytes(
                    "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text");

    private final SsaParser parser;
    private final boolean fixMkvFormat;

    public EnhancedSsaDecoder(@Nullable List<byte[]> initializationData) {
        super(TAG);
        if (initializationData != null && initializationData.size() >= 2) {
            // MKV 内嵌 ASS：Media3 提供的 initializationData[0] 是 11 列 Format（含 ReadOrder），
            // 与 mkvmerge 实际写入的 10 字段 Dialogue 不匹配 → 替换为 10 列。
            List<byte[]> fixed = new ArrayList<>(initializationData);
            fixed.set(0, MKV_DIALOGUE_FORMAT_10);
            parser = new SsaParser(fixed);
            fixMkvFormat = true;
        } else {
            // 外挂 ASS：无初始化数据，原样交给 SsaParser（其内部从文件头解析 Format）。
            parser = new SsaParser(initializationData);
            fixMkvFormat = false;
        }
    }

    @Override
    protected Subtitle decode(byte[] data, int length, boolean reset) {
        if (reset) {
            parser.reset();
        }
        byte[] sample = ensureUtf8(data, length);
        if (fixMkvFormat) {
            sample = normalizeMkvSample(sample);
        }
        return parser.parseToLegacySubtitle(sample, /* offset= */ 0, sample.length);
    }

    /**
     * 剥掉 MatroskaExtractor 注入的假时间前缀，恢复标准 10 列 Dialogue 行。
     *
     * <p>Media3 前缀为 {@code "Dialogue: 0:00:00:00,<blockDuration>,"}，其后是 mkvmerge 原始
     * 10 字段（Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text）。检测前两个
     * 字段为时间码（H:MM:SS.cc）时，将其删除。
     */
    private static byte[] normalizeMkvSample(byte[] data) {
        String line = Util.fromUtf8Bytes(data);
        if (!line.startsWith(DIALOGUE_PREFIX)) {
            return data;
        }
        String body = line.substring(DIALOGUE_PREFIX.length());
        String[] parts = body.split(",", -1);
        if (parts.length > 2 && isTimecode(parts[0]) && isTimecode(parts[1])) {
            StringBuilder sb = new StringBuilder(DIALOGUE_PREFIX);
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) {
                    sb.append(',');
                }
                sb.append(parts[i]);
            }
            return Util.getUtf8Bytes(sb.toString());
        }
        return data;
    }

    private static boolean isTimecode(String s) {
        return s != null && s.matches("\\d+:\\d+:\\d+[.:]\\d+");
    }

    /** 编码容错：UTF-8 BOM 原样；UTF-16 BOM 转 UTF-8；非法 UTF-8 按 GB18030 转 UTF-8。 */
    private static byte[] ensureUtf8(byte[] data, int length) {
        if (length >= 3
                && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            return data;
        }
        if (length >= 2
                && (((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE)
                        || ((data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF))) {
            return new String(data, 0, length, StandardCharsets.UTF_16)
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (isValidUtf8(data, length)) {
            return data;
        }
        try {
            return new String(data, 0, length, Charset.forName("GB18030"))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return data;
        }
    }

    private static boolean isValidUtf8(byte[] data, int len) {
        int i = 0;
        while (i < len) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            int need;
            if ((b & 0xE0) == 0xC0) {
                need = 1;
            } else if ((b & 0xF0) == 0xE0) {
                need = 2;
            } else if ((b & 0xF8) == 0xF0) {
                need = 3;
            } else {
                return false;
            }
            if (i + need >= len) {
                return false;
            }
            for (int j = 1; j <= need; j++) {
                if ((data[i + j] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += need + 1;
        }
        return true;
    }
}
