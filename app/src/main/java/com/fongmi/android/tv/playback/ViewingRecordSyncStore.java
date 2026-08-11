package com.fongmi.android.tv.playback;

import com.github.catvod.utils.Prefers;

public final class ViewingRecordSyncStore {

    private static final String KEY_ENABLED = "viewing_record_sync_enabled";
    private static final String KEY_LOCAL_WRITE = "viewing_record_sync_local_write";

    private ViewingRecordSyncStore() {
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLED, enabled);
    }

    public static boolean isLocalWriteEnabled() {
        return Prefers.getBoolean(KEY_LOCAL_WRITE, false);
    }

    public static void setLocalWriteEnabled(boolean enabled) {
        Prefers.put(KEY_LOCAL_WRITE, enabled);
    }
}