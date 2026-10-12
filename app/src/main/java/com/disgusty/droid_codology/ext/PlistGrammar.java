package com.disgusty.droid_codology.ext;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;

/** Converts an XML property-list TextMate grammar (.tmLanguage / .plist) to the JSON form. */
final class PlistGrammar {
    private PlistGrammar() {
    }

    static String toJson(String xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && !p.getName().equals("plist")) {
                Object v = value(p);
                return v instanceof JSONObject ? ((JSONObject) v).toString() : "{}";
            }
        }
        return "{}";
    }

    /** Reads the value whose start tag the parser is on. */
    private static Object value(XmlPullParser p) throws Exception {
        String tag = p.getName();
        switch (tag) {
            case "dict": {
                JSONObject o = new JSONObject();
                String key = null;
                int ev;
                while ((ev = p.next()) != XmlPullParser.END_TAG || !p.getName().equals("dict")) {
                    if (ev != XmlPullParser.START_TAG) continue;
                    if (p.getName().equals("key")) key = p.nextText();
                    else {
                        Object v = value(p);
                        if (key != null) o.put(key, v);
                        key = null;
                    }
                }
                return o;
            }
            case "array": {
                JSONArray a = new JSONArray();
                int ev;
                while ((ev = p.next()) != XmlPullParser.END_TAG || !p.getName().equals("array")) {
                    if (ev == XmlPullParser.START_TAG) a.put(value(p));
                }
                return a;
            }
            case "true":
                p.next();
                return Boolean.TRUE;
            case "false":
                p.next();
                return Boolean.FALSE;
            case "integer":
                return Long.parseLong(p.nextText().trim());
            case "real":
                return Double.parseDouble(p.nextText().trim());
            default:
                return p.nextText();
        }
    }
}
