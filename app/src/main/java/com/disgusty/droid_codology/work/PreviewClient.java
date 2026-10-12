package com.disgusty.droid_codology.work;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.disgusty.droid_codology.net.Http;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * WebView client for rendered Markdown (previews, extension READMEs): links open in the browser,
 * and on Android &lt; 5.0, whose WebView can't reach most HTTPS hosts anymore, remote images and
 * styles are fetched through {@link Http} (our own TLS stack there).
 */
class PreviewClient extends WebViewClient {
    private final Activity activity;

    PreviewClient(Activity activity) {
        this.activity = activity;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        // Anchors stay in the page.
        if (url.startsWith("#") || url.contains("#") && url.startsWith("file:")) return false;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
        return true;
    }

    /**
     * Android 8+: all WebViews share one renderer process; when it dies, a WebView that doesn't
     * handle it takes the whole app down (unsaved text included). This one is dropped instead.
     */
    @Override
    @android.annotation.TargetApi(26)
    public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
        dropDeadWebView(view);
        return true;
    }

    static void dropDeadWebView(WebView view) {
        android.view.ViewParent p = view.getParent();
        if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(view);
        try {
            view.destroy();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        if (Build.VERSION.SDK_INT >= 21 || !url.startsWith("https:")) return null;
        try {
            Http.Response r = Http.get(url, null);
            if (!r.ok()) return null;
            String type = "application/octet-stream";
            List<String> ct = r.headers == null ? null : r.headers.get("content-type");
            if (ct != null && !ct.isEmpty()) type = ct.get(0);
            int semi = type.indexOf(';');
            if (semi >= 0) type = type.substring(0, semi).trim();
            return new WebResourceResponse(type, null, new ByteArrayInputStream(r.body));
        } catch (Exception e) {
            return null;
        }
    }
}
