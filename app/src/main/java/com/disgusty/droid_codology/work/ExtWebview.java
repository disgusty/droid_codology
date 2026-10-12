package com.disgusty.droid_codology.work;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;

import com.disgusty.droid_codology.editor.EditorColors;
import com.disgusty.droid_codology.ui.Skin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An extension webview (vscode.window.createWebviewPanel or a WebviewViewProvider) shown in an
 * Android WebView. The page gets VS Code's acquireVsCodeApi(), theme CSS variables and
 * asWebviewUri() resources, which are served from the extension and workspace folders.
 */
final class ExtWebview {
    static final String ORIGIN = "https://dc-resource.local";

    final String handle, kind, extId, viewId, viewType;
    String title;
    final WebView web;
    /** For panels: the full-screen page that holds the title bar and the WebView. */
    LinearLayout page;

    private final Workbench wb;
    private final ExtensionHost host;
    private final String extensionPath;
    private final ArrayList<File> roots = new ArrayList<File>();
    private final ArrayDeque<String> inbox = new ArrayDeque<String>();
    private volatile String state = "null";
    private String html = "";
    private boolean scripts;
    boolean disposed;
    /**
     * Known only to the boot script in the top page: the bridge object is also visible in every
     * iframe, and a page the extension embeds (a remote site) must not talk to the extension.
     */
    private final String token = android.util.Base64.encodeToString(
            com.disgusty.droid_codology.net.tls.TlsClient.random(18), android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    ExtWebview(Workbench wb, ExtensionHost host, String handle, String kind, JSONObject info) {
        this.wb = wb;
        this.host = host;
        this.handle = handle;
        this.kind = kind;
        this.extId = info.optString("ext");
        this.viewId = info.optString("viewId", null);
        this.viewType = info.optString("viewType", null);
        this.title = info.optString("title", "");
        this.extensionPath = info.optString("extensionPath", "");
        web = new WebView(wb.activity);
        WebSettings s = web.getSettings();
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Bridge(), "DCWV");
        web.setWebViewClient(new Client());
        // Side bar views sit on the side bar's color, panels on the editor's (as in VS Code).
        web.setBackgroundColor("view".equals(kind) ? wb.skin.sideBarBg : colors().background);
        setOptions(info.optJSONObject("options"));
    }

    private EditorColors colors() {
        return wb.editor.colors();
    }

    // ------------------------------------------------------------------ from the extension

    void setOptions(JSONObject o) {
        scripts = o != null && o.optBoolean("enableScripts");
        web.getSettings().setJavaScriptEnabled(scripts);
        roots.clear();
        if (!extensionPath.isEmpty()) roots.add(new File(extensionPath));
        if (wb.workspace != null) roots.add(wb.workspace.root);
        JSONArray r = o == null ? null : o.optJSONArray("localResourceRoots");
        for (int i = 0; r != null && i < r.length(); i++) {
            String p = r.optString(i, "");
            if (!p.isEmpty()) roots.add(new File(p));
        }
        if (!html.isEmpty()) setHtml(html);
    }

    void setHtml(String h) {
        html = h;
        String base = ORIGIN + (extensionPath.isEmpty() ? "" : Uri.encode(extensionPath, "/")) + "/";
        web.loadDataWithBaseURL(base, prepare(h), "text/html", "UTF-8", null);
    }

    /** extension → page: delivered as a 'message' event, through a queue the page drains. */
    void post(String json) {
        synchronized (inbox) {
            inbox.add(json);
        }
        if (!scripts) return;
        if (Build.VERSION.SDK_INT >= 19) com.disgusty.droid_codology.compat.Api19.evaluate(web, "window.__dcPull&&__dcPull()");
        else web.loadUrl("javascript:window.__dcPull&&__dcPull()");
    }

    void destroy() {
        disposed = true;
        ViewGroup p = (ViewGroup) web.getParent();
        if (p != null) p.removeView(web);
        web.destroy();
    }

    // ------------------------------------------------------------------ page preparation

    /** VS Code's theme variables (without the --vscode- prefix) for the current colors. */
    private java.util.LinkedHashMap<String, String> themeVars() {
        EditorColors c = colors();
        Skin k = wb.skin;
        java.util.LinkedHashMap<String, String> v = new java.util.LinkedHashMap<String, String>();
        v.put("font-family", "sans-serif");
        v.put("font-size", "13px");
        v.put("font-weight", "normal");
        v.put("editor-font-family", "monospace");
        v.put("editor-font-size", "13px");
        v.put("foreground", css(c.foreground));
        v.put("editor-background", css(c.background));
        v.put("editor-foreground", css(c.foreground));
        v.put("sideBar-background", css(k.sideBarBg));
        v.put("sideBar-foreground", css(k.sideBarFg));
        v.put("panel-background", css(k.panelBg));
        v.put("descriptionForeground", css(k.descriptionFg));
        v.put("errorForeground", css(k.errorFg));
        v.put("focusBorder", css(k.focusBorder));
        v.put("textLink-foreground", css(k.textLink));
        v.put("textLink-activeForeground", css(k.textLink));
        v.put("button-background", css(k.buttonBg));
        v.put("button-foreground", css(k.buttonFg));
        v.put("button-hoverBackground", css(k.buttonBg));
        v.put("button-secondaryBackground", css(k.secondaryButtonBg));
        v.put("button-secondaryForeground", css(k.secondaryButtonFg));
        v.put("input-background", css(k.inputBg));
        v.put("input-foreground", css(k.inputFg));
        v.put("input-border", css(k.inputBorder));
        v.put("input-placeholderForeground", css(k.inputPlaceholder));
        v.put("dropdown-background", css(k.inputBg));
        v.put("dropdown-foreground", css(k.inputFg));
        v.put("dropdown-border", css(k.inputBorder));
        v.put("badge-background", css(k.badgeBg));
        v.put("badge-foreground", css(k.badgeFg));
        v.put("list-hoverBackground", css(k.listPressed));
        v.put("list-activeSelectionBackground", css(k.listActiveBg));
        v.put("list-activeSelectionForeground", css(k.listActiveFg));
        v.put("widget-border", css(k.widgetBorder));
        v.put("editorWidget-background", css(k.widgetBg));
        v.put("panel-border", css(k.border));
        v.put("textCodeBlock-background", css(k.widgetBg));
        v.put("textPreformat-foreground", css(c.foreground));
        return v;
    }

    /** VS Code's page API; must run before the page's own scripts. */
    private String bootScript() {
        java.util.LinkedHashMap<String, String> tv = themeVars();
        StringBuilder vars = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : tv.entrySet()) {
            if (vars.length() > 0) vars.append(';');
            vars.append("--vscode-").append(e.getKey()).append('=').append(e.getValue());
        }
        String themeClass = wb.skin.dark ? "vscode-dark" : "vscode-light";
        String bg = tv.get("view".equals(kind) ? "sideBar-background" : "editor-background");
        // Body defaults use concrete values: old WebViews (Chrome < 49) don't know CSS variables.
        return "(function(){var b=window.DCWV,k='" + token + "',st=null;try{st=JSON.parse(b.getState(k))}catch(e){}"
                + "var api={postMessage:function(m){b.postMessage(k,JSON.stringify(m===undefined?null:m))},"
                + "getState:function(){return st},setState:function(s){st=s;b.setState(k,JSON.stringify(s===undefined?null:s));return s}};"
                + "window.acquireVsCodeApi=function(){return api};"
                + "window.__dcPull=function(){var s;while((s=b.take(k))){var d;try{d=JSON.parse(s)}catch(e){d=s}var ev;"
                + "try{ev=new MessageEvent('message',{data:d})}catch(e){ev=document.createEvent('MessageEvent');"
                + "ev.initMessageEvent('message',false,false,d,'','',window,null)}window.dispatchEvent(ev)}};"
                + "var r=document.documentElement,v=" + JSONObject.quote(vars.toString()) + ".split(';');"
                + "for(var i=0;i<v.length;i++){var kv=v[i].split('=');if(kv.length===2)r.style.setProperty(kv[0],kv[1])}"
                + "function ready(){var bd=document.body;if(!bd)return;bd.className=(bd.className?bd.className+' ':'')+'" + themeClass + "';"
                + "bd.setAttribute('data-vscode-theme-kind','" + themeClass + "');"
                + "if(!bd.style.backgroundColor)bd.style.backgroundColor='" + bg + "';"
                + "if(!bd.style.color)bd.style.color='" + tv.get("foreground") + "';"
                + "if(!bd.style.fontFamily)bd.style.fontFamily='sans-serif';"
                + "if(!bd.style.fontSize)bd.style.fontSize='13px';__dcPull()}"
                + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',ready);else ready()})();";
    }

    private static final Pattern CSS_VAR = Pattern.compile("var\\(\\s*--vscode-([\\w-]+)\\s*(?:,\\s*([^()]*(?:\\([^()]*\\)[^()]*)*))?\\)");
    private static int chromeVersion = -1;

    /** Major Chrome version of the system WebView (0 for the pre-Chromium WebView of Android 4.3 and older). */
    private int chrome() {
        if (chromeVersion < 0) {
            int v = 0;
            try {
                Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(WebSettings.getDefaultUserAgent(wb.activity));
                if (m.find()) v = Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
            chromeVersion = v;
        }
        return chromeVersion;
    }

    /** Chrome 49+ WebViews support CSS variables; older ones get them substituted in. */
    private boolean cssVarsSupported() {
        return chrome() >= 49;
    }

    /** Replaces var(--vscode-*) with the theme's values (its fallback, or as-is, when unknown). */
    String substituteVars(String text) {
        if (text.indexOf("--vscode-") < 0) return text;
        java.util.LinkedHashMap<String, String> tv = themeVars();
        Matcher m = CSS_VAR.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String v = tv.get(m.group(1));
            if (v == null) v = m.group(2) != null ? m.group(2).trim() : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String css(int argb) {
        int a = argb >>> 24;
        if (a == 255) return String.format(Locale.ROOT, "#%06x", argb & 0xFFFFFF);
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", (argb >> 16) & 255, (argb >> 8) & 255, argb & 255, a / 255f);
    }

    private static final Pattern CSP_META = Pattern.compile(
            "<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?content-security-policy[\"']?[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_ATTR = Pattern.compile("content\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML = Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** Injects the boot script first in the page and lets it through the page's Content Security Policy. */
    String prepare(String page) {
        if (!cssVarsSupported()) page = substituteVars(page);
        if (!scripts) return page;
        String boot = bootScript();
        String hash = "'sha256-" + sha256Base64(boot) + "'";
        // A CSP that limits scripts needs our script's hash (unless it already allows inline scripts).
        Matcher m = CSP_META.matcher(page);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String tag = m.group();
            if (chrome() < 40) {
                // CSP 1.0 only (Android 4.x, and 5.0 with its WebView never updated): nonces and hashes
                // would block every inline script.
                m.appendReplacement(out, "");
                continue;
            }
            Matcher c = CONTENT_ATTR.matcher(tag);
            if (c.find()) {
                String policy = c.group(2) != null ? c.group(2) : c.group(3);
                String fixed = allowScript(policy, hash);
                tag = tag.substring(0, c.start()) + "content=\"" + fixed.replace("\"", "&quot;") + "\"" + tag.substring(c.end());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(tag));
        }
        m.appendTail(out);
        page = out.toString();
        String script = "<script>" + boot + "</script>";
        Matcher h = HEAD.matcher(page);
        if (h.find()) return page.substring(0, h.end()) + script + page.substring(h.end());
        Matcher x = HTML.matcher(page);
        if (x.find()) return page.substring(0, x.end()) + "<head>" + script + "</head>" + page.substring(x.end());
        return script + page;
    }

    static String allowScript(String policy, String hash) {
        String[] parts = policy.split(";");
        int script = -1, def = -1;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim().toLowerCase(Locale.ROOT);
            if (p.startsWith("script-src ") || p.equals("script-src")) script = i;
            else if (p.startsWith("default-src ") || p.equals("default-src")) def = i;
        }
        int at = script >= 0 ? script : def;
        if (at < 0) return policy;
        String d = parts[at];
        if (d.toLowerCase(Locale.ROOT).contains("'unsafe-inline'") && !d.toLowerCase(Locale.ROOT).contains("'nonce-")
                && !d.toLowerCase(Locale.ROOT).contains("'sha")) return policy;
        // 'none' can't be combined with sources.
        d = d.replaceAll("(?i)'none'", "");
        parts[at] = d.trim() + " " + hash;
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static String sha256Base64(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            return Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ resources

    /** File behind a resource URL, if it lies inside an allowed root. */
    File resourceFile(String url) {
        if (!url.startsWith(ORIGIN + "/")) return null;
        String path = url.substring(ORIGIN.length());
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int f = path.indexOf('#');
        if (f >= 0) path = path.substring(0, f);
        path = Uri.decode(path);
        try {
            File file = new File(path).getCanonicalFile();
            for (File r : roots) {
                String root = r.getCanonicalPath();
                if (file.getPath().equals(root) || file.getPath().startsWith(root + File.separator)) return file.isFile() ? file : null;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static String mimeOf(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (ext.equals("js") || ext.equals("mjs")) return "text/javascript";
        if (ext.equals("css")) return "text/css";
        if (ext.equals("svg")) return "image/svg+xml";
        if (ext.equals("json")) return "application/json";
        if (ext.equals("woff")) return "font/woff";
        if (ext.equals("woff2")) return "font/woff2";
        if (ext.equals("ttf")) return "font/ttf";
        if (ext.equals("wasm")) return "application/wasm";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private final class Client extends PreviewClient {
        Client() {
            super(wb.activity);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url.startsWith(ORIGIN)) return true; // in-page navigation away is not supported
            return super.shouldOverrideUrlLoading(view, url);
        }

        /** The renderer died: close this panel or view (the extension hears onDidDispose), keep the app. */
        @Override
        @android.annotation.TargetApi(26)
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            if (disposed) return true;
            if ("panel".equals(kind)) {
                wb.closeWebviewPanel(ExtWebview.this, true);
            } else {
                host.webviewClosed(ExtWebview.this);
                wb.onWebviewViewUnavailable(viewId, null);
            }
            return true;
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (url.startsWith(ORIGIN + "/")) {
                File f = resourceFile(url);
                if (f == null) return new WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
                try {
                    String mime = mimeOf(f.getName());
                    if (mime.equals("text/css") && !cssVarsSupported()) {
                        String text = new String(ExtensionHost.readBytes(f), "UTF-8");
                        return new WebResourceResponse(mime, "UTF-8", new java.io.ByteArrayInputStream(substituteVars(text).getBytes("UTF-8")));
                    }
                    return new WebResourceResponse(mime, mime.startsWith("text/") ? "UTF-8" : null, new FileInputStream(f));
                } catch (IOException e) {
                    return null;
                }
            }
            return super.shouldInterceptRequest(view, url);
        }
    }

    // ------------------------------------------------------------------ page → extension (JavaBridge thread)

    final class Bridge {
        @JavascriptInterface
        public void postMessage(String key, final String json) {
            if (!token.equals(key)) return;
            wb.ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!disposed) host.webviewMessage(handle, json);
                }
            });
        }

        @JavascriptInterface
        public String getState(String key) {
            return token.equals(key) ? state : "null";
        }

        @JavascriptInterface
        public void setState(String key, String json) {
            if (token.equals(key)) state = json == null ? "null" : json;
        }

        @JavascriptInterface
        public String take(String key) {
            if (!token.equals(key)) return "";
            synchronized (inbox) {
                String s = inbox.poll();
                return s == null ? "" : s;
            }
        }
    }

    /** Placeholder shown in place of a webview that can't be shown. */
    static View note(Workbench wb, String text) {
        android.widget.TextView t = wb.skin.text(text, wb.skin.uiSp() - 1, wb.skin.descriptionFg, wb.skin.regular());
        t.setPadding(wb.skin.dp(16), wb.skin.dp(8), wb.skin.dp(16), wb.skin.dp(8));
        return t;
    }
}
