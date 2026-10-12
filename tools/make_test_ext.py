"""Builds tools/demo-ext.vsix: an ES5 extension exercising the JS extension host API."""
import json
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))

PACKAGE = {
    "name": "host-demo", "publisher": "droidcodology", "displayName": "Extension Host Demo",
    "description": "Exercises the droid_codology extension API", "version": "0.0.1",
    "engines": {"vscode": "^1.60.0"},
    "activationEvents": ["onStartupFinished"],
    "main": "./out/extension.js",
    "contributes": {
        "commands": [
            {"command": "demo.hello", "title": "Hello World", "category": "Demo"},
            {"command": "demo.upper", "title": "Uppercase Selection", "category": "Demo"},
            {"command": "demo.pick", "title": "Pick and Insert", "category": "Demo"},
            {"command": "demo.panel", "title": "Open Webview Panel", "category": "Demo"},
        ],
        "configuration": {"title": "Demo", "properties": {"demo.greeting": {"type": "string", "default": "hi"}}},
        "viewsContainers": {"activitybar": [{"id": "demoBar", "title": "Demo Tasks", "icon": "media/tasks.svg"}]},
        "views": {"demoBar": [
            {"id": "demo.tasks", "name": "Tasks"},
            {"id": "demo.web", "name": "Web Panel", "type": "webview"},
        ]},
    },
}

EXTENSION_JS = r"""
var vscode = require('vscode');
var path = require('path');
var helper = require('./lib/helper');

function activate(context) {
    var out = vscode.window.createOutputChannel('Demo');
    var item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
    item.text = '$(rocket) Hello';
    item.command = 'demo.hello';
    item.show();
    context.subscriptions.push(item);

    context.subscriptions.push(vscode.commands.registerCommand('demo.hello', function () {
        var n = context.globalState.get('count', 0) + 1;
        context.globalState.update('count', n);
        return vscode.window.showInformationMessage('Hello from JS #' + n + ' ' + helper.greet(path.basename('/a/b/c.txt')), 'Yes', 'No')
            .then(function (choice) {
                out.appendLine('picked ' + choice);
                item.text = '$(rocket) ' + (choice || 'none');
            });
    }));

    context.subscriptions.push(vscode.commands.registerTextEditorCommand('demo.upper', function (editor, edit) {
        editor.selections.forEach(function (s) {
            edit.replace(s, editor.document.getText(s).toUpperCase());
        });
    }));

    context.subscriptions.push(vscode.commands.registerCommand('demo.pick', function () {
        return vscode.window.showQuickPick(['alpha', 'beta', 'gamma'], {placeHolder: 'Pick one'}).then(function (v) {
            if (!v) return;
            return vscode.window.showInputBox({prompt: 'Say something', value: v}).then(function (t) {
                var ed = vscode.window.activeTextEditor;
                if (ed && t) return ed.insertSnippet(new vscode.SnippetString(t + ' ${1:here}$0'));
            });
        });
    }));

    context.subscriptions.push(vscode.languages.registerDocumentFormattingEditProvider('plaintext', {
        provideDocumentFormattingEdits: function (doc) {
            var edits = [];
            for (var i = 0; i < doc.lineCount; i++) {
                var l = doc.lineAt(i), t = l.text.replace(/\s+$/, '');
                if (t !== l.text) edits.push(vscode.TextEdit.replace(l.range, t));
            }
            out.appendLine('formatted ' + edits.length + ' lines');
            return edits;
        }
    }));

    var groups = {Today: ['Write tests', 'Fix bug #12'], Later: ['Refactor', 'Docs']};
    var changed = new vscode.EventEmitter();
    var done = {};
    vscode.window.registerTreeDataProvider('demo.tasks', {
        onDidChangeTreeData: changed.event,
        getChildren: function (el) {
            if (!el) return Object.keys(groups);
            return groups[el] || [];
        },
        getTreeItem: function (el) {
            var group = !!groups[el];
            var it = new vscode.TreeItem(el, group ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.None);
            if (group) it.description = groups[el].length + ' items';
            else {
                it.iconPath = new vscode.ThemeIcon(done[el] ? 'check' : 'circle-outline');
                it.tooltip = 'Tap to toggle';
                it.command = {command: 'demo.toggle', title: 'Toggle', arguments: [el]};
            }
            return it;
        }
    });
    context.subscriptions.push(vscode.commands.registerCommand('demo.toggle', function (name) {
        done[name] = !done[name];
        changed.fire();
    }));

    function page(webview, title) {
        var nonce = 'n' + Date.now();
        var img = webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri, 'media', 'tasks.svg'));
        return '<!DOCTYPE html><html><head><meta charset="utf-8">'
            + '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src ' + webview.cspSource
            + '; style-src \'unsafe-inline\'; script-src \'nonce-' + nonce + '\';">'
            + '<style>button{background:var(--vscode-button-background);color:var(--vscode-button-foreground);border:0;padding:6px 12px}</style>'
            + '</head><body><h3>' + title + '</h3><img id="i" src="' + img + '" width="32" height="32">'
            + '<p id="out">waiting</p><button id="b">Ping extension</button>'
            + '<script nonce="' + nonce + '">var vs=acquireVsCodeApi();var n=(vs.getState()||{n:0}).n;'
            + 'document.getElementById("b").onclick=function(){n++;vs.setState({n:n});vs.postMessage({type:"ping",n:n})};'
            + 'window.addEventListener("message",function(e){document.getElementById("out").textContent="pong "+e.data.n+" "+(document.body.className)});'
            + '</script></body></html>';
    }

    context.subscriptions.push(vscode.window.registerWebviewViewProvider('demo.web', {
        resolveWebviewView: function (view) {
            view.webview.options = {enableScripts: true};
            view.webview.html = page(view.webview, 'Side view');
            view.webview.onDidReceiveMessage(function (m) {
                out.appendLine('view got ' + JSON.stringify(m));
                view.webview.postMessage({n: m.n});
            });
        }
    }));

    context.subscriptions.push(vscode.commands.registerCommand('demo.panel', function () {
        var panel = vscode.window.createWebviewPanel('demo.panel', 'Demo Panel', vscode.ViewColumn.One, {enableScripts: true});
        panel.webview.html = page(panel.webview, 'Panel');
        panel.webview.onDidReceiveMessage(function (m) { panel.webview.postMessage({n: m.n * 10}); });
        panel.onDidDispose(function () { out.appendLine('panel disposed'); vscode.window.showInformationMessage('Panel closed'); });
    }));

    vscode.workspace.onDidSaveTextDocument(function (d) { out.appendLine('saved ' + d.fileName); });
    vscode.window.onDidChangeActiveTextEditor(function (e) { out.appendLine('active: ' + (e ? e.document.languageId : 'none')); });
    out.appendLine('demo activated, greeting=' + vscode.workspace.getConfiguration('demo').get('greeting')
        + ', promise=' + typeof Promise + ', count=' + context.globalState.get('count', 0));
    console.log('host-demo up');
    return {api: 42};
}

exports.activate = activate;
"""

HELPER_JS = "exports.greet = function (n) { return 'hello, ' + n; };\n"

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">
  <Metadata><Identity Language="en-US" Id="host-demo" Version="0.0.1" Publisher="droidcodology"/>
  <DisplayName>Extension Host Demo</DisplayName></Metadata>
  <Installation><InstallationTarget Id="Microsoft.VisualStudio.Code"/></Installation>
  <Assets><Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true"/></Assets>
</PackageManifest>
"""

with zipfile.ZipFile(os.path.join(HERE, "demo-ext.vsix"), "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("extension.vsixmanifest", MANIFEST)
    z.writestr("extension/package.json", json.dumps(PACKAGE, indent=2))
    z.writestr("extension/out/extension.js", EXTENSION_JS)
    z.writestr("extension/out/lib/helper.js", HELPER_JS)
    z.writestr("extension/media/tasks.svg", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
               '<path d="M3 5h2v2H3zm4 0h14v2H7zm-4 6h2v2H3zm4 0h14v2H7zm-4 6h2v2H3zm4 0h14v2H7z"/></svg>')
print("ok")
