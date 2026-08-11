package com.fongmi.android.tv.playback;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class PlaybackFieldPolicy {

    private static final Set<String> PROTOCOL = set("schema", "event", "eventId", "timestamp", "sessionId", "dedupeKey", "scope", "deletedAt");
    private static final Set<String> OBJECT = set("cid", "configKey", "configName", "historyKey", "siteKey", "siteName", "vodId", "vodName", "vodPic", "flag", "episodeName");
    private static final Set<String> PROGRESS = set("state", "positionMs", "durationMs", "progress", "speed", "completed");
    private static final Set<String> STANDARD = set("appVersion", "client");
    private static final Set<String> FULL = set("episodeUrl", "episodeIndex", "clientKey");

    private final Set<String> fields;
    private final boolean hashHistoryKey;

    private PlaybackFieldPolicy(Set<String> fields, boolean hashHistoryKey) {
        this.fields = fields;
        this.hashHistoryKey = hashHistoryKey;
    }

    public static PlaybackFieldPolicy apiSafe() {
        Set<String> fields = base();
        fields.remove("event");
        fields.remove("eventId");
        return new PlaybackFieldPolicy(fields, false);
    }

    public static PlaybackFieldPolicy full() {
        Set<String> fields = base();
        fields.addAll(STANDARD);
        fields.addAll(FULL);
        return new PlaybackFieldPolicy(fields, false);
    }

    public boolean includes(String field) {
        return fields.contains(field);
    }

    public boolean hashHistoryKey() {
        return hashHistoryKey;
    }

    private static Set<String> base() {
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(PROTOCOL);
        fields.addAll(OBJECT);
        fields.addAll(PROGRESS);
        return fields;
    }

    private static Set<String> set(String... fields) {
        return new LinkedHashSet<>(Arrays.asList(fields));
    }
}