package com.fongmi.android.tv.player;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

public class PlayerHelper {

    public static String getDefaultUa() {
        return Util.getUserAgent(App.get(), BuildConfig.APPLICATION_ID);
    }

    public static String getUa() {
        return resolveUa(Setting.getUa(), PlayerHelper::getDefaultUa);
    }

    public static String resolveUa(String ua, Supplier<String> fallback) {
        return TextUtils.isEmpty(ua) ? fallback.get() : ua;
    }

    public static String getSubtitleMimeType(String path) {
        if (TextUtils.isEmpty(path)) return "";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".sup")) return "application/pgs";
        if (lower.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (lower.endsWith(".ssa") || lower.endsWith(".ass")) return MimeTypes.TEXT_SSA;
        if (lower.endsWith(".ttml") || lower.endsWith(".xml") || lower.endsWith(".dfxp")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.APPLICATION_SUBRIP;
    }

    public static Bundle toBundle(Map<String, String> headers) {
        Bundle bundle = new Bundle();
        headers.forEach(bundle::putString);
        return bundle;
    }

    public static String describeFormat(Format format) {
        StringJoiner joiner = new StringJoiner(",");
        if (format.id != null) joiner.add(format.id);
        if (format.label != null) joiner.add(format.label);
        if (format.codecs != null) joiner.add(format.codecs);
        if (format.language != null) joiner.add(format.language);
        if (format.sampleMimeType != null) joiner.add(format.sampleMimeType);
        if (format.containerMimeType != null) joiner.add(format.containerMimeType);
        if (format.width != C.LENGTH_UNSET) joiner.add(String.valueOf(format.width));
        if (format.height != C.LENGTH_UNSET) joiner.add(String.valueOf(format.height));
        if (format.sampleRate != C.RATE_UNSET_INT) joiner.add(String.valueOf(format.sampleRate));
        if (format.channelCount != C.LENGTH_UNSET) joiner.add(String.valueOf(format.channelCount));
        if (format.averageBitrate != C.LENGTH_UNSET) joiner.add(String.valueOf(format.averageBitrate));
        return joiner.toString();
    }

    /**
     * Resolve a human-readable subtitle format label (PGS / ASS / SSA / SRT / VTT / TTML / TXT ...).
     * Media3 normalizes every text track's sampleMimeType to "application/x-media3-cues", so the
     * real format must be read from the {@code codecs} field (embedded tracks) or from the real
     * sampleMimeType (external subtitle files).
     */
    public static String getSubtitleFormatLabel(Format format) {
        if (format == null) return "";
        String mime = format.sampleMimeType;
        String codecs = format.codecs;
        // Media3 collapses every text track's MIME into the internal Cues representation.
        if ("application/x-media3-cues".equals(mime)) {
            if (!TextUtils.isEmpty(codecs)) {
                switch (codecs.toLowerCase(Locale.ROOT)) {
                    case "ass":  return "ASS";
                    case "ssa":  return "SSA";
                    case "stpp": return "TTML";
                    case "wvtt": return "VTT";
                    case "pgs":  return "PGS";
                    case "srt":  return "SRT";
                    case "tx3g": return "TX3G";
                }
            }
            // Embedded but no codecs (most SRT/SUB tracks fall here) -> best-effort label.
            return "SUB";
        }
        if (MimeTypes.APPLICATION_SUBRIP.equals(mime) || "application/subrip".equals(mime)) return "SRT";
        if (MimeTypes.TEXT_SSA.equals(mime)) {
            if (TextUtils.isEmpty(codecs)) return "SSA";
            return "ass".equalsIgnoreCase(codecs) ? "ASS" : "SSA";
        }
        if ("application/pgs".equals(mime) || "image/pgs".equals(mime)) return "PGS";
        if (MimeTypes.TEXT_VTT.equals(mime)) return "VTT";
        if (MimeTypes.APPLICATION_TTML.equals(mime) || "application/ttml+xml".equals(mime)) return "TTML";
        if ("text/plain".equals(mime)) return "TXT";
        if (!TextUtils.isEmpty(codecs)) {
            switch (codecs.toLowerCase(Locale.ROOT)) {
                case "ass":  return "ASS";
                case "ssa":  return "SSA";
                case "pgs":  return "PGS";
                case "wvtt": return "VTT";
                case "stpp": return "TTML";
                case "srt":  return "SRT";
                case "tx3g": return "TX3G";
            }
        }
        return "SUB";
    }

    public static void share(Activity activity, String url, Map<String, String> headers, CharSequence title) {
        try {
            if (url == null || url.isEmpty()) return;
            Bundle bundle = toBundle(headers);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_TEXT, url);
            intent.putExtra("extra_headers", bundle);
            intent.putExtra("title", title).putExtra("name", title);
            intent.setType("text/plain");
            activity.startActivity(getChooser(intent));
        } catch (Exception ignored) {
        }
    }

    public static void choose(Activity activity, String url, Map<String, String> headers, boolean isVod, long position, CharSequence title) {
        try {
            if (url == null || url.isEmpty()) return;
            List<String> list = new ArrayList<>();
            headers.forEach((key, value) -> {
                list.add(key);
                list.add(value);
            });
            Uri data = url.startsWith("file://") || url.startsWith("/") ? FileUtil.getShareUri(url) : Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(data, "video/*");
            intent.putExtra("title", title).putExtra("return_result", isVod);
            intent.putExtra("headers", list.toArray(String[]::new));
            if (isVod) intent.putExtra("position", (int) position);
            activity.startActivityForResult(getChooser(intent), 1001);
        } catch (Exception ignored) {
        }
    }

    public static void onExternalResult(Intent data, Runnable onNext, LongConsumer seekTo) {
        try {
            if (data == null || data.getExtras() == null) return;
            long position = data.getExtras().getInt("position", 0);
            String endBy = data.getExtras().getString("end_by", "");
            if ("playback_completion".equals(endBy)) App.post(onNext);
            if ("user".equals(endBy)) seekTo.accept(position);
        } catch (Exception ignored) {
        }
    }

    private static Intent getChooser(Intent intent) {
        List<ComponentName> components = new ArrayList<>();
        for (ResolveInfo resolveInfo : App.get().getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            String pkgName = resolveInfo.activityInfo.packageName;
            if (pkgName.equals(App.get().getPackageName())) {
                components.add(new ComponentName(pkgName, resolveInfo.activityInfo.name));
            }
        }
        return Intent.createChooser(intent, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, components.toArray(new ComponentName[0]));
    }
}
