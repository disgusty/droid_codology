# Sign-in and AI accounts: setting up a build

droid_codology can sign in with Google, Apple, GitHub, email or an API key, and it can connect AI providers
(Claude, Gemini, OpenAI, OpenRouter). Some of these work out of the box. Others need OAuth clients that only the
person who publishes the build can register. This page lists what each method needs and how to set it up.

| In the app | Needs | Works on |
|---|---|---|
| Sign in with GitHub, no server (settings sync through a private Gist) | nothing: the user pastes a personal access token. With `githubClientId`, sign-in by code instead | all |
| Sign in with Email, no server (settings sync through Firebase) | `firebaseApiKey` + `firebaseDatabaseUrl` (a free Firebase project) | all (no browser involved) |
| Sign in with Google, no server (settings sync through Google Drive) | `googleClientId` (+ `googleDeviceClientId` for Android 4.x) | all |
| Sign in with Apple | a droid_codology server: Apple needs a secret key that can't be shipped in an app | 5.0+ in the browser, 4.x by code |
| Sign in with Email / API key through the server | a droid_codology server | all (no browser involved) |
| Sign in with GitHub / Google through the server | a server with that OAuth app | 5.0+ in the browser, 4.x by code |
| Sign in on Another Device | a server (version with `/link`) | all |
| Connect Claude / Gemini / OpenAI | nothing: the user creates a key in the provider's console | all (4.x: open the link elsewhere) |
| Sign in with OpenRouter | nothing (OpenRouter's public OAuth) | 5.0+ |

One account is signed in at a time. When a server is set and signed in to, it takes precedence over the server-less
accounts.

Everything below goes into `accounts.properties` at the root of the sources. This file is git-ignored. Copy it from
`accounts.example.properties`. The values are compiled into the APK, so none of them may be a real secret. Google
documents the "secrets" of Desktop and TV clients as not confidential.

## A server built into the app

Run `server/droidcodology_server.py` on a public HTTPS address (see [server/README.md](server/README.md)), then set

```properties
defaultServer=https://dc.example.com
```

Users see this address under **Accounts → Server** and can change it. If they clear the field, the built-in address is
used again. Email sign-in and API keys work right away. The OAuth providers below appear once the server has them.

### OAuth apps on the server

| Provider | Where | Callback URL |
|---|---|---|
| GitHub | github.com → Settings → Developer settings → OAuth Apps | `https://dc.example.com/v1/auth/oauth/github/callback` |
| Google | console.cloud.google.com → APIs & Services → Credentials → OAuth client ID → **Web application** | `https://dc.example.com/v1/auth/oauth/google/callback` |
| Apple | developer.apple.com (paid membership) → Identifiers → **Services ID** with Sign in with Apple; Keys → a key with Sign in with Apple (`.p8`) | `https://dc.example.com/v1/auth/oauth/apple/callback` |

Give the server the IDs through its environment variables: `DC_GITHUB_CLIENT_ID`/`_SECRET`,
`DC_GOOGLE_CLIENT_ID`/`_SECRET`, `DC_APPLE_SERVICES_ID`, `DC_APPLE_TEAM_ID`, `DC_APPLE_KEY_ID` and `DC_APPLE_KEY_FILE`.
Apple accepts only HTTPS callback addresses on a real domain.

## Google without a server

Users sign in with Google directly, and their settings sync to a hidden app folder in their own Google Drive
(scope `drive.appdata`: the app sees only its own file there).

1. In console.cloud.google.com, create a project and enable the **Google Drive API**.
2. Set up the **OAuth consent screen** (External), and add the scopes `openid`, `email`, `profile` and
   `.../auth/drive.appdata`. While the app is in "Testing", only the test users you list can sign in. Publish it for
   everyone. `drive.appdata` is not a restricted scope, so verification is light.
3. Under **Credentials → Create credentials → OAuth client ID**, choose **Desktop app**. The app signs in through the
   browser and a loopback address (`http://127.0.0.1:<port>`) with PKCE, which Google supports for Desktop clients.
4. For Android 4.x, whose browsers can't open Google's sign-in page any more, also create a client of type
   **TVs and Limited Input devices**. With it, the app shows a code to enter at google.com/device on another device.

```properties
googleClientId=1234-abc.apps.googleusercontent.com
googleClientSecret=GOCSPX-...
googleDeviceClientId=1234-def.apps.googleusercontent.com
googleDeviceClientSecret=GOCSPX-...
```

If a server that offers Google sign-in is set, the app signs in through the server instead.

## GitHub without a server

This works in every build with no setup. The app opens github.com's "new token" page with the `gist` and `repo` scopes
already ticked. The user presses **Generate token** and copies it. Back in the app, the token is taken from the
clipboard (or typed in on Android 4.x, whose browsers can't open github.com any more). Settings sync through a
private (secret) Gist named `droid_codology-settings.json` in the user's account, and the same token is used for Git
pushes to github.com.

To let users sign in by code instead of copying a token, register an OAuth app:

1. github.com → Settings → Developer settings → **OAuth Apps** → New OAuth App. Any homepage and callback URL will do.
2. Tick **Enable Device Flow** and save. No client secret is needed.

```properties
githubClientId=Ov23li...
```

The app then shows a code to enter at github.com/login/device, on this device or any other.

## Email without a server (Firebase)

Users create an account with an email and a password. Their settings are stored under their own user ID in a
Firebase Realtime Database. The free Spark plan is enough.

1. In console.firebase.google.com, create a project.
2. **Build → Authentication → Sign-in method**: enable **Email/Password**.
3. **Build → Realtime Database → Create database**. Under **Rules**, let each user reach only their own data:

   ```json
   {
     "rules": {
       "droid_codology": {
         "users": {
           "$uid": {
             ".read": "auth.uid === $uid",
             ".write": "auth.uid === $uid"
           }
         }
       }
     }
   }
   ```

4. **Project settings → General**: copy the **Web API key**. The database URL is shown at the top of the Realtime
   Database page.

```properties
firebaseApiKey=AIza...
firebaseDatabaseUrl=https://my-project-default-rtdb.europe-west1.firebasedatabase.app
```

The Web API key only identifies the project; the database rules above are what keeps users' data apart. "Forgot
password?" sends Firebase's reset email. Change its text and sender under Authentication → Templates.

## AI providers
- **Connect Claude / Gemini / OpenAI** opens the provider's key page (console.anthropic.com, aistudio.google.com,
  platform.openai.com). When the user copies the new key and comes back, the app takes it from the clipboard, checks
  it by listing the models, and adds the provider with those models.
- There is no "Sign in with Claude" for other apps. The Claude.ai or Claude Code subscription login belongs to
  Anthropic's own products, so an API key from the Anthropic Console is the way in. Through OpenRouter, Claude models
  are also available with an OpenRouter account.
- **Sign in with OpenRouter** uses OpenRouter's OAuth with PKCE: the browser signs in, and the app receives an API key.
  It needs no registration.

## Testing without real accounts (debug builds)

- `tools/fake_google.py 9300` stands in for Google's OAuth (with the PKCE check), its device flow and the Drive app
  folder. Start the app with
  `adb shell am start -n com.disgusty.droid_codology/.MainActivity -e googleTestBase http://10.0.2.2:9300`.
  `GET /state` shows the synced file. `POST /bump` changes it as if another device had synced.
- `tools/fake_github.py 9310` stands in for github.com: the device flow (approved at the third poll), token checks
  and Gists. Start the app with `-e githubTestBase http://10.0.2.2:9310` for sign-in by code, or with
  `-e githubTestBase "http://10.0.2.2:9310#pat"` for the token sign-in. `ghp_` followed by 36 `a` is a good token;
  with 36 `b` it lacks the `gist` scope. `GET /state` and `POST /bump` work as above.
- `tools/fake_firebase.py 9320` stands in for Firebase Authentication and the Realtime Database (with the rules
  above and ETags): `-e firebaseTestBase http://10.0.2.2:9320`. `POST /bump/<email>` changes that user's settings.
- The server's code sign-in can be tested with the server running locally: tap "Sign in on Another Device" and open
  `http://10.0.2.2:8080/link` in another emulator's browser.
