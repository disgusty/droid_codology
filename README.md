# droid\_codology

A code editor for Android 4.2 and newer, modeled on desktop programs and Horus for Android.

- Editor: syntax highlighting (built-in lexers and TextMate grammars), multi-cursor editing, folding, sticky scroll, minimap, completion, snippets, Emmet, find and replace, split editor, Markdown preview.

- Styles: Original (VS Code look), Holo, Material Design 1, Material You and Matrix, each with accent colors. Optional falling-code background.

- Extensions from [Open VSX](https://open-vsx.org/): themes, grammars, snippets, language configuration, and extension code. Extension code runs in a built-in JavaScript host, or on Node.js in Alpine Linux.

- Terminal, either Android's shell or Alpine Linux through PRoot. Alpine needs no root and is downloaded on first use. Use the **standard / alpine** switch in the menus.

- Git (clone, commit, branches, merge, push over HTTPS), language servers, an AI chat with agent mode, Settings Sync, and an app password.

- Optional self-hosted server for accounts, sync and the AI proxy, in `server/`. See [ACCOUNTS.md](file:///C:/Users/bnmfatman/Documents/droid_codology-github/ACCOUNTS.md).

## Release

Release builds are signed with the same key, so new versions install over old ones.

## Building

You need JDK 17 or newer, the Android SDK (platform 36) and NDK 23.1.7779620.

```
gradlew assembleDebug
```

Release signing reads `signing/keystore.properties`, which is not in the repository. Without it, release builds are signed with the debug key. To sign in without a server, copy `accounts.example.properties` to `accounts.properties` (see [ACCOUNTS.md](file:///C:/Users/bnmfatman/Documents/droid_codology-github/ACCOUNTS.md)).

The PRoot binaries in `app/src/main/jniLibs` are built with `tools/native/build-proot.sh` in Linux or WSL with NDK r23c.

## License

[GPL-3.0](file:///C:/Users/bnmfatman/Documents/droid_codology-github/LICENSE). Third-party material is listed in [NOTICE.md](file:///C:/Users/bnmfatman/Documents/droid_codology-github/NOTICE.md).
