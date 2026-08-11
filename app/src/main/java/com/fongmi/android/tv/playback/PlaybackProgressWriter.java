package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.PlaybackDeleteTombstone;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.db.dao.HistoryDao;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlaybackProgressWriter {

    private PlaybackProgressWriter() {
    }

    public static PlaybackProgressApplyResult applyFromLocalApi(PlaybackProgressInput input) {
        if (!ViewingRecordSyncStore.isEnabled()) return PlaybackProgressApplyResult.failed(input, "观影记录同步未开启");
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) return PlaybackProgressApplyResult.failed(input, "本机 API 修改未开启");
        return applyInternal(input);
    }

    public static PlaybackProgressBatchResult applyFromLocalApi(List<PlaybackProgressInput> inputs) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (!ViewingRecordSyncStore.isEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "观影记录同步未开启"));
            return batch;
        }
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "本机 API 修改未开启"));
            return batch;
        }
        if (inputs == null || inputs.isEmpty()) return batch;
        for (PlaybackProgressInput input : inputs) batch.add(applyInternal(input));
        return batch;
    }

    private static synchronized PlaybackProgressApplyResult applyInternal(PlaybackProgressInput input) {
        if (Setting.isIncognito()) return PlaybackProgressApplyResult.failed(input, "隐身模式不允许写入");
        if (input == null) return PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "请求体不能为空");
        input.normalize();
        int cid = targetCid(input);
        if (cid <= 0) return PlaybackProgressApplyResult.skipped(input, input.historyKey, "接口不匹配", 0);
        String error = input.validate();
        if (!TextUtils.isEmpty(error)) return PlaybackProgressApplyResult.failed(input, error);
        if (input.updatedAt <= 0) input.updatedAt = System.currentTimeMillis();
        long deletedAt = PlaybackDeleteTombstoneStore.latest(PlaybackDeleteTombstoneStore.snapshot(), input.configKey, cid, input.historyKey, input.siteKey, input.vodId);
        if (deletedAt > 0 && input.updatedAt <= deletedAt) return PlaybackProgressApplyResult.skipped(input, input.historyKey, "记录已被删除", deletedAt);
        String requestedKey = input.targetHistoryKey(cid);
        History local = findLocal(cid, input, requestedKey);
        String key = local == null ? requestedKey : local.getKey();
        History history = local == null ? new History() : local.copy();
        history.setKey(key);
        history.setCid(cid);
        history.setVodName(input.vodName);
        history.setVodPic(input.vodPic);
        history.setVodFlag(input.flag);
        history.setVodRemarks(input.episodeName);
        history.setEpisodeUrl(input.episodeUrl);
        history.setPosition(input.positionMs);
        history.setDuration(input.durationMs);
        history.setSpeed(input.speed <= 0 ? 1f : input.speed);
        history.setCreateTime(input.updatedAt);
        AppDatabase.get().getHistoryDao().insertOrUpdate(history);
        RefreshEvent.history();
        return local == null ? PlaybackProgressApplyResult.created(input, history.getKey()) : PlaybackProgressApplyResult.updated(input, history.getKey());
    }

    private static History findLocal(int cid, PlaybackProgressInput input, String key) {
        History exact = AppDatabase.get().getHistoryDao().find(cid, key);
        if (exact != null) return exact;
        String baseKey = input.siteKey + AppDatabase.SYMBOL + input.vodId;
        History base = AppDatabase.get().getHistoryDao().find(cid, baseKey);
        if (base != null) return base;
        List<History> items = AppDatabase.get().getHistoryDao().findByKeyPrefix(cid, baseKey + AppDatabase.SYMBOL);
        if (items.isEmpty()) return null;
        return bestEpisodeMatch(items, input);
    }

    private static History bestEpisodeMatch(List<History> items, PlaybackProgressInput input) {
        for (History item : items) if (!TextUtils.isEmpty(input.episodeUrl) && TextUtils.equals(input.episodeUrl, item.getEpisodeUrl())) return item;
        for (History item : items) if (!TextUtils.isEmpty(input.flag) && TextUtils.equals(input.flag, item.getVodFlag()) && TextUtils.equals(input.episodeName, item.getVodRemarks())) return item;
        for (History item : items) if (TextUtils.equals(input.episodeName, item.getVodRemarks())) return item;
        return items.get(0);
    }

    private static int targetCid(PlaybackProgressInput input) {
        int cid = PlaybackConfigIdentity.cidForKey(input.configKey);
        if (cid > 0) return cid;
        if (!TextUtils.isEmpty(input.configKey)) return 0;
        return input.cid > 0 ? input.cid : VodConfig.getCid();
    }

    public static PlaybackProgressBatchResult deleteFromLocalApi(List<PlaybackProgressDeleteInput> inputs) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (!ViewingRecordSyncStore.isEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "观影记录同步未开启"));
            return batch;
        }
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "本机 API 修改未开启"));
            return batch;
        }
        if (inputs == null || inputs.isEmpty()) return batch;
        for (PlaybackProgressDeleteInput input : inputs) batch.add(deleteInternal(input));
        return batch;
    }

    private static synchronized PlaybackProgressApplyResult deleteInternal(PlaybackProgressDeleteInput input) {
        if (Setting.isIncognito()) return PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "隐身模式不允许清理");
        if (input == null) return PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "请求体不能为空");
        input.normalize();
        int cid = targetCid(input);
        if (cid <= 0) return PlaybackProgressApplyResult.skipped(input, input.historyKey, "接口不匹配");
        if (!input.isAllScope() && !input.isSiteScope() && TextUtils.isEmpty(input.historyKey)
                && (TextUtils.isEmpty(input.siteKey) || TextUtils.isEmpty(input.vodId))) {
            return PlaybackProgressApplyResult.failed(input, "historyKey、siteKey+vodId或siteKey不能为空");
        }
        if (input.isAllScope() && !input.confirm) return PlaybackProgressApplyResult.failed(input, "全量清理需要confirm=true");
        if (input.isSiteScope() && TextUtils.isEmpty(input.siteKey)) return PlaybackProgressApplyResult.failed(input, "按站点清理需要siteKey");
        if (input.deletedAt <= 0) input.deletedAt = System.currentTimeMillis();

        PlaybackDeleteTombstoneStore.record(input, cid);
        List<PlaybackDeleteTombstone> tombstones = PlaybackDeleteTombstoneStore.snapshot();
        HistoryDao dao = AppDatabase.get().getHistoryDao();
        List<History> candidates = candidates(dao, cid, input);
        int affected = 0;
        for (History history : candidates) {
            long deletedAt = PlaybackDeleteTombstoneStore.latest(tombstones, input.configKey, cid,
                    history.getKey(), history.getSiteKey(), history.getVodId());
            if (history.getCreateTime() > deletedAt) continue;
            int count = dao.delete(cid, history.getKey());
            if (count <= 0) continue;
            AppDatabase.get().getTrackDao().delete(history.getKey());
            affected += count;
        }
        if (affected > 0) RefreshEvent.history();
        if (affected > 0) return PlaybackProgressApplyResult.deleted(input, resultKey(input), affected);
        return PlaybackProgressApplyResult.skipped(input, resultKey(input), "本地记录不存在");
    }

    private static List<History> candidates(HistoryDao dao, int cid, PlaybackProgressDeleteInput input) {
        Map<String, History> result = new LinkedHashMap<>();
        if (!TextUtils.isEmpty(input.historyKey)) {
            History exact = dao.find(cid, input.historyKey);
            if (exact != null) result.put(exact.getKey(), exact);
            if (result.isEmpty() && !TextUtils.isEmpty(input.siteKey) && !TextUtils.isEmpty(input.vodId)) addByItem(dao, cid, input, result);
        } else if (!TextUtils.isEmpty(input.siteKey) && !TextUtils.isEmpty(input.vodId)) {
            addByItem(dao, cid, input, result);
        } else {
            for (History history : dao.findAll(cid)) result.put(history.getKey(), history);
        }
        if (input.isSiteScope() && !TextUtils.isEmpty(input.siteKey)) {
            result.entrySet().removeIf(entry -> !PlaybackDeleteTombstoneStore.normalize(entry.getValue().getSiteKey()).equals(PlaybackDeleteTombstoneStore.normalize(input.siteKey)));
        }
        if (!input.isAllScope() && !input.isSiteScope() && TextUtils.isEmpty(input.historyKey)
                && (TextUtils.isEmpty(input.siteKey) || TextUtils.isEmpty(input.vodId))) result.clear();
        return new ArrayList<>(result.values());
    }

    private static void addByItem(HistoryDao dao, int cid, PlaybackProgressDeleteInput input, Map<String, History> result) {
        String baseKey = input.siteKey + AppDatabase.SYMBOL + input.vodId;
        History base = dao.find(cid, baseKey);
        if (base != null) result.put(base.getKey(), base);
        for (History item : dao.findByKeyPrefix(cid, baseKey + AppDatabase.SYMBOL)) result.put(item.getKey(), item);
    }

    private static String resultKey(PlaybackProgressDeleteInput input) {
        if (!TextUtils.isEmpty(input.historyKey)) return input.historyKey;
        if (!TextUtils.isEmpty(input.siteKey) && !TextUtils.isEmpty(input.vodId)) return input.siteKey + AppDatabase.SYMBOL + input.vodId;
        return "";
    }

    private static int targetCid(PlaybackProgressDeleteInput input) {
        int cid = PlaybackConfigIdentity.cidForKey(input.configKey);
        if (cid > 0) return cid;
        if (!TextUtils.isEmpty(input.configKey)) return 0;
        if (input.cid > 0) return input.cid;
        try {
            int index = input.historyKey.lastIndexOf(AppDatabase.SYMBOL);
            if (index >= 0) return Integer.parseInt(input.historyKey.substring(index + AppDatabase.SYMBOL.length()));
        } catch (Exception ignored) {
        }
        return VodConfig.getCid();
    }
}