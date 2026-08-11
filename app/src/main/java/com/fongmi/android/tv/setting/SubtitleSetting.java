package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

public final class SubtitleSetting {

    private SubtitleSetting() {
    }

    public static String getSearchToken() {
        return Prefers.getString("subtitle_search_token", "");
    }

    public static String getEffectiveToken() {
        return getSearchToken();
    }

    public static void putSearchToken(String token) {
        Prefers.put("subtitle_search_token", token);
    }

    public static boolean hasToken() {
        return !TextUtils.isEmpty(getEffectiveToken());
    }
}
