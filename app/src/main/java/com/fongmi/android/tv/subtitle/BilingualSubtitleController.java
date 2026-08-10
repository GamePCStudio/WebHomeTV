package com.fongmi.android.tv.subtitle;

import android.util.Log;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动双语字幕控制器(A2S / WebHomeTV.A2S 分支)。
 *
 * 播放开始时触发:检测媒体内嵌的中文 + 英文字幕轨,
 * 提取 → 时间轴对齐 → 合成双语 SRT → 注入播放器并自动选中。
 *
 * 设置入口:设置 → 播放 → 自动双语字幕(关闭 / 中英 / 英中)。
 */
public final class BilingualSubtitleController {

    private static final String TAG = "BilingualSubtitle";
    private static final Map<String, Boolean> INFLIGHT = new ConcurrentHashMap<>();

    private BilingualSubtitleController() {
    }

    /**
     * 播放/换源/切集入口,由 VideoActivity.onTracksChanged() 调用。
     *
     * @param player 播放器管理器
     * @param key    当前剧集标识(historyKey 等),用于去重与缓存命名
     */
    public static void onPlaybackStarted(PlayerManager player, String key) {
        int mode = PlayerSetting.getAutoBilingualSubtitle();
        if (mode == PlayerSetting.AUTO_BILINGUAL_OFF) return;
        if (player == null || key == null || key.isEmpty()) return;
        String dedupe = key + "#" + mode;
        if (INFLIGHT.putIfAbsent(dedupe, Boolean.TRUE) != null) return;
        Task.execute(() -> handle(player, key, mode, dedupe));
    }

    private static void handle(PlayerManager player, String key, int mode, String dedupe) {
        try {
            String url = player.getUrl();
            if (url == null || url.isEmpty()) return;
            List<EmbeddedSubtitleExtractor.SubtitleTrack> tracks =
                    EmbeddedSubtitleExtractor.extractTextTracks(App.get(), url, player.getHeaders());
            if (tracks.isEmpty()) return;

            EmbeddedSubtitleExtractor.SubtitleTrack zh = null;
            EmbeddedSubtitleExtractor.SubtitleTrack en = null;
            for (EmbeddedSubtitleExtractor.SubtitleTrack track : tracks) {
                if (!track.isText()) continue;
                if (isChinese(track)) zh = track;
                else if (isEnglish(track)) en = track;
            }
            if (zh == null || en == null) return;

            List<BilingualSubtitleComposer.Cue> primary = BilingualSubtitleComposer.parse(zh.mime, zh.data);
            List<BilingualSubtitleComposer.Cue> secondary = BilingualSubtitleComposer.parse(en.mime, en.data);
            if (primary.isEmpty() || secondary.isEmpty()) return;
            if (mode == PlayerSetting.AUTO_BILINGUAL_EN_ZH) {
                List<BilingualSubtitleComposer.Cue> tmp = primary;
                primary = secondary;
                secondary = tmp;
            }

            byte[] srt = BilingualSubtitleComposer.composeBilingualSrt(primary, secondary);
            File dir = new File(App.get().getCacheDir(), "bilingual");
            if (!dir.exists() && !dir.mkdirs()) return;
            File out = new File(dir, safeName(key) + ".srt");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(srt);
            }

            String path = out.getAbsolutePath();
            App.post(() -> inject(player, path));
        } catch (Exception e) {
            Log.e(TAG, "bilingual subtitle failed: " + e.getMessage());
        } finally {
            INFLIGHT.remove(dedupe);
        }
    }

    private static void inject(PlayerManager player, String path) {
        try {
            if (player == null || path == null) return;
            Sub sub = Sub.from(path);
            sub.setFlag(androidx.media3.common.C.SELECTION_FLAG_DEFAULT);
            player.setSub(sub);
            Notify.show(App.get().getString(com.fongmi.android.tv.R.string.player_auto_bilingual_ready));
        } catch (Exception e) {
            Log.e(TAG, "inject failed: " + e.getMessage());
        }
    }

    private static boolean isChinese(EmbeddedSubtitleExtractor.SubtitleTrack track) {
        String lang = track.language == null ? "" : track.language.toLowerCase(Locale.ROOT);
        return lang.startsWith("zh") || lang.startsWith("chi") || lang.startsWith("zho")
                || lang.contains("cmn") || lang.contains("yue") || lang.contains("wuu")
                || lang.contains("chs") || lang.contains("cht") || lang.contains("hans") || lang.contains("hant")
                || lang.contains("chinese");
    }

    private static boolean isEnglish(EmbeddedSubtitleExtractor.SubtitleTrack track) {
        String lang = track.language == null ? "" : track.language.toLowerCase(Locale.ROOT);
        return lang.startsWith("en") || lang.contains("eng") || lang.contains("english");
    }

    private static String safeName(String key) {
        String name = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 80) name = name.substring(0, 80);
        return name.isEmpty() ? "bilingual" : name;
    }
}
