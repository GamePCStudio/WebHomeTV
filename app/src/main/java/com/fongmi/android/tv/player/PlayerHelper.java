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
     * Media3 (and the ass-media integration) normalize embedded text tracks' sampleMimeType to
     * "application/x-media3-cues", so the real format must be recovered from {@code codecs},
     * {@code label} or {@code id}. Returns "" when it cannot be determined (callers then keep the
     * player's native track name untouched).
     */
    public static String getSubtitleFormatLabel(Format format) {
        if (format == null) return "";
        String mime = format.sampleMimeType;
        String codecs = format.codecs;
        // Real (external / non-normalized) subtitle mimes already display fine via
        // FormatNameUtil.getSampleMimeTypeDisplayName; normalize them to our short labels.
        if (MimeTypes.APPLICATION_SUBRIP.equals(mime) || "application/subrip".equals(mime)) return "SRT";
        if (MimeTypes.TEXT_VTT.equals(mime)) return "VTT";
        if (MimeTypes.APPLICATION_TTML.equals(mime) || "application/ttml+xml".equals(mime)) return "TTML";
        if (MimeTypes.APPLICATION_TX3G.equals(mime)) return "TX3G";
        if ("application/pgs".equals(mime) || "image/pgs".equals(mime)) return "PGS";
        if (MimeTypes.APPLICATION_VOBSUB.equals(mime)) return "VobSub";
        if (MimeTypes.APPLICATION_DVBSUBS.equals(mime)) return "DVB";
        if (MimeTypes.TEXT_SSA.equals(mime)) {
            return (codecs != null && "ass".equalsIgnoreCase(codecs)) ? "ASS" : "SSA";
        }
        if ("text/plain".equals(mime)) return "TXT";
        // Normalized embedded track (application/x-media3-cues): recover the real format. Fall back
        // to a generic label so the raw "application/x-media3-cues" token is never shown to the user.
        if ("application/x-media3-cues".equals(mime)) {
            String recovered = recoverSubtitleFormat(codecs, format.label, format.id);
            return TextUtils.isEmpty(recovered) ? "SUB" : recovered;
        }
        return recoverSubtitleFormat(codecs, null, null);
    }

    private static String recoverSubtitleFormat(String codecs, String label, String id) {
        List<String> tokens = new ArrayList<>();
        if (!TextUtils.isEmpty(codecs)) tokens.add(codecs);
        if (!TextUtils.isEmpty(label)) tokens.add(label);
        if (!TextUtils.isEmpty(id)) tokens.add(id);
        for (String raw : tokens) {
            String t = raw.toLowerCase(Locale.ROOT);
            // Matroska codec ids
            if (t.contains("s_text/ass")) return "ASS";
            if (t.contains("s_text/ssa")) return "SSA";
            if (t.contains("s_text/utf8") || t.contains("s_text/usf")) return "SRT";
            if (t.contains("s_hdmv/pgs") || t.contains("s_image/bmp")) return "PGS";
            if (t.contains("s_text/webvtt")) return "VTT";
            if (t.contains("s_dvbsub")) return "DVB";
            if (t.contains("s_vobsub")) return "VobSub";
            if (t.contains("s_ttml") || t.endsWith(".ttml") || t.contains(".xml") || t.contains(".dfxp")) return "TTML";
            // Simplified codec values
            if (t.equals("ass")) return "ASS";
            if (t.equals("ssa")) return "SSA";
            if (t.equals("srt")) return "SRT";
            if (t.equals("pgs")) return "PGS";
            if (t.equals("wvtt")) return "VTT";
            if (t.equals("stpp")) return "TTML";
            if (t.equals("tx3g")) return "TX3G";
            // File extensions (external subtitles often carry the filename in label/id)
            if (t.endsWith(".srt")) return "SRT";
            if (t.endsWith(".ass")) return "ASS";
            if (t.endsWith(".ssa")) return "SSA";
            if (t.endsWith(".sup")) return "PGS";
            if (t.endsWith(".vtt")) return "VTT";
            if (t.endsWith(".ttml") || t.endsWith(".xml") || t.endsWith(".dfxp")) return "TTML";
            if (t.endsWith(".smi")) return "SMI";
            if (t.endsWith(".txt")) return "TXT";
            if (t.endsWith(".sub") || t.endsWith(".idx")) return "VobSub";
        }
        return "";
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
