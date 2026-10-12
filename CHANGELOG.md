# Changelog

## 1.0 (2026-10-09)

First stable release, the same as 0.7.0-rc10. The release candidates below list everything since 0.6.

## 0.7.0-rc10: release candidate (2026-10-09)

- **Node.js comes with Alpine Linux**, and packages are downloaded by the app: on some devices (seen on 32-bit ARM)
  apk inside Alpine never gets its downloads going. The app now fetches the package list and the packages itself,
  the same way it downloads Alpine, into a local repository in Alpine, and apk installs from there without the
  network, still checking Alpine's signatures. Used for Node.js (with Alpine, and when extensions switch to Node.js),
  "Install Developer Tools", the Claude Code libraries and the new palette command **Alpine Linux: Install
  Packages...**.
- Alpine's `/dev/random` is `/dev/urandom` (OpenSSL waited forever on old kernels before its first HTTPS
  connection), `/dev/shm` exists, and the /proc files Android hides get stand-ins, as in proot-distro.
- On 32-bit Android, extensions whose programs exist only for 64-bit processors (Claude Code among them) say so
  instead of the general desktop note.
- About: links to Alpine Linux and Horus.

## 0.7.0-rc9: release candidate (2026-10-09)

- **Fixed: apk hanging at "fetch https://dl-cdn.alpinelinux.org/..."** while the app itself downloads from the same
  host. Programs in Alpine resolved names and picked addresses on their own (public DNS servers, musl), which on some
  networks leads nowhere. Now Alpine goes out through the app: a local proxy (127.0.0.1, random password) that lets
  Android resolve and connect, with HTTPS tunnelled end to end (`https_proxy` and friends, `NODE_USE_ENV_PROXY=1`).
  apk, git, curl, npm, Node.js and Claude Code use it. `/etc/resolv.conf` now lists Android's own DNS servers, and
  apk gives up on a download with no progress for a minute (`timeout 60`) instead of hanging.
- **Fixed: Claude Code and other desktop extensions not installing in alpine mode.** Their Alpine Linux build was
  only chosen once Node.js was already there. Now alpine mode is enough: the Alpine build is installed, and the app
  switches extensions to Node.js on its own, installing Node.js first if needed and restarting the extension host
  when it's ready.

## 0.7.0-rc8: release candidate (2026-10-09)

- **Fixed: "apk: Unable to lock database: Resource temporarily unavailable".** It appeared when apk ran while
  another apk was still busy, for example the Node.js install the app starts in its own terminal. apk now waits for
  its turn ("Waiting for repository lock", `wait 600` in `/etc/apk/config`, added to existing installs too).
- **No leftover Linux processes.** Closing a terminal whose program ignores the hang-up now ends everything PRoot
  runs, not only PRoot itself, and so does stopping the Node.js extension host. Processes left from an earlier run
  of the app (possible on old Android versions) are ended when Alpine starts.
- **App mode switch: standard / alpine**, at the top of the Manage menu (bottom left) and the app menu (⋯, top
  right). Two joined buttons; the current mode is in the accent color. Standard is the default, even with Alpine
  downloaded: terminals run Android's shell and extensions the built-in engine. Alpine: terminals, and extensions if
  "Run Extensions on Node.js" is on, run in Alpine Linux. Without Alpine Linux, alpine opens Settings at its
  Download button and switches once it's ready. Commands that need Alpine (Install Developer Tools...) open an
  Alpine terminal in either mode.

## 0.7.0-rc7: release candidate (2026-10-09)

- **Matrix**: a fifth interface style, also offered as a color theme. Phosphor green on black in a monospace face with
  a soft glow, frames with corner brackets like the menus of the Matrix games, inverted buttons, its own editor
  colors and launcher icon. The accent picks the phosphor (green, lime, cyan, amber...).
- **Falling code** (Settings → Appearance): the film's digital rain behind the editor and panels, in any style. It
  pauses when the app isn't on screen.
- **App password** (Settings → Security): asked when the app opens and after a chosen time in the background
  (right away to 1 hour); "Lock the App Now" in the command palette. Only a salted PBKDF2-SHA256 hash is stored,
  never synced or backed up; five wrong tries wait 30 seconds.
- **Alpine Linux in the terminal** (Settings → Terminal): new terminals run in Alpine Linux 3.24 through PRoot, no
  root needed. It's downloaded on first use (about 4 MB, checked against its SHA-256). Android storage and projects
  have the same paths inside. Palette: "Alpine Linux: Install Developer Tools", "Prepare for Claude Code" (the
  libraries Anthropic's CLI needs on Alpine), "Remove Alpine Linux". PRoot is built by
  `tools/native/build-proot.sh` for all four ABIs and ships in the APK; `android-compat.c` supplies the libc
  functions old Android lacks, and a patch turns `fork`/`vfork` into `clone` so Alpine's tools can start processes
  (seccomp is off under PRoot on x86).
- **Extensions on Node.js** (Settings → Terminal → Run Extensions on Node.js): the extension host runs in Node.js
  inside Alpine instead of the built-in browser engine, so extensions for desktop VS Code work, including ones with
  Linux programs (the Alpine build from Open VSX is picked automatically and its programs are kept). Node.js is
  installed with `apk` when the switch is turned on. Tested with Anthropic's Claude Code extension: it activates and
  shows its own sign-in.
- More of the VS Code API: diff/webview/notebook tab inputs, `NotebookCellOutputItem`, comment controller stubs,
  `window.terminals`.

## 0.7.0-rc6: release candidate (2026-10-06)

- **Holo and Material Design 1 have a vertical activity bar** too, in each style's look: icons with ALL CAPS
  labels and an accent edge (Holo), icons with labels in the app bar color (Material 1). Long labels wrap, and the
  bar scrolls when the window is short (landscape, keyboard up).
- **Movable borders.** Double-tap the border between the side bar and the editor, the editor and the terminal, or two
  split editors, and keep the finger down to drag it. A plain double-tap highlights the border for a few seconds;
  then an ordinary drag moves it. Single taps and scrolling near a border work as before. Sizes are remembered on
  each device.

## 0.7.0-rc5: release candidate (2026-10-06)

Desktop-only extensions, found while testing rc4 with the Claude Code extension.

- Some extensions are made only for desktop VS Code: Claude Code, for example, is a front end for a 250 MB desktop
  program and needs Node.js. Neither exists on Android, so their code can't run in any Android editor. Before, such an
  extension downloaded hundreds of megabytes and then failed with a misleading "needs a newer JavaScript engine".
  Now:
  - The extension's page says it is made for desktop computers before anything is downloaded, and Install asks
    first.
  - Desktop programs and Node add-ons in a package are not unpacked (Claude Code takes 11 MB instead of about 370 MB).
  - The code of desktop-only extensions isn't started. Their themes, languages and snippets still work. Extensions
    installed by earlier versions are recognized too.
  - An extension that turns out to need Node.js says so in plain words.
  - Settings Sync no longer installs desktop-only extensions on other devices by itself.
- The "needs a newer JavaScript engine" message names the WebView's Chrome version.

## 0.7.0-rc4: release candidate (2026-10-06)

Sign-in without a server, and fixes from a review of the whole code base.

### Sign-in without a server

- **GitHub** works in every build with nothing to set up: the app opens GitHub's "new token" page with the right
  scopes ticked, and the copied token is taken from the clipboard (on Android 4.x it is typed in). With an OAuth app ID
  in the build, sign-in goes by code at github.com/login/device instead. Settings sync through a private Gist, and the
  token is also used for Git pushes to github.com.
- **Email and password** through Firebase (a free project, see [ACCOUNTS.md](ACCOUNTS.md)): create an account, sign
  in, reset a forgotten password. Settings sync through the Realtime Database, each user's data behind their own ID.
- Apple sign-in still needs a server: Apple requires a secret key that can't be shipped inside an app. The Accounts
  page says so.

### Security

- Chat answers from AI models are rendered without raw HTML, and links and images only with safe addresses
  (no `javascript:` and similar).
- Git: file names and ref names from a remote are checked, so a malicious repository can't write outside the working
  folder or `.git`. Checkout and pull stop instead of overwriting untracked files. Binary files are no longer
  line-merged. The GitHub token goes only to github.com itself. Credentials typed into a remote URL
  (`https://user:token@host/...`) are saved for the host and kept out of `.git/config` and messages.
- Sign-in links (`droid-codology://auth`) are accepted only for a sign-in started in the app.
- Extension webviews: pages embedded in a webview (iframes) can no longer talk to the extension.
- Backups (`allowBackup`) leave out the account and AI key storage.
- Server: language servers are for the admin by default (`DC_LSP`), with process limits and no secrets in their
  environment. OAuth sign-in is bound to the browser that started it and can't take over a password account. Redirect
  addresses, request sizes and field types are checked; `X-Forwarded-For` is trusted only from `DC_TRUSTED_PROXY`.
  Changing the password signs out other sessions. Settings uploads can't overwrite each other. AI requests are capped
  (`DC_AI_MAX_TOKENS`).

### Fixes

- Saving: binary and read-only files are refused with a message. Files changed on disk ask before being overwritten.
  Save As asks before replacing a file. Text that the file's encoding can't hold offers to save as UTF-8 instead of
  writing "?". Saving through a symlink keeps the link, and scripts keep their executable bit.
- Find and replace: Replace and Replace All always use the current text. Regex replacements with lookarounds and
  `$1` groups match what the search found. Search → Replace All runs in the background and writes files safely.
- Editor: no crash when deleting the last lines with word wrap or folding, snippet tab stops no longer grab typed
  text, the caret never lands inside an emoji, and the keyboard's delete keeps the text being composed.
- Agent: edits are checked against the file's current content before applying or undoing, keep the file's encoding
  and line endings, and "Stop" works while a step waits for approval.
- Explorer: renaming `Foo` to `foo` no longer overwrites another file; deleting a folder never follows symlinks.
- Settings sync no longer re-uploads unchanged settings, and a failed sign-in keeps the previous account.
- Extensions: formatters and completion providers that fail or hang no longer block the editor;
  `TextDocument.save()` returns `false` instead of hanging when it can't save.
- Terminal: invalid UTF-8 shows � instead of crashing; finished sessions are cleaned up.
- Reopen with Encoding asks before discarding unsaved changes. Large tabs restored in the background are kept when
  the app is closed before they load.
- Russian translation for all new texts.

## 0.7.0-rc3: release candidate (2026-10-05)

Fixes for running your own server, found while testing rc2.

- App: a server address typed without `http://` or `https://` gets `http://` for home-network addresses
  (192.168.x.x, 10.x, 172.16–31.x, localhost, `.local`). The address field is no longer pre-filled with `https://`.
  When HTTPS to such a server fails ("connection closed by server"), the app offers to switch to `http://` with one
  tap.
- Server: `start-server.bat` (Windows) and `start-server.sh` (Linux, macOS, Termux). Running the `.py` with `sh` now
  hands it to Python. Clear messages for an old Python, a busy port, and HTTPS requests on the plain-HTTP port, instead
  of tracebacks. At startup it shows the addresses to enter in the app, and it writes a log to `data/server.log`. On
  Windows the window stays open after an error.
- Server: fixed an error logged for requests that can't be parsed.

## 0.7.0-rc2: release candidate (2026-10-05)

Second release candidate of 0.7.0. It reworks rc1 after testing.

### New since rc1

- **Sign-in options are always visible.** Accounts lists Google, Apple, GitHub, email and API key even without a
  server. Options that need a server say so and ask for its address. A build can include a default server
  (`accounts.properties`, see [ACCOUNTS.md](ACCOUNTS.md)).
- **Sign in with Google without a server.** Sign-in runs in the browser (loopback redirect + PKCE), and settings sync
  to a hidden app folder in the user's Google Drive. Pulling, pushing and conflicts work as with the server.
- **Sign in by code on another device** for Android 4.x, whose browsers can't open today's sign-in pages. For Google
  without a server, the code is entered at google.com/device. Through the server, a new `/link` page offers its OAuth
  providers and email. On any Android version, "Sign in on Another Device" is also offered when the server supports it.
- **Connect Claude, Gemini or OpenAI through the browser.** The provider's key page opens, the copied key is taken
  from the clipboard, checked, and the provider is added with the models the key can use.
  **Sign in with OpenRouter** gets a key through OpenRouter's OAuth.
- **Extension webviews.** `createWebviewPanel` opens a full-screen page, and `registerWebviewViewProvider` views show in
  the side bar. Supported: `postMessage` both ways, `acquireVsCodeApi()` with `getState`/`setState`, `asWebviewUri`,
  `cspSource`, VS Code theme CSS variables and `vscode-light`/`vscode-dark` body classes, and `onDidDispose` when the
  user closes a panel. On Android 4.x WebViews, theme variables are substituted into pages and CSS files, and nonce
  CSPs are dropped, because those WebViews don't support either.
- Server: device sign-in (`/v1/auth/device/start`, `/v1/auth/device/poll`, `/link`); `/v1/info` reports
  `deviceSignIn`.
- Fixes: Settings and Accounts pages stay open when the window is rebuilt (for example after sync applies settings);
  more Russian translations (extension pages, marketplace).

## 0.7.0-rc1: release candidate (2026-10-05)

First release candidate of 0.7.0. Runs on Android 4.2 (API 17) through Android 15.

### New since 0.6

- **Agent mode in Chat.** The chat has an Ask/Agent switch (Agent by default). In Agent mode the model
  works on the open project: it lists, reads and searches files, edits/creates/deletes them, runs shell
  commands, checks problems and looks at git status and diffs. Every edit and command shows up as a step
  card with **Apply / Reject**. You can choose "Always allow edits" or "Always allow commands" for the
  session. Applied edits can be **reverted** from their card, and the agent stops after 40 steps.
  "Reset agent permissions" is in the mode menu.
- **Customizable activity bar.** Settings → Activity Bar, the Manage menu, or the
  "View: Customize Activity Bar" command: reorder, remove and re-add the built-in views.
- **Extension views in the side bar.** Installed extensions that contribute
  `viewsContainers.activitybar` + `views` can be added to the activity bar by hand and moved like the
  built-in views. Tree views are filled by the extension's `TreeDataProvider`
  (`registerTreeDataProvider` / `createTreeView`), with lazy expansion, item commands, descriptions,
  tooltips (long press) and `onDidChangeTreeData` refresh. SVG container icons are supported. Webview
  views show a placeholder for now.
- Russian translations for all of the above.

### Already in this line (0.4–0.6)

- Accounts: GitHub, Google, Apple, e-mail and API-key sign-in to a self-hostable droid_codology server
  (`server/`, Python standard library only), settings sync.
- AI chat with OpenAI-, Anthropic- and Gemini-compatible providers (direct or through the server),
  inline AI edit (Ctrl+I), explain / find bugs / write tests.
- Git without external tools: clone, fetch, pull, push (smart HTTP), commit, branches, history, diff.
- Terminal (native pty, xterm emulation), run selection / active file.
- Language servers through the server's LSP bridge: diagnostics, completion, hover, go to definition,
  references, rename, formatting.
- Split editor, extensions (.vsix: declarative contributions + JS extension host), 4 UI styles with
  accent colors, own TLS 1.2 stack for Android < 5.

### Known limitations

- Webview-based extension views and panels are not rendered yet (done in rc2).
- Unknown codicons in extension trees are left blank (the space is kept so rows stay aligned).
- The agent's `run_command` uses the device shell (`sh`); available commands depend on the device.
