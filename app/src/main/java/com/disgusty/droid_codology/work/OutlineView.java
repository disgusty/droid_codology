package com.disgusty.droid_codology.work;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.disgusty.droid_codology.lang.Symbols;
import com.disgusty.droid_codology.ui.Skin;

import java.util.ArrayList;
import java.util.List;

import static com.disgusty.droid_codology.work.I18n.t;

/** The Outline view: symbols of the active editor, indented by nesting; tap to jump. */
final class OutlineView extends LinearLayout {
    private final Workbench wb;
    private final Skin skin;
    private final ListView list;
    private final TextView empty;
    private List<Symbols.Symbol> symbols = new ArrayList<Symbols.Symbol>();
    private final Adapter adapter = new Adapter();
    private boolean sortByName;

    OutlineView(Workbench wb) {
        super(wb.activity);
        this.wb = wb;
        this.skin = wb.skin;
        setOrientation(VERTICAL);
        setBackgroundColor(skin.sideBarBg);
        ArrayList<Skin.Action> acts = new ArrayList<Skin.Action>();
        acts.add(new Skin.Action("sort", t("Sort By: Name"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                sortByName = !sortByName;
                refresh();
            }
        }));
        acts.add(new Skin.Action("refresh", t("Refresh"), new OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        }));
        addView(skin.sideBarHeader(t("Outline"), acts));
        empty = skin.caption(t("The active editor cannot provide outline information."));
        empty.setPadding(skin.dp(16), skin.dp(8), skin.dp(16), skin.dp(8));
        addView(empty);
        list = new ListView(wb.activity);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setFastScrollEnabled(true);
        list.setAdapter(adapter);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    void refresh() {
        Document d = wb.activeDocument();
        symbols = d == null ? new ArrayList<Symbols.Symbol>() : new ArrayList<Symbols.Symbol>(wb.symbols(d));
        if (sortByName) {
            java.util.Collections.sort(symbols, new java.util.Comparator<Symbols.Symbol>() {
                @Override
                public int compare(Symbols.Symbol a, Symbols.Symbol b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
        }
        empty.setVisibility(symbols.isEmpty() ? VISIBLE : GONE);
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return symbols.size();
        }

        @Override
        public Object getItem(int i) {
            return symbols.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            final Symbols.Symbol s = symbols.get(i);
            LinearLayout row = skin.treeRow(sortByName ? 0 : Math.min(8, s.depth), null,
                    skin.icon(Symbols.icon(s.kind), Symbols.color(s.kind), 16), s.name, null, null);
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    wb.goTo(s.line, s.column);
                    wb.closeOverlaySideBar();
                }
            });
            row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }
    }
}
