package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;

import java.util.ArrayList;
import java.util.List;

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
}