"""Downloads the original icon sets and generates IconData.java with their SVG path data.

Sets:
  codicon  - VS Code Codicons (microsoft/vscode-codicons, CC BY 4.0)      -> Original style
  material - Material Icons, filled (google/material-design-icons, Apache 2.0) -> Holo / Material 1
  symbols  - Material Symbols Outlined (same repo, Apache 2.0)           -> Material 3

Usage: python tools/icons.py   (cache in tools/icons_cache, output in app/src/main/java/.../ui/IconData.java)
"""
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", "icons_cache")
OUT = os.path.join(ROOT, "app", "src", "main", "java", "com", "disgusty", "droid_codology", "ui", "IconData.java")

CODICON = "https://raw.githubusercontent.com/microsoft/vscode-codicons/main/src/icons/{n}.svg"
MATERIAL = "https://raw.githubusercontent.com/google/material-design-icons/master/src/{c}/{n}/materialicons/24px.svg"
SYMBOLS = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web/{n}/materialsymbolsoutlined/{n}_24px.svg"

# semantic name: (codicon, (material category, material name), material symbol)
ICONS = {
    "menu": ("menu", ("navigation", "menu"), "menu"),
    "explorer": ("files", ("file", "folder_open"), "folder_open"),
    "search": ("search", ("action", "search"), "search"),
    "scm": ("source-control", ("social", "share"), "account_tree"),
    "extensions": ("extensions", ("action", "extension"), "extension"),
    "settings": ("settings-gear", ("action", "settings"), "settings"),
    "account": ("account", ("action", "account_circle"), "account_circle"),
    "close": ("close", ("navigation", "close"), "close"),
    "dirty": ("circle-filled", ("av", "fiber_manual_record"), "circle"),
    "chevron_right": ("chevron-right", ("navigation", "chevron_right"), "chevron_right"),
    "chevron_down": ("chevron-down", ("navigation", "expand_more"), "expand_more"),
    "chevron_up": ("chevron-up", ("navigation", "expand_less"), "expand_less"),
    "new_file": ("new-file", ("action", "note_add"), "note_add"),
    "new_folder": ("new-folder", ("file", "create_new_folder"), "create_new_folder"),
    "refresh": ("refresh", ("navigation", "refresh"), "refresh"),
    "collapse_all": ("collapse-all", ("navigation", "unfold_less"), "unfold_less"),
    "more": ("ellipsis", ("navigation", "more_vert"), "more_vert"),
    "more_h": ("ellipsis", ("navigation", "more_horiz"), "more_horiz"),
    "split": ("split-horizontal", ("action", "view_column"), "splitscreen_right"),
    "folder": ("folder", ("file", "folder"), "folder"),
    "folder_open": ("folder-opened", ("file", "folder_open"), "folder_open"),
    "file": ("file", ("editor", "insert_drive_file"), "draft"),
    "file_code": ("file-code", ("action", "code"), "code"),
    "save": ("save", ("content", "save"), "save"),
    "save_all": ("save-all", ("content", "save"), "save_as"),
    "undo": ("discard", ("content", "undo"), "undo"),
    "redo": ("redo", ("content", "redo"), "redo"),
    "replace": ("replace", ("action", "find_replace"), "find_replace"),
    "replace_all": ("replace-all", ("action", "find_replace"), "find_replace"),
    "case": ("case-sensitive", ("editor", "text_fields"), "match_case"),
    "whole_word": ("whole-word", ("editor", "space_bar"), "match_word"),
    "regex": ("regex", ("action", "code"), "regular_expression"),
    "arrow_up": ("arrow-up", ("navigation", "arrow_upward"), "arrow_upward"),
    "arrow_down": ("arrow-down", ("navigation", "arrow_downward"), "arrow_downward"),
    "arrow_left": ("arrow-left", ("navigation", "arrow_back"), "arrow_back"),
    "arrow_right": ("arrow-right", ("navigation", "arrow_forward"), "arrow_forward"),
    "check": ("check", ("navigation", "check"), "check"),
    "error": ("error", ("alert", "error"), "error"),
    "warning": ("warning", ("alert", "warning"), "warning"),
    "info": ("info", ("action", "info"), "info"),
    "bell": ("bell", ("social", "notifications"), "notifications"),
    "sync": ("sync", ("notification", "sync"), "sync"),
    "terminal": ("terminal", ("hardware", "computer"), "terminal"),
    "run": ("play", ("av", "play_arrow"), "play_arrow"),
    "branch": ("git-branch", ("communication", "call_split"), "fork_right"),
    "word_wrap": ("word-wrap", ("editor", "wrap_text"), "wrap_text"),
    "zoom_in": ("zoom-in", ("action", "zoom_in"), "zoom_in"),
    "zoom_out": ("zoom-out", ("action", "zoom_out"), "zoom_out"),
    "add": ("add", ("content", "add"), "add"),
    "trash": ("trash", ("action", "delete"), "delete"),
    "edit": ("edit", ("editor", "mode_edit"), "edit"),
    "copy": ("copy", ("content", "content_copy"), "content_copy"),
    "paste": ("clippy", ("content", "content_paste"), "content_paste"),
    "cut": ("scissors", ("content", "content_cut"), "content_cut"),
    "select_all": ("list-selection", ("content", "select_all"), "select_all"),
    "keyboard": ("record-keys", ("hardware", "keyboard"), "keyboard"),
    "history": ("history", ("action", "history"), "history"),
    "sidebar": ("layout-sidebar-left", ("action", "view_quilt"), "left_panel_open"),
    "filter": ("filter", ("content", "filter_list"), "filter_list"),
    "link": ("link", ("content", "link"), "link"),
    "globe": ("globe", ("action", "language"), "language"),
    "cloud": ("cloud", ("file", "cloud"), "cloud"),
    "key": ("key", ("communication", "vpn_key"), "key"),
    "lock": ("lock", ("action", "lock"), "lock"),
    "person": ("person", ("social", "person"), "person"),
    "mail": ("mail", ("content", "mail"), "mail"),
    "github": ("github", ("action", "code"), "code"),
    "palette": ("symbol-color", ("image", "palette"), "palette"),
    "brush": ("paintcan", ("image", "brush"), "brush"),
    "color_mode": ("color-mode", ("image", "brightness_4"), "dark_mode"),
    "go_to_file": ("go-to-file", ("action", "open_in_new"), "open_in_new"),
    "home": ("home", ("action", "home"), "home"),
    "comment": ("comment", ("editor", "insert_comment"), "comment"),
    "indent": ("arrow-right", ("editor", "format_indent_increase"), "format_indent_increase"),
    "outdent": ("arrow-left", ("editor", "format_indent_decrease"), "format_indent_decrease"),
    "tab": ("arrow-right", ("hardware", "keyboard_tab"), "keyboard_tab"),
    "sort": ("list-ordered", ("content", "sort"), "sort"),
    "go_to_line": ("list-ordered", ("editor", "format_list_numbered"), "format_list_numbered"),
    "command": ("symbol-event", ("image", "flash_on"), "bolt"),
    "preview": ("open-preview", ("action", "visibility"), "visibility"),
    "info_outline": ("info", ("action", "info_outline"), "info"),
    "pin": ("pin", ("content", "push_pin" if False else "flag"), "keep"),
    "rename": ("edit", ("editor", "mode_edit"), "edit_note"),
    "open": ("folder-opened", ("file", "folder_open"), "file_open"),
    "storage": ("database", ("device", "storage"), "hard_drive"),
    "sd": ("database", ("device", "sd_storage"), "sd_card"),
    "android": ("device-mobile", ("action", "android"), "android"),
    "encoding": ("file-binary", ("editor", "text_fields"), "text_fields"),
    "minimap": ("layout", ("action", "view_agenda"), "view_agenda"),
    "fullscreen": ("screen-full", ("navigation", "fullscreen"), "fullscreen"),
    "lightbulb": ("lightbulb", ("action", "lightbulb_outline"), "lightbulb"),
    "sparkle": ("sparkle", ("image", "auto_awesome"), "auto_awesome"),
    "debug": ("debug-alt", ("action", "bug_report"), "bug_report"),
    "output": ("output", ("action", "subject"), "subject"),
    "problems": ("warning", ("alert", "warning"), "warning"),
    "panel": ("layout-panel", ("action", "view_day"), "bottom_panel_open"),
    "outline": ("list-tree", ("action", "list"), "account_tree"),
    "symbol_method": ("symbol-method", ("action", "code"), "function"),
    "symbol_class": ("symbol-class", ("action", "class"), "deployed_code"),
    "symbol_variable": ("symbol-variable", ("action", "label"), "variables"),
    "symbol_field": ("symbol-field", ("action", "label_outline"), "label"),
    "symbol_keyword": ("symbol-keyword", ("editor", "short_text"), "short_text"),
    "symbol_snippet": ("symbol-snippet", ("editor", "insert_drive_file"), "integration_instructions"),
    "symbol_text": ("symbol-string", ("editor", "text_fields"), "text_fields"),
    "chat": ("comment-discussion", ("communication", "chat"), "chat"),
    "send": ("send", ("content", "send"), "send"),
    "stop": ("debug-stop", ("av", "stop"), "stop"),
    "insert": ("insert", ("editor", "vertical_align_bottom"), "input"),
    "sign_out": ("sign-out", ("action", "exit_to_app"), "logout"),
    "rocket": ("rocket", ("places", "fitness_center"), "rocket_launch"),
}

UA = {"User-Agent": "droid_codology-icon-tool"}


def fetch(url, path):
    if os.path.exists(path):
        with open(path, "rb") as f:
            return f.read()
    req = urllib.request.Request(url, headers=UA)
    try:
        data = urllib.request.urlopen(req, timeout=30).read()
    except Exception as e:  # noqa: BLE001
        return None
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return data


def num(v):
    return re.sub(r"\.0+$", "", ("%.3f" % float(v)).rstrip("0")) if "." in ("%.3f" % float(v)) else str(v)


def shape_to_path(el):
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path":
        return a.get("d", "")
    if tag == "circle":
        cx, cy, r = float(a.get("cx", 0)), float(a.get("cy", 0)), float(a["r"])
        return "M%s %sa%s %s 0 1 0 %s 0a%s %s 0 1 0 %s 0z" % (cx - r, cy, r, r, 2 * r, r, r, -2 * r)
    if tag == "rect":
        x, y = float(a.get("x", 0)), float(a.get("y", 0))
        w, h = float(a["width"]), float(a["height"])
        return "M%s %sh%sv%sh%sz" % (x, y, w, h, -w)
    if tag == "polygon":
        pts = a["points"].replace(",", " ").split()
        return "M" + " ".join(pts) + "z"
    return ""


def parse_svg(data):
    root = ET.fromstring(data)
    vb = root.attrib.get("viewBox", "0 0 24 24").replace(",", " ").split()
    vb = [float(x) for x in vb]
    parts = []
    evenodd = False
    for el in root.iter():
        tag = el.tag.split("}")[-1]
        if tag in ("path", "circle", "rect", "polygon"):
            if el.attrib.get("fill") == "none" and tag == "path" and el.attrib.get("d", "").startswith("M0 0h24v24H0"):
                continue
            if el.attrib.get("fill") == "none":
                continue
            if el.attrib.get("fill-rule") == "evenodd" or el.attrib.get("clip-rule") == "evenodd":
                evenodd = True
            d = shape_to_path(el)
            if d:
                parts.append(d)
    return vb, " ".join(parts), evenodd


def java_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main():
    sets = {"codicon": {}, "material": {}, "symbols": {}}
    missing = []
    for name, (cod, (cat, mat), sym) in ICONS.items():
        for set_name, url, cache in (
            ("codicon", CODICON.format(n=cod), os.path.join(CACHE, "codicon", cod + ".svg")),
            ("material", MATERIAL.format(c=cat, n=mat), os.path.join(CACHE, "material", cat + "_" + mat + ".svg")),
            ("symbols", SYMBOLS.format(n=sym), os.path.join(CACHE, "symbols", sym + ".svg")),
        ):
            data = fetch(url, cache)
            if data is None:
                missing.append((set_name, name, url))
                continue
            vb, d, eo = parse_svg(data)
            sets[set_name][name] = (vb, d, eo)
    out = []
    out.append("package com.disgusty.droid_codology.ui;\n")
    out.append("/**\n * Generated by tools/icons.py from the original icon sets - do not edit.\n"
               " * Codicons (c) Microsoft, CC BY 4.0. Material Icons / Material Symbols (c) Google, Apache License 2.0.\n */")
    out.append("final class IconData {\n    private IconData() {\n    }\n")
    out.append("    /** Rows of {name, viewBox \"minX minY w h\", path data, \"1\" if even-odd}. */")
    for set_name, const in (("codicon", "CODICON"), ("material", "MATERIAL"), ("symbols", "SYMBOLS")):
        out.append("    static final String[][] %s = {" % const)
        for name in ICONS:
            if name not in sets[set_name]:
                continue
            vb, d, eo = sets[set_name][name]
            out.append("            {%s, %s, %s, %s}," % (java_str(name), java_str(" ".join(num(v) for v in vb)), java_str(d), java_str("1" if eo else "0")))
        out.append("    };\n")
    out.append("}\n")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out))
    for m in missing:
        print("missing", *m)
    print("icons:", {k: len(v) for k, v in sets.items()})


if __name__ == "__main__":
    main()
