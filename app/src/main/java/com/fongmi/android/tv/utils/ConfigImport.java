package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.impl.Callback;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public class ConfigImport {

    public static String normalize(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.isEmpty()) return "";
        if (text.startsWith(Path.rootPath())) return "file:/" + text.replace(Path.rootPath(), "");
        return text;
    }

    public static Preview preview(@NonNull Config config) {
        String url = normalize(config.getUrl());
        if (TextUtils.isEmpty(url)) return Preview.empty(config.getType());
        try {
            return switch (config.getType()) {
                case 0 -> previewVod(config.url(url));
                case 1 -> previewLive(config.url(url));
                case 2 -> previewWall(config.url(url));
                default -> Preview.error(config.getType(), url, "未知配置类型");
            };
        } catch (Throwable e) {
            SpiderDebug.log(e);
            return Preview.error(config.getType(), url, Notify.getError(R.string.error_config_get, e));
        }
    }

    private static Preview previewVod(Config config) throws Throwable {
        String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), "ConfigImport");
        JsonObject object = Json.parse(json).getAsJsonObject();
        if (object.has("msg")) return Preview.error(config.getType(), config.getUrl(), object.get("msg").getAsString());
        if (object.has("urls")) {
            List<Depot> depots = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
            return Preview.success(config.getType(), config.getUrl(), depots.isEmpty() ? "配置仓库（空）" : "配置仓库", depots.size(), 0, !Json.safeString(object, "logo").isEmpty(), false, true, depots.isEmpty() ? "未发现可用配置入口" : "检测到配置仓库入口");
        }
        int siteCount = Json.safeListElement(object, "sites").size();
        int liveCount = Json.safeListElement(object, "lives").size();
        String name = !TextUtils.isEmpty(config.getName()) ? config.getName() : Json.safeString(object, "notice");
        if (TextUtils.isEmpty(name)) name = Json.safeString(object, "home");
        return Preview.success(config.getType(), config.getUrl(), TextUtils.isEmpty(name) ? "点播配置" : name, siteCount, liveCount, !Json.safeString(object, "logo").isEmpty(), hasHomePage(object), false, siteCount > 0 ? "导入后可直接使用" : "配置可解析，但未发现站点");
    }

    private static Preview previewLive(Config config) throws Throwable {
        String text = Decoder.getJson(UrlUtil.convert(config.getUrl()), "ConfigImport");
        if (Json.isObj(text)) {
            JsonObject object = Json.parse(text).getAsJsonObject();
            if (object.has("msg")) return Preview.error(config.getType(), config.getUrl(), object.get("msg").getAsString());
            if (object.has("urls")) {
                List<Depot> depots = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
                return Preview.success(config.getType(), config.getUrl(), depots.isEmpty() ? "直播配置仓库（空）" : "直播配置仓库", depots.size(), 0, false, false, true, depots.isEmpty() ? "未发现可用配置入口" : "检测到配置仓库入口");
            }
            int liveCount = Json.safeListElement(object, "lives").size();
            return Preview.success(config.getType(), config.getUrl(), TextUtils.isEmpty(config.getName()) ? "直播配置" : config.getName(), liveCount, 0, false, false, false, liveCount > 0 ? "导入后可直接使用" : "配置可解析，但未发现直播源");
        }
        int lines = text.split("\\r?\\n").length;
        return Preview.success(config.getType(), config.getUrl(), TextUtils.isEmpty(config.getName()) ? "直播文本源" : config.getName(), lines, 0, false, false, false, lines > 0 ? "导入后可直接使用" : "直播文本为空");
    }

    private static Preview previewWall(Config config) {
        String url = config.getUrl();
        String label = TextUtils.isEmpty(config.getName()) ? "壁纸配置" : config.getName();
        if (url.startsWith("file") && !Path.exists(Path.local(url))) return Preview.error(config.getType(), url, "本地文件不存在");
        return Preview.success(config.getType(), url, label, 1, 0, false, false, false, "导入后将直接用于壁纸");
    }

    private static boolean hasHomePage(JsonObject object) {
        for (var element : Json.safeListElement(object, "sites")) {
            if (!element.isJsonObject()) continue;
            JsonObject site = element.getAsJsonObject();
            if (!Json.safeString(site, "homePage").isEmpty()) return true;
            if (!Json.safeString(site, "home_page").isEmpty()) return true;
            if (!Json.safeString(site, "webHome").isEmpty()) return true;
            if (!Json.safeString(site, "web_home").isEmpty()) return true;
        }
        return false;
    }

    public record Preview(int type, String url, String title, int primaryCount, int secondaryCount, boolean hasLogo, boolean hasHomePage, boolean depot, boolean valid, String summary) {
        public static Preview empty(int type) {
            return new Preview(type, "", "", 0, 0, false, false, false, true, "已清空该配置");
        }

        public static Preview success(int type, String url, String title, int primaryCount, int secondaryCount, boolean hasLogo, boolean hasHomePage, boolean depot, String summary) {
            return new Preview(type, url, title, primaryCount, secondaryCount, hasLogo, hasHomePage, depot, true, summary);
        }

        public static Preview error(int type, String url, String summary) {
            return new Preview(type, url, "", 0, 0, false, false, false, false, summary);
        }
    }

    public static Callback toCallback(Runnable start, java.util.function.Consumer<Preview> success, java.util.function.Consumer<String> error) {
        return new Callback() {
            @Override
            public void start() {
                if (start != null) start.run();
            }

            @Override
            public void success() {
            }

            @Override
            public void error(String msg) {
                if (error != null) error.accept(msg);
            }
        };
    }
}
