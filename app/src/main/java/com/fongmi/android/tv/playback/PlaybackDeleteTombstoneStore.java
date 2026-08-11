package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.PlaybackDeleteTombstone;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Persistent delete markers used to make playback sync monotonic (Prefers-backed). */
public final class PlaybackDeleteTombstoneStore {

    private static final String KEY = "playback_delete_tombstones";
    private static final long RETENTION_MS = TimeUnit.DAYS.toMillis(90);
    private static final Type LIST_TYPE = new TypeToken<List<PlaybackDeleteTombstone>>() {
    }.getType();

    private PlaybackDeleteTombstoneStore() {
    }

    public static synchronized List<PlaybackDeleteTombstone> snapshot() {
        List<PlaybackDeleteTombstone> tombstones = load();
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        tombstones.removeIf(tombstone -> tombstone.deletedAt < cutoff);
        return tombstones;
    }

    public static synchronized void record(PlaybackProgressDeleteInput input, int cid) {
        if (input == null) return;
        input.normalize();
        List<PlaybackDeleteTombstone> tombstones = load();
        PlaybackDeleteTombstone tombstone = create(input, cid);
        PlaybackDeleteTombstone existing = find(tombstones, tombstone.id);
        if (newest(existing, tombstone) == tombstone) tombstones.add(tombstone);
        persist(trim(tombstones));
    }

    public static long latest(List<PlaybackDeleteTombstone> tombstones, String configKey, int cid,
                              String historyKey, String siteKey, String vodId) {
        if (tombstones == null || tombstones.isEmpty()) return 0;
        String identity = configIdentity(configKey, cid);
        String normalizedHistory = safe(historyKey);
        String normalizedSite = normalize(siteKey);
        String normalizedVod = safe(vodId);
        long latest = 0;
        for (PlaybackDeleteTombstone tombstone : tombstones) {
            if (tombstone == null || !Objects.equals(identity, safe(tombstone.configKey))) continue;
            if (!matches(tombstone, normalizedHistory, normalizedSite, normalizedVod)) continue;
            latest = Math.max(latest, tombstone.deletedAt);
        }
        return latest;
    }

    public static String configIdentity(String configKey, int cid) {
        String value = PlaybackConfigIdentity.normalizeKey(configKey);
        if (!empty(value)) return value;
        value = PlaybackConfigIdentity.keyForCid(cid);
        return empty(value) ? "cid:" + Math.max(0, cid) : value;
    }

    private static PlaybackDeleteTombstone create(PlaybackProgressDeleteInput input, int cid) {
        PlaybackDeleteTombstone tombstone = new PlaybackDeleteTombstone();
        tombstone.configKey = configIdentity(input.configKey, cid);
        tombstone.scope = input.isAllScope() ? "all" : input.isSiteScope() ? "site" : "item";
        tombstone.historyKey = safe(input.historyKey);
        tombstone.siteKey = normalize(input.siteKey);
        tombstone.vodId = safe(input.vodId);
        if ("all".equals(tombstone.scope)) {
            tombstone.historyKey = "";
            tombstone.siteKey = "";
            tombstone.vodId = "";
        } else if ("site".equals(tombstone.scope)) {
            tombstone.historyKey = "";
            tombstone.vodId = "";
        } else if (!empty(tombstone.siteKey) && !empty(tombstone.vodId)) {
            tombstone.historyKey = "";
        }
        tombstone.deletedAt = input.deletedAt > 0 ? input.deletedAt : System.currentTimeMillis();
        tombstone.id = digest(join(tombstone.configKey, tombstone.scope, tombstone.historyKey,
                tombstone.siteKey, tombstone.vodId));
        return tombstone;
    }

    private static boolean matches(PlaybackDeleteTombstone tombstone, String historyKey, String siteKey, String vodId) {
        if ("all".equals(tombstone.scope)) return true;
        if ("site".equals(tombstone.scope)) return Objects.equals(tombstone.siteKey, siteKey);
        if (!empty(tombstone.siteKey) && !empty(tombstone.vodId)) {
            return Objects.equals(tombstone.siteKey, siteKey) && Objects.equals(tombstone.vodId, vodId);
        }
        return !empty(tombstone.historyKey) && Objects.equals(tombstone.historyKey, historyKey);
    }

    static PlaybackDeleteTombstone newest(PlaybackDeleteTombstone existing, PlaybackDeleteTombstone incoming) {
        if (existing == null) return incoming;
        if (incoming == null || incoming.deletedAt <= existing.deletedAt) return existing;
        return incoming;
    }

    private static PlaybackDeleteTombstone find(List<PlaybackDeleteTombstone> tombstones, String id) {
        for (PlaybackDeleteTombstone tombstone : tombstones) {
            if (tombstone != null && tombstone.id != null && tombstone.id.equals(id)) return tombstone;
        }
        return null;
    }

    private static List<PlaybackDeleteTombstone> trim(List<PlaybackDeleteTombstone> items) {
        if (items == null) return new ArrayList<>();
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        items.removeIf(tombstone -> tombstone.deletedAt < cutoff);
        return items;
    }

    private static List<PlaybackDeleteTombstone> load() {
        try {
            List<PlaybackDeleteTombstone> tombstones = App.gson().fromJson(Prefers.getString(KEY), LIST_TYPE);
            return tombstones == null ? new ArrayList<>() : tombstones;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void persist(List<PlaybackDeleteTombstone> items) {
        Prefers.put(KEY, App.gson().toJson(items));
    }

    private static String join(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) builder.append(safe(value)).append('\n');
        return builder.toString();
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) builder.append(String.format(Locale.ROOT, "%02x", item));
            return builder.toString();
        } catch (Exception e) {
            return value;
        }
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }
}