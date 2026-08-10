package com.fongmi.android.tv.subtitle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 双语字幕合成器(A2S / WebHomeTV.A2S 分支)。
 *
 * 负责:解析 SRT / ASS / SSA / VTT 字幕文本为统一 cue 列表,
 * 将两条字幕按时间轴对齐合并为双语 SRT(主字幕 + 副字幕两行显示)。
 */
public final class BilingualSubtitleComposer {

    /** 对齐容差:副字幕起始时间与主字幕相差不超过该毫秒数才配对。 */
    public static final long MATCH_WINDOW_MS = 500;

    /** 统一的字幕行。 */
    public static final class Cue {
        public final long startMs;
        public final long endMs;
        public final String text;

        Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text == null ? "" : text;
        }
    }

    private static final Pattern SRT_TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[,.]?(\\d{1,3})?");
    private static final Pattern ASS_TIME = Pattern.compile(
            "(\\d+):(\\d{1,2}):(\\d{1,2})[.](\\d{1,2})");
    private static final Pattern VTT_TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[.](\\d{1,3})");

    private BilingualSubtitleComposer() {
    }

    // ==================== 解析 ====================

    /**
     * 按 mime 解析字幕文本为 cue 列表。
     *
     * @param mime  EmbeddedSubtitleExtractor 归一化后的 mime
     * @param data  字幕文件字节
     */
    @NonNull
    public static List<Cue> parse(String mime, byte[] data) {
        if (data == null || data.length == 0) return new ArrayList<>();
        String content = new String(data, StandardCharsets.UTF_8);
        if (content.indexOf('\uFFFD') >= 0) {
            content = new String(data, StandardCharsets.UTF_16LE);
        }
        if (mime != null && mime.contains("ssa")) return parseAss(content);
        if (mime != null && mime.contains("vtt")) return parseVtt(content);
        return parseSrt(content);
    }

    @NonNull
    static List<Cue> parseSrt(String content) {
        List<Cue> cues = new ArrayList<>();
        String[] blocks = content.split("\\r?\\n\\s*\\r?\\n");
        for (String block : blocks) {
            String[] lines = block.split("\\r?\\n");
            if (lines.length < 2) continue;
            int timeIndex = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timeIndex = i;
                    break;
                }
            }
            if (timeIndex < 0) continue;
            long[] range = parseSrtTimeRange(lines[timeIndex]);
            if (range == null) continue;
            StringBuilder text = new StringBuilder();
            for (int i = timeIndex + 1; i < lines.length; i++) {
                if (text.length() > 0) text.append('\n');
                text.append(lines[i].trim());
            }
            if (text.length() == 0) continue;
            cues.add(new Cue(range[0], range[1], text.toString()));
        }
        return cues;
    }

    @NonNull
    static List<Cue> parseAss(String content) {
        List<Cue> cues = new ArrayList<>();
        boolean inEvents = false;
        String[] formatColumns = null;
        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("[")) {
                inEvents = lower.startsWith("[events]");
                formatColumns = null;
                continue;
            }
            if (!inEvents) continue;
            if (lower.startsWith("format:")) {
                formatColumns = line.substring(7).trim().split("\\s*,\\s*");
                continue;
            }
            if (!lower.startsWith("dialogue:")) continue;
            String body = line.substring(9).trim();
            String[] fields = splitAssDialogue(body, formatColumns == null ? 10 : formatColumns.length);
            if (fields.length < 10) continue;
            long[] range = parseAssTimeRange(fields[1]);
            if (range == null) continue;
            String text = cleanAssText(fields[9]);
            if (text.isEmpty()) continue;
            cues.add(new Cue(range[0], range[1], text));
        }
        return cues;
    }

    @NonNull
    static List<Cue> parseVtt(String content) {
        List<Cue> cues = new ArrayList<>();
        String[] blocks = content.split("\\r?\\n\\s*\\r?\\n");
        for (String block : blocks) {
            String[] lines = block.split("\\r?\\n");
            int timeIndex = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timeIndex = i;
                    break;
                }
            }
            if (timeIndex < 0) continue;
            long[] range = parseVttTimeRange(lines[timeIndex]);
            if (range == null) continue;
            StringBuilder text = new StringBuilder();
            for (int i = timeIndex + 1; i < lines.length; i++) {
                if (text.length() > 0) text.append('\n');
                text.append(lines[i].trim());
            }
            if (text.length() == 0) continue;
            cues.add(new Cue(range[0], range[1], text.toString()));
        }
        return cues;
    }

    // ==================== 合并 ====================

    /**
     * 合成双语 SRT 内容。
     *
     * @param primary   主字幕(显示在下方的语言)
     * @param secondary 副字幕(显示在上方的语言)
     * @return UTF-8 编码的 SRT 字节
     */
    @NonNull
    public static byte[] composeBilingualSrt(@NonNull List<Cue> primary, @NonNull List<Cue> secondary) {
        StringBuilder sb = new StringBuilder(primary.size() * 64);
        int index = 1;
        for (Cue cue : primary) {
            Cue match = matchSecondary(secondary, cue);
            if (match == null) continue;
            sb.append(index++).append('\n');
            sb.append(formatSrtTime(cue.startMs)).append(" --> ").append(formatSrtTime(cue.endMs)).append('\n');
            sb.append(cue.text);
            sb.append('\n');
            sb.append(match.text);
            sb.append("\n\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 在副字幕中查找与主字幕起始时间最接近(±500ms)的行。 */
    @Nullable
    private static Cue matchSecondary(List<Cue> secondary, Cue primary) {
        if (secondary == null || secondary.isEmpty()) return null;
        Cue best = null;
        long bestGap = Long.MAX_VALUE;
        for (Cue candidate : secondary) {
            long gap = Math.abs(candidate.startMs - primary.startMs);
            if (gap < bestGap) {
                bestGap = gap;
                best = candidate;
            }
        }
        return bestGap <= MATCH_WINDOW_MS ? best : null;
    }

    // ==================== 工具 ====================

    private static long[] parseSrtTimeRange(String line) {
        Matcher m = SRT_TIME.matcher(line);
        long start = -1, end = -1;
        if (m.find()) start = toMs(m, m.group(4) == null ? 0 : Integer.parseInt(m.group(4)) * 10);
        if (m.find()) end = toMs(m, m.group(4) == null ? 0 : Integer.parseInt(m.group(4)) * 10);
        return (start >= 0 && end >= 0) ? new long[]{start, end} : null;
    }

    private static long[] parseAssTimeRange(String time) {
        Matcher m = ASS_TIME.matcher(time);
        long start = -1, end = -1;
        if (m.find()) start = toMs(m, Integer.parseInt(m.group(4)) * 10);
        if (m.find()) end = toMs(m, Integer.parseInt(m.group(4)) * 10);
        return (start >= 0 && end >= 0) ? new long[]{start, end} : null;
    }

    private static long[] parseVttTimeRange(String line) {
        Matcher m = VTT_TIME.matcher(line);
        long start = -1, end = -1;
        if (m.find()) start = toMs(m, Integer.parseInt(m.group(4)));
        if (m.find()) end = toMs(m, Integer.parseInt(m.group(4)));
        return (start >= 0 && end >= 0) ? new long[]{start, end} : null;
    }

    private static long toMs(Matcher m, int millis) {
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        int s = Integer.parseInt(m.group(3));
        return ((long) h * 3600 + min * 60 + s) * 1000L + millis;
    }

    /** ASS Dialogue 按 Format 列数切分,保留 Text 列中的逗号。 */
    private static String[] splitAssDialogue(String body, int columns) {
        String[] fields = body.split(",", Math.max(10, columns));
        return fields;
    }

    /** 去掉 ASS 样式标签,保留换行。 */
    private static String cleanAssText(String raw) {
        String text = raw.replaceAll("\\{[^}]*\\}", "");
        text = text.replace("\\N", "\n").replace("\\n", "\n");
        text = text.replace("\\h", " ");
        return text.trim();
    }

    private static String formatSrtTime(long ms) {
        long total = Math.max(0, ms);
        long h = total / 3600000;
        long m = (total % 3600000) / 60000;
        long s = (total % 60000) / 1000;
        long millis = total % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, millis);
    }
}
