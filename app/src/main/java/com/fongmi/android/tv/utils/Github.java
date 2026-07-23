package com.fongmi.android.tv.utils;

public class Github {

    private static final String GITHUB_LATEST = "https://github.com/GamePCStudio/webhtv/releases/latest/download";
    private static final String GITHUB_RELEASE = "https://github.com/GamePCStudio/webhtv/releases/download";
    private static final String GITHUB_API = "https://api.github.com/repos/GamePCStudio/webhtv/releases/tags";
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/GamePCStudio/webhtv/releases";
    private static final String GITHUB_RELEASE_ASSETS_API = "https://api.github.com/repos/GamePCStudio/webhtv/releases/assets";

    public static String getGithubLatestAsset(String name) {
        return GITHUB_LATEST + "/" + name;
    }

    public static String getGithubReleaseAsset(String tag, String name) {
        return GITHUB_RELEASE + "/" + tag + "/" + name;
    }

    public static String getReleaseApi(String tag) {
        return GITHUB_API + "/" + tag;
    }

    public static String getReleasesApi() {
        return GITHUB_RELEASES_API + "?per_page=20";
    }

    public static String getLatestReleaseApi() {
        return GITHUB_RELEASES_API + "/latest";
    }

    public static String getReleaseAssetApi(long id) {
        return GITHUB_RELEASE_ASSETS_API + "/" + id;
    }
}
