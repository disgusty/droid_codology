package com.disgusty.droid_codology.lang;

import android.content.res.AssetManager;

import com.disgusty.droid_codology.io.Jsonc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Snippets per language: VS Code's built-in snippet files (assets/snippets/&lt;id&gt;.code-snippets),
 * our additions (assets/snippets/_extra.json) and snippets contributed later by extensions/users.
 */
public final class Snippets {
    private Snippets() {
    }

    public static final class Item {
        public final String prefix, body, description, name;

        Item(String name, String prefix, String body, String description) {
            this.name = name;
            this.prefix = prefix;
            this.body = body;
            this.description = description;
        }
    }

    private static AssetManager assets;
    private static final HashMap<String, List<Item>> BY_LANG = new HashMap<String, List<Item>>();
    private static JSONObject extra;

    public static synchronized void init(AssetManager am) {
        assets = am;
    }

    /** Adds snippets from a VS Code .code-snippets / snippets JSON text (extensions, user snippets). */
    public static synchronized void add(String languageId, String json) {
        List<Item> list = forLanguage(languageId);
        parse(json, list);
    }

    public static synchronized List<Item> forLanguage(String id) {
        List<Item> l = BY_LANG.get(id);
        if (l != null) return l;
        l = new ArrayList<Item>();
        BY_LANG.put(id, l);
        if (assets != null) {
            String text = read("snippets/" + id + ".code-snippets");
            if (text != null) parse(text, l);
            try {
                if (extra == null) {
                    String ex = read("snippets/_extra.json");
                    extra = ex == null ? new JSONObject() : new JSONObject(Jsonc.clean(ex));
                }
                JSONObject mine = extra.optJSONObject(id);
                if (mine != null) parse(mine.toString(), l);
            } catch (Exception ignored) {
            }
        }
        return l;
    }

    static void parse(String text, List<Item> out) {
        try {
            JSONObject o = new JSONObject(Jsonc.clean(text));
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject s = o.optJSONObject(name);
                if (s == null) continue;
                String body = joined(s.opt("body"));
                if (body == null) continue;
                String desc = s.optString("description", name);
                Object pre = s.opt("prefix");
                if (pre instanceof JSONArray) {
                    JSONArray a = (JSONArray) pre;
                    for (int i = 0; i < a.length(); i++) out.add(new Item(name, a.optString(i), body, desc));
                } else if (pre != null) {
                    out.add(new Item(name, pre.toString(), body, desc));
                }
            }
        } catch (Exception ignored) {
            // A malformed snippet file must not break completion.
        }
    }

    private static String joined(Object body) {
        if (body instanceof JSONArray) {
            JSONArray a = (JSONArray) body;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(a.optString(i));
            }
            return sb.toString();
        }
        return body == null ? null : body.toString();
    }

    private static String read(String path) {
        try {
            InputStream in = assets.open(path);
            try {
                return new String(com.disgusty.droid_codology.io.TextFiles.readAll(in), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
