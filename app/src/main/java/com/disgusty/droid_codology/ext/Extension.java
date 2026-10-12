package com.disgusty.droid_codology.ext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** An installed extension: its folder and what its package.json contributes. */
public final class Extension {
    public final File dir;
    public final JSONObject manifest;
    public final String id, name, publisher, displayName, description, version;
    public boolean enabled = true;
    /** The desktop platform the package was built for ("linux-arm64", "win32-x64"...), or null for a universal one. */
    public String targetPlatform;
    /** Desktop programs and Node add-ons (ELF, Mach-O, PE, .node) the package has; they aren't unpacked. */
    public int nativeFiles;

    /**
     * Made for desktop computers: its code starts desktop programs or needs Node.js, and Android has
     * neither, so only its declarative parts (themes, languages, snippets) can be used.
     */
    public boolean desktopOnly() {
        return targetPlatform != null || nativeFiles > 0;
    }

    public static final class Theme {
        public final String label, uiTheme, path;

        Theme(String label, String uiTheme, String path) {
            this.label = label;
            this.uiTheme = uiTheme;
            this.path = path;
        }

        public boolean dark() {
            return !"vs".equals(uiTheme) && !"hc-light".equals(uiTheme);
        }
    }

    public Extension(File dir, JSONObject manifest) {
        this.dir = dir;
        this.manifest = manifest;
        publisher = manifest.optString("publisher", "unknown");
        name = manifest.optString("name", dir.getName());
        id = publisher + "." + name;
        String dn = manifest.optString("displayName", name);
        displayName = dn.startsWith("%") ? name : dn;
        String desc = manifest.optString("description", "");
        description = desc.startsWith("%") ? "" : desc;
        version = manifest.optString("version", "0.0.0");
    }

    public JSONObject contributes() {
        JSONObject c = manifest.optJSONObject("contributes");
        return c != null ? c : new JSONObject();
    }

    public List<Theme> themes() {
        ArrayList<Theme> out = new ArrayList<Theme>();
        JSONArray a = contributes().optJSONArray("themes");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            String label = t.optString("label", t.optString("id", "Theme"));
            if (label.startsWith("%")) label = t.optString("id", name);
            out.add(new Theme(label, t.optString("uiTheme", "vs-dark"), t.optString("path")));
        }
        return out;
    }

    /** The JS entry point ("browser" preferred, then "main"), or null for declarative extensions. */
    public String entryPoint() {
        String b = manifest.optString("browser", null);
        if (b != null && !b.isEmpty()) return b;
        String m = manifest.optString("main", null);
        return m == null || m.isEmpty() ? null : m;
    }

    /** Short summary of the supported contributions ("2 themes · 1 grammar · snippets"). */
    public String capabilities() {
        JSONObject c = contributes();
        ArrayList<String> parts = new ArrayList<String>();
        int themes = c.optJSONArray("themes") != null ? c.optJSONArray("themes").length() : 0;
        int grammars = c.optJSONArray("grammars") != null ? c.optJSONArray("grammars").length() : 0;
        int langs = c.optJSONArray("languages") != null ? c.optJSONArray("languages").length() : 0;
        if (themes > 0) parts.add(themes + " theme" + (themes > 1 ? "s" : ""));
        if (langs > 0) parts.add(langs + " language" + (langs > 1 ? "s" : ""));
        if (grammars > 0) parts.add(grammars + " grammar" + (grammars > 1 ? "s" : ""));
        if (c.optJSONArray("snippets") != null) parts.add("snippets");
        if (entryPoint() != null && !desktopOnly()) parts.add("code");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    public File file(String relative) {
        String r = relative;
        while (r.startsWith("./")) r = r.substring(2);
        File f = new File(dir, r);
        // Never read outside the extension folder.
        try {
            // With the separator: "ext-1.0" must not reach "ext-1.0-evil" next to it.
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) return null;
        } catch (Exception e) {
            return null;
        }
        return f;
    }
}
