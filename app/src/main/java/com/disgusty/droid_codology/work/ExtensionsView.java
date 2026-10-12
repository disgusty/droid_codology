package com.disgusty.droid_codology.work;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.disgusty.droid_codology.ext.Extension;
import com.disgusty.droid_codology.ext.OpenVsx;
import com.disgusty.droid_codology.net.Http;
import com.disgusty.droid_codology.ui.Skin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;

import static com.disgusty.droid_codology.work.I18n.f;
import static com.disgusty.droid_codology.work.I18n.t;

/** The Extensions view: installed extensions and the Open VSX marketplace. */
final class ExtensionsView extends LinearLayout {
    private final Workbench wb;
    private final Skin skin;
    private final EditText query;
    private final TextView status;
    private final ListView list;
    private final ArrayList<Object> rows = new ArrayList<Object>(); // String header | Extension | OpenVsx.Item
    private final Adapter adapter = new Adapter();
    private List<OpenVsx.Item> market = new ArrayList<OpenVsx.Item>();
    private Future<?> job;
    private final HashMap<String, Drawable> icons = new HashMap<String, Drawable>();
    private final File iconDir;

    ExtensionsView(Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        iconDir = new File(wb.activity.getCacheDir(), "ext-icons");
        iconDir.mkdirs();
        setOrientation(VERTICAL);
        setBackgroundColor(skin.sideBarBg);
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("refresh", t("Refresh"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                search();
            }
        }));
        acts.add(new Skin.Action("more_h", t("More"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<Skin.MenuItem> m = new ArrayList<Skin.MenuItem>();
                m.add(new Skin.MenuItem(t("Install from VSIX..."), new Runnable() {
                    @Override
                    public void run() {
                        if (ExtensionsView.this.wb.activity instanceof Workbench.Opener) ((Workbench.Opener) ExtensionsView.this.wb.activity).pickVsix();
                    }
                }));
                ExtensionsView.this.skin.popup(v, m);
            }
        }));
        addView(skin.sideBarHeader(t("Extensions"), acts));
        LinearLayout box = new LinearLayout(wb.activity);
        box.setPadding(skin.dp(8), 0, skin.dp(8), skin.dp(6));
        query = skin.input(t("Search Extensions in Open VSX"));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                search();
                return true;
            }
        });
        box.addView(query, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(box);
        status = skin.caption("");
        status.setPadding(skin.dp(16), 0, skin.dp(16), skin.dp(4));
        addView(status);
        list = new ListView(wb.activity);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setAdapter(adapter);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        rebuild();
    }

    void refreshInstalled() {
        rebuild();
    }

    private void rebuild() {
        rows.clear();
        List<Extension> inst = wb.extensions.installed();
        rows.add(t("Installed") + " (" + inst.size() + ")");
        rows.addAll(inst);
        if (!market.isEmpty()) {
            rows.add(query.getText().length() == 0 ? t("Popular") : t("Marketplace"));
            for (OpenVsx.Item it : market) if (wb.extensions.find(it.id()) == null) rows.add(it);
        }
        adapter.notifyDataSetChanged();
    }

    void search() {
        if (job != null) job.cancel(true);
        final String q = query.getText().toString().trim();
        status.setText(t("Searching..."));
        job = wb.bg.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<OpenVsx.Item> res = OpenVsx.search(q, 40);
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            market = res;
                            status.setText(res.isEmpty() ? t("No results") : "");
                            rebuild();
                        }
                    });
                } catch (final Exception e) {
                    wb.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText(f("Can't reach the marketplace: {0}", String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        });
    }

    /** Extension icons: Open VSX icon URLs, cached on disk; installed ones from their folder. */
    private Drawable icon(final String key, final String url, File local) {
        Drawable d = icons.get(key);
        if (d != null) return d;
        if (local != null && local.isFile()) {
            d = decode(local);
            if (d != null) {
                icons.put(key, d);
                return d;
            }
        }
        final File cached = new File(iconDir, key.replaceAll("[^\\w.-]", "_") + ".png");
        if (cached.isFile()) {
            d = decode(cached);
            if (d != null) {
                icons.put(key, d);
                return d;
            }
        }
        if (url != null && url.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg)(\\?.*)?$")) {
            icons.put(key, skin.icon("extensions", skin.descriptionFg, 32));
            wb.bg.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Http.download(url, cached, null, null);
                        final Drawable dd = decode(cached);
                        if (dd != null) {
                            wb.ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    icons.put(key, dd);
                                    adapter.notifyDataSetChanged();
                                }
                            });
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        return skin.icon("extensions", skin.descriptionFg, 32);
    }

    private Drawable decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        int size = skin.dp(40);
        Bitmap s = Bitmap.createScaledBitmap(b, size, size, true);
        if (s != b) b.recycle();
        return new BitmapDrawable(wb.activity.getResources(), s);
    }

    private View row(Object o) {
        LinearLayout r = new LinearLayout(wb.activity);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(skin.dp(12), skin.dp(8), skin.dp(12), skin.dp(8));
        ImageView iv = new ImageView(wb.activity);
        LinearLayout texts = new LinearLayout(wb.activity);
        texts.setOrientation(VERTICAL);
        String title, sub, desc;
        if (o instanceof Extension) {
            final Extension e = (Extension) o;
            String icon = e.manifest.optString("icon", null);
            iv.setImageDrawable(icon(e.id, null, icon == null ? null : e.file(icon)));
            title = e.displayName + (e.enabled ? "" : "  (" + t("Disabled") + ")");
            sub = e.publisher + "  ·  v" + e.version;
            desc = e.capabilities().isEmpty() ? e.description : e.capabilities();
            r.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    wb.openExtensionPage(e, null);
                }
            });
            if (!e.enabled) r.setAlpha(0.6f);
        } else {
            final OpenVsx.Item it = (OpenVsx.Item) o;
            iv.setImageDrawable(icon(it.id(), it.iconUrl, null));
            title = it.displayName;
            sub = it.namespace + "  ·  ⤓ " + compact(it.downloads) + (it.rating > 0 ? "  ·  ★ " + String.format(Locale.ROOT, "%.1f", it.rating) : "");
            desc = it.description;
            r.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    wb.openExtensionPage(null, it);
                }
            });
        }
        LayoutParams ilp = new LayoutParams(skin.dp(40), skin.dp(40));
        ilp.rightMargin = skin.dp(12);
        r.addView(iv, ilp);
        TextView tt = skin.text(title, skin.uiSp(), skin.sideBarFg, skin.medium());
        tt.setSingleLine(true);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(tt);
        TextView dd = skin.caption(desc);
        dd.setSingleLine(true);
        dd.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(dd);
        TextView ss = skin.text(sub, skin.uiSp() - 3, skin.descriptionFg, skin.regular());
        ss.setSingleLine(true);
        texts.addView(ss);
        r.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.setBackgroundDrawable(skin.listSelector(false));
        r.setClickable(true);
        return r;
    }

    static String compact(long n) {
        if (n >= 1000000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 1000) return String.format(Locale.ROOT, "%.0fK", n / 1e3);
        return String.valueOf(n);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Object o = rows.get(i);
            View v;
            if (o instanceof String) {
                v = skin.sectionHeader((String) o, true, null);
                v.setClickable(false);
            } else v = row(o);
            v.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
    }
}
