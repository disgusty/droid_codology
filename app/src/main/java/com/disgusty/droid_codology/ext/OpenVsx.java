package com.disgusty.droid_codology.ext;

import com.disgusty.droid_codology.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Client for the Open VSX registry (open-vsx.org), the open marketplace for VS Code extensions. */
public final class OpenVsx {
    private OpenVsx() {
    }

    public static String base = "https://open-vsx.org";

    public static final class Item {
        public String namespace, name, version, displayName, description, iconUrl, downloadUrl, readmeUrl;
        public long downloads;
        public double rating;
        /** Platforms with their own package ("universal", "web", "linux-arm64"...); known after details(). */
        public final List<String> platforms = new ArrayList<String>();
        /** Download of each platform's package. */
        public final java.util.Map<String, String> platformUrls = new java.util.HashMap<String, String>();

        /**
         * The Alpine Linux build for this device ("alpine-arm64", "alpine-x64"), which runs on the
         * Node.js extension host; null if the extension has none.
         */
        public String alpinePlatform() {
            String arch = com.disgusty.droid_codology.linux.AlpineLinux.arch();
            String p = "aarch64".equals(arch) ? "alpine-arm64" : "x86_64".equals(arch) ? "alpine-x64" : null;
            return p != null && platformUrls.get(p) != null ? p : null;
        }

        public String id() {
            return namespace + "." + name;
        }

        /**
         * Published only as desktop builds (each with its own programs for Windows, macOS or
         * Linux): none of them runs on Android, so only declarative parts could be used.
         */
        public boolean desktopOnly() {
            return !platforms.isEmpty() && !platforms.contains("universal") && !platforms.contains("web");
        }
    }

    private static Item parse(JSONObject o) {
        Item it = new Item();
        it.namespace = o.optString("namespace");
        it.name = o.optString("name");
        it.version = o.optString("version");
        it.displayName = o.optString("displayName", it.name);
        if (it.displayName.isEmpty()) it.displayName = it.name;
        it.description = o.optString("description", "");
        it.downloads = o.optLong("downloadCount", 0);
        it.rating = o.optDouble("averageRating", 0);
        JSONObject files = o.optJSONObject("files");
        if (files != null) {
            it.iconUrl = files.optString("icon", null);
            it.downloadUrl = files.optString("download", null);
            it.readmeUrl = files.optString("readme", null);
        }
        JSONObject dl = o.optJSONObject("downloads");
        if (dl != null) {
            java.util.Iterator<String> k = dl.keys();
            while (k.hasNext()) {
                String platform = k.next();
                it.platforms.add(platform);
                it.platformUrls.put(platform, dl.optString(platform, null));
            }
        } else if (o.has("targetPlatform")) {
            it.platforms.add(o.optString("targetPlatform"));
        }
        return it;
    }

    /** Search; an empty query lists the most downloaded extensions. */
    public static List<Item> search(String query, int size) throws IOException {
        String url = base + "/api/-/search?size=" + size + "&sortBy=" + (query.isEmpty() ? "downloadCount" : "relevance")
                + "&sortOrder=desc" + (query.isEmpty() ? "" : "&query=" + Http.encode(query));
        Http.Response r = Http.get(url, null);
        if (!r.ok()) throw new IOException("Open VSX: HTTP " + r.code);
        ArrayList<Item> out = new ArrayList<Item>();
        try {
            JSONArray a = new JSONObject(r.text()).optJSONArray("extensions");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(parse(a.getJSONObject(i)));
        } catch (Exception e) {
            throw new IOException("Open VSX: bad response");
        }
        return out;
    }

    /** Full metadata (with download / readme URLs) of the latest version. */
    public static Item details(String namespace, String name) throws IOException {
        Http.Response r = Http.get(base + "/api/" + Http.encode(namespace) + "/" + Http.encode(name), null);
        if (!r.ok()) throw new IOException("Open VSX: HTTP " + r.code);
        try {
            return parse(new JSONObject(r.text()));
        } catch (Exception e) {
            throw new IOException("Open VSX: bad response");
        }
    }

    public static String readme(Item it) throws IOException {
        if (it.readmeUrl == null) return "";
        Http.Response r = Http.get(it.readmeUrl, null);
        return r.ok() ? r.text() : "";
    }

    public static File download(Item it, File dir, Http.Progress p) throws IOException {
        if (it.downloadUrl == null) {
            Item d = details(it.namespace, it.name);
            it.downloadUrl = d.downloadUrl;
        }
        if (it.downloadUrl == null) throw new IOException("No download");
        File f = new File(dir, it.id() + "-" + it.version + ".vsix");
        Http.download(it.downloadUrl, f, null, p);
        return f;
    }
}
