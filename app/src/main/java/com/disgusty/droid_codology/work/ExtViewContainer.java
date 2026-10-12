package com.disgusty.droid_codology.work;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.disgusty.droid_codology.ui.Icons;
import com.disgusty.droid_codology.ui.Skin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.disgusty.droid_codology.work.I18n.t;

/**
 * A side bar view container contributed by an extension (contributes.viewsContainers.activitybar):
 * its views, each a tree the extension fills through its TreeDataProvider or a webview from its
 * WebviewViewProvider.
 */
final class ExtViewContainer extends LinearLayout {
    /** What an extension contributes for one activity bar entry. */
    static final class Spec {
        final String key, extId, title, iconName;
        final List<JSONObject> views;

        Spec(String key, String extId, String title, String iconName, List<JSONObject> views) {
            this.key = key;
            this.extId = extId;
            this.title = title;
            this.iconName = iconName;
            this.views = views;
        }
    }

    private final Workbench wb;
    private final Skin skin;
    final Spec spec;
    private final LinearLayout body;
    private final HashMap<String, Section> sections = new HashMap<String, Section>();
    /** One webview view alone fills the container; otherwise each webview gets a fixed height. */
    private final boolean singleWebview;

    private final class Section {
        final String viewId, name;
        final boolean webview;
        final LinearLayout list;
        boolean open = true, loaded;
        /** Webview views: the extension's webview once resolved, and whether we asked for it. */
        ExtWebview web;
        boolean requested;

        Section(JSONObject v) {
            viewId = v.optString("id");
            String n = v.optString("name", viewId);
            name = n.startsWith("%") ? viewId : n;
            webview = "webview".equals(v.optString("type"));
            list = new LinearLayout(wb.activity);
            list.setOrientation(VERTICAL);
        }
    }

    ExtViewContainer(Workbench wb, Spec spec) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        this.spec = spec;
        setOrientation(VERTICAL);
        setBackgroundColor(skin.sideBarBg);
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("refresh", t("Refresh"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                reloadAll();
            }
        }));
        addView(skin.sideBarHeader(spec.title, acts));
        singleWebview = spec.views.size() == 1 && "webview".equals(spec.views.get(0).optString("type"));
        body = new LinearLayout(wb.activity);
        body.setOrientation(VERTICAL);
        if (singleWebview) {
            addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            ScrollView sv = new ScrollView(wb.activity);
            sv.addView(body);
            addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        for (JSONObject v : spec.views) {
            final Section s = new Section(v);
            sections.put(s.viewId, s);
            if (spec.views.size() > 1) {
                body.addView(skin.sectionHeader(s.name, true, new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        s.open = !s.open;
                        s.list.setVisibility(s.open ? VISIBLE : GONE);
                        if (s.open && !s.loaded) load(s, null, s.list, 0);
                    }
                }));
            }
            body.addView(s.list, singleWebview ? new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1)
                    : new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (spec.views.isEmpty()) note(body, t("This extension contributes no views here."));
    }

    /** Loads the roots the first time the container is shown. */
    void onShown() {
        for (Section s : sections.values()) if (s.open && !s.loaded) load(s, null, s.list, 0);
    }

    void reloadAll() {
        for (Section s : sections.values()) {
            if (s.webview && s.web != null) {
                // Webview views start over: the extension resolves a fresh one.
                ExtWebview w = s.web;
                s.web = null;
                s.requested = false;
                wb.extHost.webviewClosed(w);
                s.list.removeAllViews();
            }
            s.loaded = false;
            if (s.open) load(s, null, s.list, 0);
        }
    }

    void onTreeChanged(String viewId) {
        Section s = sections.get(viewId);
        if (s != null && s.loaded && !s.webview) load(s, null, s.list, 0);
    }

    /** Closes this container's webviews (the container is being thrown away). */
    void dispose() {
        for (Section s : sections.values()) {
            if (s.web == null) continue;
            ExtWebview w = s.web;
            s.web = null;
            wb.extHost.webviewClosed(w);
        }
    }

    // ------------------------------------------------------------------ webview views

    /** The extension registered a provider late: resolve views that are waiting for it. */
    void onWebviewRegistered(String viewId) {
        Section s = sections.get(viewId);
        if (s != null && s.webview && s.web == null && !s.requested && s.loaded && s.open) load(s, null, s.list, 0);
    }

    /** A resolved webview for one of our views; false if it isn't ours. */
    boolean attachWebview(ExtWebview w) {
        Section s = w.viewId == null ? null : sections.get(w.viewId);
        if (s == null || !s.webview || s.web != null) return false;
        s.web = w;
        s.requested = false;
        s.list.removeAllViews();
        if (w.web.getParent() != null) ((ViewGroup) w.web.getParent()).removeView(w.web);
        s.list.addView(w.web, singleWebview ? new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                : new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, skin.dp(360)));
        return true;
    }

    void webviewUnavailable(String viewId, String message) {
        Section s = sections.get(viewId);
        if (s == null || !s.webview) return;
        s.web = null;
        s.requested = false;
        s.list.removeAllViews();
        if (message != null && !message.isEmpty()) note(s.list, message);
        else s.loaded = false;
    }

    // ------------------------------------------------------------------ trees

    private void note(LinearLayout into, String text) {
        TextView t = skin.text(text, skin.uiSp() - 1, skin.descriptionFg, skin.regular());
        t.setPadding(skin.dp(16), skin.dp(8), skin.dp(16), skin.dp(8));
        into.addView(t);
    }

    private void load(final Section s, final String parentHandle, final LinearLayout into, final int depth) {
        if (parentHandle == null) s.loaded = true;
        if (s.webview) {
            if (s.web != null || s.requested) return;
            s.requested = true;
            into.removeAllViews();
            note(into, t("Loading..."));
            wb.extHost.resolveWebviewView(s.viewId);
            return;
        }
        if (into.getChildCount() == 0) note(into, t("Loading..."));
        wb.extHost.treeChildren(s.viewId, parentHandle, new LspManager.Result<Object>() {
            @Override
            public void done(Object value) {
                into.removeAllViews();
                if (!(value instanceof JSONArray)) {
                    note(into, value == null ? t("Nothing to show.") : String.valueOf(value));
                    return;
                }
                JSONArray items = (JSONArray) value;
                if (items.length() == 0 && parentHandle == null) note(into, t("Nothing to show."));
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it != null) into.addView(row(s, it, depth));
                }
            }
        });
    }

    private View row(final Section s, final JSONObject it, final int depth) {
        final String handle = it.optString("handle");
        final int collapsible = it.optInt("collapsible");
        String icon = it.isNull("icon") ? null : it.optString("icon", null);
        android.graphics.drawable.Drawable d = icon == null ? null
                : Icons.has(Icons.SET_CODICON, icon) ? skin.icon(icon, skin.descriptionFg, 16)
                // An icon we don't have: keep its space so the labels of siblings stay aligned.
                : skin.icon("dirty", android.graphics.Color.TRANSPARENT, 16);
        final LinearLayout wrap = new LinearLayout(wb.activity);
        wrap.setOrientation(VERTICAL);
        final boolean[] expanded = {collapsible == 2};
        final LinearLayout children = new LinearLayout(wb.activity);
        children.setOrientation(VERTICAL);
        final LinearLayout r = skin.treeRow(depth, collapsible == 0 ? null : expanded[0], d, it.optString("label"),
                it.optString("description", ""), null);
        r.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (collapsible != 0) {
                    expanded[0] = !expanded[0];
                    children.setVisibility(expanded[0] ? VISIBLE : GONE);
                    if (expanded[0] && children.getChildCount() == 0) load(s, handle, children, depth + 1);
                    // Redraw the chevron.
                    int at = wrap.indexOfChild(v);
                    LinearLayout nr = skin.treeRow(depth, expanded[0], null, it.optString("label"), it.optString("description", ""), null);
                    nr.setOnClickListener(this);
                    wrap.removeViewAt(at);
                    wrap.addView(nr, at);
                }
                if (it.optBoolean("hasCommand")) wb.extHost.treeClick(handle);
            }
        });
        final String tip = it.optString("tooltip", "");
        if (!tip.isEmpty()) {
            r.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    wb.showMessage(tip);
                    return true;
                }
            });
        }
        wrap.addView(r);
        children.setVisibility(expanded[0] ? VISIBLE : GONE);
        wrap.addView(children);
        if (expanded[0]) load(s, handle, children, depth + 1);
        return wrap;
    }
}
