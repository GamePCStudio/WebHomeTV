package com.fongmi.android.tv.player;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.media3.ui.danmaku.DanmakuController;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.IjkPlayerEngine;
import com.fongmi.android.tv.player.engine.MpvPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardBufferPolicy;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardController;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardEligibility;
import com.fongmi.android.tv.player.exo.ForwardBufferTrend;
import com.fongmi.android.tv.player.effect.video.ExoVideoEffectController;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.VideoSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlayerManager implements ParseCallback {

    private final Runnable runnable;
    private final Callback callback;
    private DanmakuController danmakuController;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private int playerType;

    private boolean initTrack;
    private int retry;
    private int lastListenerState = Player.STATE_IDLE;

    private final Runnable networkProtectionRunnable = this::evaluateNetworkProtection;
    private final ExoNetworkGuardController networkProtectionController = new ExoNetworkGuardController();
    private final ForwardBufferTrend networkProtectionTrend = new ForwardBufferTrend();
    private ExoNetworkGuardController.State networkProtectionState = ExoNetworkGuardController.State.NORMAL;
    private ExoNetworkGuardController.ProtectionTier networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
    private String networkProtectionReason;
    private float userPlaybackSpeed = 1f;
    private float networkProtectionSpeed = 1f;
    private float networkProtectionSupportedSpeed = 1f;
    private long networkProtectionMediaBitrate;
    private int networkProtectionRebufferCount;

    private final ExoVideoEffectController videoEffectController = new ExoVideoEffectController();

    public PlayerManager(Callback callback) {
        this.runnable = () -> callback.onError(ResUtil.getString(R.string.error_play_timeout));
        this.playerType = PlayerSetting.getPlayer();
        this.engine = buildEngine(playerType, PlayerEngine.HARD);
        this.player = engine.getPlayer();
        this.callback = callback;
    }

    public void release() {
        if (player == null && engine == null) return;
        stopParse();
        App.removeCallbacks(runnable);
        App.removeCallbacks(networkProtectionRunnable);
        if (player != null) player.removeListener(listener);
        clearVideoEffect();
        if (engine != null) engine.release();
        engine = null;
        player = null;
    }

    public Player getPlayer() {
        return player;
    }

    public Tracks getCurrentTracks() {
        return engine.getCurrentTracks();
    }

    public List<MediaEdition> getCurrentMediaEditions() {
        return engine.getCurrentMediaEditions();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : null;
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return engine.isLive();
    }

    public boolean isVod() {
        return engine.isVod();
    }

    public boolean haveTrack(int type) {
        return engine.haveTrack(type);
    }

    public boolean haveTitle() {
        return engine.haveTitle();
    }

    public boolean haveDanmaku() {
        return getDanmakus() != null && getDanmakus().stream().anyMatch(Danmaku::isSelected);
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getSpeedText() {
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String getDecodeText() {
        return engine.getDecodeText();
    }

    public String getPositionTime(long delta) {
        long time = Math.max(0, Math.min(getPosition() + delta, Math.max(0, getDuration())));
        return Util.timeMs(time);
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (spec != null) spec.setSub(sub);
        setMediaItem();
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        setMediaItem();
    }

    public void setTitle(MediaEdition edition) {
        if (edition == null) return;
        if (playerType == PlayerSetting.MPV && engine.selectEdition(edition)) return;
        if (spec != null) spec.setUrl(spec.getUri().buildUpon().fragment("edition=" + edition.index).build().toString());
        if (engine.selectEdition(edition)) return;
        setMediaItem();
        seekTo(0);
    }

    public int getPlayerType() {
        return playerType;
    }

    public String getPlayerText() {
        String[] names = ResUtil.getStringArray(R.array.select_player_kernel);
        int index = Math.min(Math.max(playerType, 0), names.length - 1);
        return names[index];
    }

    public boolean canSetVideoSetting() {
        return playerType == PlayerSetting.EXO && engine.supportsVideoEffects();
    }

    public void refreshVideoSetting() {
        if (!canSetVideoSetting()) return;
        videoEffectController.apply((ExoPlayer) engine.getPlayer(), VideoSetting.getAppliedProfile());
    }

    public void clearVideoEffect() {
        if (engine != null && engine.getPlayer() instanceof ExoPlayer exo) videoEffectController.clear(exo);
    }

    public void togglePlayer() {
        switchPlayer(PlayerSetting.nextPlayer(playerType));
    }

    public void switchPlayer(int type) {
        type = PlayerSetting.sanitizePlayer(type);
        if (type == playerType) return;
        long position = player != null ? Math.max(0, player.getCurrentPosition()) : 0;
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        int decode = engine != null ? engine.getDecode() : PlayerEngine.HARD;
        if (player != null) player.removeListener(listener);
        if (engine != null) engine.release();
        PlayerSetting.putPlayer(type);
        playerType = type;
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        engine.setRepeatOne(repeat);
        setSpeed(speed);
        callback.onPlayerRebuild(player);
        if (spec != null && spec.getUrl() != null) {
            setMediaItem();
            if (position > 0) seekTo(position);
        }
    }

    private PlayerEngine buildEngine(int type, int decode) {
        return switch (type) {
            case PlayerSetting.IJK -> new IjkPlayerEngine(decode, listener);
            case PlayerSetting.MPV -> new MpvPlayerEngine(decode, listener, (w, h) -> videoSize = new VideoSize(w, h));
            default -> new ExoPlayerEngine(decode, listener);
        };
    }

    public static MediaMetadata buildMetadata(String title, String artist, String artUri) {
        Uri artwork = TextUtils.isEmpty(artUri) ? null : Uri.parse(artUri);
        return new MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build();
    }

    public void setMetadata(MediaMetadata data) {
        if (spec != null) spec.setMetadata(data);
        engine.setMetadata(data);
    }

    public void setDanmakuController(DanmakuController controller) {
        danmakuController = controller;
        danmakuController.setOkHttpClient(OkHttp.player());
        danmakuController.setConfig(DanmakuSetting.getConfig());
    }

    public void setDanmakuConfig(DanmakuConfig config) {
        danmakuController.setConfig(config);
    }

    public void setDanmakuEnabled(boolean enabled) {
        danmakuController.setEnabled(enabled);
    }

    public void sendDanmaku(String text) {
        danmakuController.sendNow(text);
    }

    public String setSpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        userPlaybackSpeed = speed;
        resetNetworkProtectionSession("user-speed");
        if (Math.abs(speed - 1f) < 0.001f) scheduleNetworkProtection(0);
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
        return getSpeedText();
    }

    public String addSpeed() {
        float speed = getSpeed();
        float addon = speed >= 2 ? 1f : 0.25f;
        speed = speed >= 5 ? 0.25f : Math.min(speed + addon, 5.0f);
        return setSpeed(speed);
    }

    public String addSpeed(float value) {
        return setSpeed(Math.min(getSpeed() + value, 5));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.max(getSpeed() - value, 0.25f));
    }

    public String toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? PlayerSetting.getSpeed() : 1);
    }

    private void applyEffectiveSpeed(float speed, String reason) {
        if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return;
        float current = player.getPlaybackParameters().speed;
        float next = roundThousandth(speed);
        if (Math.abs(current - next) < 0.0005f) return;
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(next));
        SpiderDebug.log("player-guard", "applyEffectiveSpeed reason=%s current=%.3f next=%.3f", reason, current, next);
    }

    private void resetNetworkProtectionSession(String reason) {
        App.removeCallbacks(networkProtectionRunnable);
        networkProtectionController.reset();
        networkProtectionTrend.reset();
        networkProtectionState = ExoNetworkGuardController.State.NORMAL;
        networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
        networkProtectionReason = reason;
        networkProtectionSpeed = 1f;
        networkProtectionSupportedSpeed = 1f;
        networkProtectionMediaBitrate = 0;
        networkProtectionRebufferCount = 0;
        applyEffectiveSpeed(userPlaybackSpeed, reason);
    }

    private ExoNetworkGuardEligibility.Decision getNetworkProtectionEligibility() {
        boolean enabled = ExoPerformanceSetting.isNetworkProtectionEnabled();
        boolean exo = playerType == PlayerSetting.EXO;
        boolean vod = isVod();
        boolean unitSpeed = Math.abs(userPlaybackSpeed - 1f) < 0.001f;
        boolean speedAvailable = player != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH);
        return ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(enabled, exo, vod, unitSpeed, speedAvailable, false, false));
    }

    private void scheduleNetworkProtection(long delayMs) {
        App.removeCallbacks(networkProtectionRunnable);
        ExoNetworkGuardEligibility.Decision eligibility = getNetworkProtectionEligibility();
        if (!eligibility.eligible()) {
            if (networkProtectionSpeed < 0.999f) resetNetworkProtectionSession(eligibility.reason());
            else {
                networkProtectionState = ExoNetworkGuardController.State.NORMAL;
                networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
                networkProtectionReason = eligibility.reason();
            }
            return;
        }
        App.post(networkProtectionRunnable, delayMs);
    }

    private void evaluateNetworkProtection() {
        if (player == null) return;
        ExoNetworkGuardEligibility.Decision eligibility = getNetworkProtectionEligibility();
        boolean eligible = eligibility.eligible();
        long nowMs = SystemClock.elapsedRealtime();
        boolean ready = player.getPlaybackState() == Player.STATE_READY;
        boolean playing = player.isPlaying();
        boolean loading = player.isLoading();
        long bufferedMs = Math.max(0, player.getTotalBufferedDuration());
        networkProtectionTrend.observe(nowMs, bufferedMs, ready && playing, loading);
        ForwardBufferTrend.Snapshot trend = networkProtectionTrend.snapshot();
        float minimumSpeed = ExoPerformanceSetting.getNetworkProtectionMinimumSpeed();
        long safeBufferMs = getNetworkProtectionSafeBufferMs();
        ExoNetworkGuardController.Decision decision = networkProtectionController.evaluate(new ExoNetworkGuardController.Input(
                nowMs, eligible, ready, playing, loading, bufferedMs,
                trend.known(), trend.slopeMsPerSecond(), trend.fastSlopeMsPerSecond(), trend.slowSlopeMsPerSecond(), trend.windowMs(),
                networkProtectionRebufferCount, getSpeed(), minimumSpeed, safeBufferMs, false, 1f));
        networkProtectionState = decision.state();
        networkProtectionTier = decision.tier();
        networkProtectionReason = decision.reason();
        networkProtectionSpeed = decision.targetSpeed();
        networkProtectionSupportedSpeed = decision.supportedSpeed();
        if (decision.changed()) applyEffectiveSpeed(networkProtectionSpeed, "guard-" + decision.reason());
        if (eligible && ready && playing) scheduleNetworkProtection(getNetworkProtectionEvaluationDelayMs());
    }

    private long getNetworkProtectionEvaluationDelayMs() {
        return switch (networkProtectionState) {
            case WARNING, PROTECT, RECOVERY -> ExoNetworkGuardController.CONTROL_INTERVAL_MS;
            case NORMAL, UNSUSTAINABLE -> ExoNetworkGuardController.OBSERVE_INTERVAL_MS;
        };
    }

    private long getNetworkProtectionSafeBufferMs() {
        boolean loopback = spec != null && spec.getPlaybackRoute() != null && spec.getPlaybackRoute().loopback();
        return ExoNetworkGuardBufferPolicy.resolve(loopback, ExoPerformanceSetting.getRebufferMs());
    }

    private static float roundThousandth(float value) {
        return Math.round(value * 1_000f) / 1_000f;
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty()) engine.setTrack(tracks);
    }

    public void play() {
        player.play();
    }

    public void pause() {
        player.pause();
    }

    public void stop() {
        player.stop();
        stopParse();
    }

    public void clearMediaItems() {
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return engine.isRepeatOne();
    }

    public void setRepeatOne(boolean repeat) {
        engine.setRepeatOne(repeat);
    }

    public void seekTo(long time) {
        player.seekTo(time);
    }

    public long getTextOffsetMs() {
        if (player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET)) return player.getTextOffsetMs();
        return 0;
    }

    public void setTextOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(offsetMs);
    }

    public long getAudioOffsetMs() {
        if (player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET)) return player.getAudioOffsetMs();
        return 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(offsetMs);
    }

    public void reset() {
        App.removeCallbacks(runnable);
        retry = 0;
    }

    public void clear() {
        spec = null;
    }

    public void resetTrack() {
        engine.resetTrack();
    }

    public void toggleDecode() {
        engine.setDecode(engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD);
        rebuildPlayer();
        setMediaItem();
    }

    private void rebuildPlayer() {
        player = engine.rebuild(listener);
        callback.onPlayerRebuild(player);
    }

    public void browse(PlaySpec spec) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY);
    }

    public void start(PlaySpec spec, long timeout) {
        this.spec = spec;
        setMediaItem(timeout);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        stopParse();
        spec = PlaySpec.fromParse(result, key, metadata);
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    public void setMediaItem() {
        setMediaItem(Constant.TIMEOUT_PLAY);
    }

    private void setMediaItem(long timeout) {
        if (spec == null || spec.getUrl() == null) return;
        SpiderDebug.log("player", "setMediaItem timeout=%d spec=%s", timeout, debugSpec());
        setDanmakus(spec.getDanmakus());
        engine.start(spec.checkUa());
        setSpeed(PlayerSetting.getDefaultSpeed());
        App.post(runnable, timeout);
        callback.onPrepare();
        initTrack = false;
    }

    private void setDanmakus(List<Danmaku> items) {
        setDanmaku(items == null || items.isEmpty() ? Danmaku.empty() : items.get(0));
    }

    public void setDanmaku(Danmaku item) {
        if (danmakuController == null) return;
        if (spec != null) spec.setDanmaku(item);
        if (item.isEmpty()) danmakuController.clearItems();
        else danmakuController.setDataSource(Uri.parse(item.getRealUrl()));
    }

    public void addDanmaku(Danmaku item) {
        if (danmakuController == null || item.isEmpty()) return;
        if (spec != null) spec.addDanmaku(item);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        SpiderDebug.log("player", "parseSuccess from=%s url=%s headerKeys=%s", from, url, headers == null ? null : headers.keySet());
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        setMediaItem();
    }

    @Override
    public void onParseError() {
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    private String debugSpec() {
        if (spec == null) return "null";
        return "key=" + spec.getKey() +
                ", url=" + spec.getUrl() +
                ", format=" + spec.getFormat() +
                ", headerKeys=" + (spec.getHeaders() == null ? null : spec.getHeaders().keySet()) +
                ", subs=" + (spec.getSubs() == null ? 0 : spec.getSubs().size()) +
                ", danmakus=" + (spec.getDanmakus() == null ? 0 : spec.getDanmakus().size());
    }

    private static String stateName(int state) {
        return switch (state) {
            case Player.STATE_IDLE -> "IDLE";
            case Player.STATE_BUFFERING -> "BUFFERING";
            case Player.STATE_READY -> "READY";
            case Player.STATE_ENDED -> "ENDED";
            default -> String.valueOf(state);
        };
    }

    private static String causeChain(Throwable error) {
        if (error == null) return "null";
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (builder.length() > 0) builder.append(" <- ");
            builder.append(current.getClass().getName());
            if (!TextUtils.isEmpty(current.getMessage())) builder.append(": ").append(current.getMessage());
            current = current.getCause();
        }
        return builder.toString();
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onTitlesChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state != Player.STATE_IDLE) App.removeCallbacks(runnable);
            if (lastListenerState == Player.STATE_READY && state == Player.STATE_BUFFERING) networkProtectionRebufferCount++;
            lastListenerState = state;
            SpiderDebug.log("player", "state=%s spec=%s", stateName(state), debugSpec());
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty() || initTrack) return;
            setTrack(Track.find(getKey()));
            callback.onTracksChanged();
            initTrack = true;
        }

        @Override
        public void onMediaEditionsChanged(@NonNull List<MediaEdition> editions) {
            callback.onTitlesChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            PlayerEngine.ErrorAction action = engine.handleError(e);
            SpiderDebug.log("player", "error code=%d message=%s action=%s retry=%d spec=%s cause=%s", e.errorCode, e.getMessage(), action, retry, debugSpec(), causeChain(e));
            if (action == PlayerEngine.ErrorAction.RECOVERED) {
                if (spec != null) setDanmakus(spec.getDanmakus());
                return;
            }
            if (action == PlayerEngine.ErrorAction.FATAL) {
                callback.onError(engine.getErrorMessage(e));
            } else if (++retry > 1) {
                callback.onError(engine.getErrorMessage(e));
            } else {
                toggleDecode();
            }
        }
    };
}
