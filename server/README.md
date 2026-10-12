# droid_codology server

A small server you can run yourself for the droid_codology editor. It gives the app:

- **accounts**: email + password, GitHub, Google and Sign in with Apple, plus API keys for scripts and other devices;
- **settings sync** between your devices (with conflict detection);
- **AI chat**: the app talks to the server, and the server talks to OpenAI, Anthropic, Gemini or any OpenAI-compatible
  endpoint (Ollama, LM Studio, OpenRouter, DeepSeek…) with keys that stay on the server. Each user has a daily token limit.

- **language servers**: the app's diagnostics, completion, hover, go to definition, references, rename and
  formatting come from language servers that run here (pyright, clangd, typescript-language-server, gopls,
  rust-analyzer…), because phones can't run them.

It is one Python file that uses only the standard library (Python 3.9+), and it keeps its data in one SQLite file.

## Quick start

The server is a Python program, so it needs **Python 3.9 or newer**.

- **Windows:** install Python from [python.org](https://www.python.org/downloads/) and tick "Add python.exe to PATH"
  in the installer. Then double-click `start-server.bat`.
- **Linux / macOS:** in a terminal, in this folder, run `sh start-server.sh` (or `python3 droidcodology_server.py`).
- **Android (Termux):** run `pkg install python`, then `sh start-server.sh` in this folder.

The server listens on port 8080 and keeps its data in `./data`. When it prints `listening on …`, it is running. Keep
the window open. In the app, open **Accounts → Server**, enter `http://<your-ip>:8080`, and register with
**Sign in with Email → Create Account**. The first account created becomes the admin. On the same phone (Termux), the
address is `http://127.0.0.1:8080`.

To run it with Docker:

```sh
docker build -t droidcodology-server .
docker run -d --name dc -p 8080:8080 -v dc-data:/data \
  -e DC_PUBLIC_URL=https://dc.example.com \
  -e OPENAI_API_KEY=sk-... \
  droidcodology-server
```

## HTTPS

Use HTTPS on any server that is reachable from the internet. There are two ways to do it:

- **Reverse proxy (recommended).** For example, [Caddy](https://caddyserver.com) gets certificates automatically:

  ```
  dc.example.com {
      reverse_proxy 127.0.0.1:8080
  }
  ```

- **Built in.** Set `DC_TLS_CERT` and `DC_TLS_KEY` to PEM files.

The app has its own TLS 1.2 client for Android 4.x, so a current, default HTTPS setup also works on old phones.

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `DC_PORT`, `DC_HOST` | `8080`, `0.0.0.0` | Where to listen |
| `DC_PUBLIC_URL` | `http://localhost:PORT` | The address users reach the server at; OAuth callbacks are built from it |
| `DC_DATA_DIR` | `./data` | Where the database and the generated secret are kept |
| `DC_NAME` | `droid_codology server` | Name shown in the app |
| `DC_ALLOW_REGISTRATION` | `true` | Set to `false` to close sign-up; create users with `create-user` instead |
| `DC_SESSION_DAYS` | `90` | How long sign-in sessions last (API keys never expire) |
| `DC_SECRET` | generated | Key that signs OAuth state (stored in `data/secret.key` if not set) |
| `DC_GITHUB_CLIENT_ID` / `_SECRET` | – | Enables "Sign in with GitHub" |
| `DC_GOOGLE_CLIENT_ID` / `_SECRET` | – | Enables "Sign in with Google" |
| `DC_APPLE_SERVICES_ID`, `DC_APPLE_TEAM_ID`, `DC_APPLE_KEY_ID`, `DC_APPLE_KEY_FILE` | – | Enables "Sign in with Apple" (needs a paid Apple developer account; the key file is the `.p8`) |
| `DC_AI_CONFIG` | – | Path to the AI providers file (see below) |
| `OPENAI_API_KEY` (+ `OPENAI_BASE_URL`, `OPENAI_MODELS`) | – | Quick AI setup without a config file |
| `ANTHROPIC_API_KEY` (+ `ANTHROPIC_MODELS`) | – | Same, for Anthropic |
| `DC_AI_DAILY_TOKENS` | `200000` | Tokens per user per day (`0` = unlimited; admins are never limited) |
| `DC_AI_MAX_TOKENS` | `8192` | Longest answer one request may ask for |
| `DC_ADMIN_EMAIL` | – | The account with this email is the admin. If not set, the first account created becomes the admin, so register right after starting a public server |
| `DC_LSP` | `admin` | Who may start language servers: `admin`, `all` or `false`. Language servers run code from the project they open (build scripts, project settings), so give `all` only to people you trust with your machine |
| `DC_LSP_MAX` | `16` | Language servers running at once, all users together |
| `DC_TRUSTED_PROXY` | – | IPs of your reverse proxies (comma-separated). Only their `X-Forwarded-For` header is believed, for rate limits |
| `DC_MAX_SYNC_BYTES` | `2097152` | Largest settings blob a user can store |

### OAuth apps

For each OAuth app, set the callback URL to `DC_PUBLIC_URL` followed by the path below:

- GitHub (Settings → Developer settings → OAuth Apps): `/v1/auth/oauth/github/callback`
- Google (Cloud Console → Credentials → OAuth client ID, type *Web application*): `/v1/auth/oauth/google/callback`
- Apple (Certificates, IDs & Profiles → Services ID → Sign in with Apple): `/v1/auth/oauth/apple/callback`

The app opens `/v1/auth/oauth/<provider>/start` in the browser. When sign-in finishes, the server sends the browser to
`droid-codology://auth?token=…`, which opens the app again.

### Signing in from another device

Browsers on Android 4.x can't open today's GitHub, Google and Apple sign-in pages. On those devices the app shows a
code instead (and offers "Sign in on Another Device" everywhere). The user opens `DC_PUBLIC_URL/link` on a phone or
computer, enters the code, and signs in with any OAuth provider the server has, or with an email and password. The
device that showed the code then receives its session token. Codes are valid for 10 minutes and work once.

### AI providers

`DC_AI_CONFIG` points to a JSON list of providers (see `ai.example.json`). An `apiKey` written as `"$NAME"` is read from
the environment variable `NAME`. The `kind` field selects the API format:

- `openai`: OpenAI Chat Completions, which also covers Ollama, LM Studio, OpenRouter, vLLM and similar services;
- `anthropic`: the Anthropic Messages API;
- `gemini`: the Google Gemini API.

Users see models as `provider/model`, for example `anthropic/claude-sonnet-5-5`.

### Language servers

At startup the server looks for well-known language servers on `PATH`: `pyright-langserver`, `pylsp`,
`typescript-language-server`, `clangd`, `gopls`, `rust-analyzer`, `bash-language-server`, `lua-language-server`,
`vscode-json/css/html-language-server`, `yaml-language-server`, `intelephense`, `kotlin-language-server`, `jdtls`,
`dart language-server` and `solargraph`. Install the ones you need, for example:

```sh
npm install -g pyright typescript typescript-language-server bash-language-server
```

To choose them yourself, set `DC_LSP_CONFIG` to a JSON file that maps language ids to commands:

```json
{ "python": ["pylsp"], "c": ["clangd", "--background-index"], "cpp": ["clangd"] }
```

By default only the admin may use language servers (`DC_LSP=admin`), because a language server runs code from the
project it opens. `DC_LSP=all` allows every signed-in user and `DC_LSP=false` turns the feature off. Language
servers get a clean environment without the server's secrets. Each user can have up to 8 running servers. A server stops 10 minutes after
the app disconnects. The app copies the files it opens into a private folder on the server, so that cross-file features
work.

The server also runs inside [Termux](https://termux.dev) on the phone itself. Install the server with
`pkg install python`, install some language servers, and run it there. Then, in the app, set the address to
`http://127.0.0.1:8080`.

## Command line

```sh
python3 droidcodology_server.py create-user you@example.com --admin   # asks for a password
python3 droidcodology_server.py create-key you@example.com laptop     # prints a new API key
```

## API

All requests and responses are JSON. Authenticate with `Authorization: Bearer <token>`, using either a session token
(`dcs_…`) or an API key (`dck_…`).

| Method & path | Body → response |
|---|---|
| `GET /v1/info` | → `{name, version, registration, oauth: [...], deviceSignIn, ai, models}` |
| `POST /v1/auth/register` | `{email, password, name?, device?}` → `{token, user}` |
| `POST /v1/auth/device/start` | `{device?}` → `{device_code, user_code, verification_url, expires_in, interval}` |
| `POST /v1/auth/device/poll` | `{device_code}` → `{pending: true}` or `{token, user}`; 410 once the code has expired |
| `GET /link?code=…` | web page where the code is entered and the user signs in (HTML) |
| `POST /v1/auth/login` | `{email, password, device?}` → `{token, user}` |
| `POST /v1/auth/logout` | ends the current session |
| `GET/PATCH/DELETE /v1/me` | profile; PATCH `{name?, password?, currentPassword?}`; DELETE removes the account |
| `GET/POST /v1/keys`, `DELETE /v1/keys/{id}` | API keys; POST `{name}` → `{key}` (the key is shown only once) |
| `DELETE /v1/sessions` | signs out every other device |
| `GET /v1/sync` | → `{version, data, updated}` |
| `PUT /v1/sync` | `{baseVersion, data}` → `{version}`; `409` if another device saved first |
| `GET /v1/ai/models`, `GET /v1/ai/usage` | available models; today's usage and the daily limit |
| `GET /v1/lsp` | → `{languages: [...]}` |
| `POST /v1/lsp/sessions` | `{language}` → `{id, rootUri}` starts a language server |
| `POST /v1/lsp/sessions/{id}/send` | one JSON-RPC message for the server |
| `GET /v1/lsp/sessions/{id}/events` | server-sent events, one JSON-RPC message each; ends with `{"droidcodology": "exit"}` |
| `PUT /v1/lsp/sessions/{id}/files` | `{path, text}` writes a file into the session's folder |
| `DELETE /v1/lsp/sessions/{id}` | stops the server |
| `POST /v1/ai/chat` | `{model, messages: [{role, content}], system?, maxTokens?, temperature?, stream?}` → server-sent events `data: {"delta": "..."}`, ending with `data: {"done": true, "usage": n}`; with `stream: false` → `{text, usage}` |

## Tests

```sh
python3 -m unittest test_server
```

## License

GPL-3.0, the same as droid_codology.
