# TapBack

**Step-by-step help on your own Android phone, in the apps you already use.**

You tell TapBack what you want to do — *"make the text bigger"*, *"send a photo
to my daughter on WhatsApp"* — and Pebble, TapBack's small guide, shows you one
step at a time: an outline around the button to press next and a card that says
what to do. You make every tap yourself. When the screen changes, TapBack looks
again and shows the next step.

Whenever my grandfather visits from India, he brings questions about his phone
that he has saved until someone can help. Tutorials often look different from
his screen and require him to switch between instructions and the app. I built
TapBack to bring that guidance directly onto his phone, and to help him
discover tools and features he might never have known to ask about.

Built for **Shipaton Next Gen**.

---

## What it does

| | |
|---|---|
| **Ask** | Type what you want to do in your own words. TapBack works out the next step from what is actually on your screen and points at it, inside whichever app you are using. |
| **Learn** | 31 short courses (118 lessons) on everyday apps — Google Maps, WhatsApp, YouTube, Gmail, Chrome, the Camera, ChatGPT, Canva and more — each guided the same way, with progress saved as you go. |
| **Pebble** | The guide on every card: it bobs beside the instructions, perches on the button to press, and waits calmly while TapBack works. It respects Android's *Remove animations* setting. |
| **TapBack Plus** | $0.99/week: 10 guided Ask tasks each week and every course. New installs get 2 free Ask tasks and the Google Maps course. |

### How cross-app guidance works — and what it doesn't do

TapBack is an ordinary Android app with an
[accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service)
that you turn on yourself. While a guide is running, the service reads the
controls on the current screen and draws an outline and an instruction card on
top of it.

- **It never taps, types or scrolls for you.** The service does not request
  Android's gesture capability (`canPerformGestures`); every action is yours.
- **It doesn't replace or control Android.** Your apps work exactly as they
  always do; TapBack only points.
- **It reads the screen only while a guide is running.** For Ask and most
  lessons, the list of controls — and sometimes one picture of the screen — is
  sent to TapBack's service, which asks Google Gemini for the single next step.
  The service doesn't keep them, and pictures are never saved on the phone.
- **It stops looking on private screens.** Password, code and payment screens,
  and windows an app marks as secure, are not read or sent.
- **It can be wrong.** If a step doesn't match the screen, *That didn't work*
  asks again with what failed; *Pause* and *End guide* are on every card.

## Judge walkthrough

About ten minutes, after [setup](#setup). Use an emulator or a test phone.

1. **Ask.** Open TapBack, finish the short onboarding, and turn on TapBack in
   Android's Accessibility settings when asked. On **Ask**, type
   `make the text bigger` and tap **Show me how**. Follow Pebble's outline and
   card through Android Settings. (A new install has 2 free Ask tasks.)
2. **Free lesson.** Open **Learn → Google Maps: Find your way** and start the
   first lesson. Free courses never use an Ask task.
3. **Buy Plus in the Test Store.** Open a locked course (for example
   **YouTube**, marked *TapBack Plus*) or **Settings → See TapBack Plus**. The
   paywall shows **$0.99 / week** and *Test Store · no charge*. Tap
   **Get TapBack Plus**, then **Test valid purchase** in RevenueCat's dialog.
   TapBack waits until its service has confirmed the purchase with RevenueCat,
   then says *You're on TapBack Plus*.
4. **Open the locked lesson.** Back in **Learn**, the *TapBack Plus* badges are
   gone. Open **YouTube** and start a lesson.

RevenueCat's Test Store renews a weekly subscription every few minutes and ends
it after a few renewals, so Plus on a test install lapses on its own shortly
after step 3.

<!--
## Screenshots

Add PNGs to docs/images/ (see docs/images/README.md), then uncomment and adjust:

| Ask | A guided step | Learn | Paywall |
|---|---|---|---|
| ![Ask](docs/images/ask.png) | ![Guided step](docs/images/guide-step.png) | ![Learn](docs/images/learn.png) | ![Paywall](docs/images/paywall.png) |
-->

## Setup

### Prerequisites

- **Android Studio** (recent stable) with the Android SDK and its bundled JDK
  (JDK 25). The app compiles against API 37 and runs on Android 8.0 (API 26)
  and later. Use the included Gradle wrapper (Gradle 9.6).
- **Python 3** for the backend (developed and tested on Python 3.13).
- A **Google Gemini API key** from <https://aistudio.google.com/apikey>.
- For purchases: a **RevenueCat** project with a Test Store (see
  [RevenueCat](#revenuecat)).

### 1. Backend

```bash
cd backend
python3 -m venv .venv
./.venv/bin/pip install -r requirements-dev.txt
cp .env.example .env
```

Fill in `backend/.env` (placeholders only in the example):

| Variable | What it is |
|---|---|
| `GEMINI_API_KEY` | Your Gemini key. It stays on the server and never reaches the app. |
| `DOT_CLIENT_TOKEN` | Any long random string: `python3 -c "import secrets; print(secrets.token_urlsafe(32))"`. The debug app sends the same value. |
| `REVENUECAT_SECRET_KEY` | RevenueCat secret API key (`sk_…`), for verifying purchases. Optional; without it nobody has Plus. |
| `REVENUECAT_ENTITLEMENT` | `dot_plus` |

Start it and check it:

```bash
backend/dev.sh start    # background, loopback only (127.0.0.1:8000)
backend/dev.sh status   # running? real model? can the emulator reach it?
backend/dev.sh check    # one real guided request end to end (one model call)
```

The server listens on loopback only. More detail — the stub provider, hosting
for release builds, logging — is in [backend/README.md](backend/README.md).

### 2. Android app

Open the project in Android Studio, then add to the root `local.properties`
(gitignored; see [`local.properties.example`](local.properties.example)):

```properties
dot.backend.url=http://10.0.2.2:8000
dot.backend.token=<the same value as DOT_CLIENT_TOKEN>
revenuecat.test.key=test_<your RevenueCat Test Store public SDK key>
```

Build and install the debug app:

```bash
./gradlew :app:installDebug
```

**Emulator networking.** `10.0.2.2` is the emulator's address for your
computer's loopback, so the emulator reaches the backend without it listening
on your network. On a physical phone, use `adb reverse tcp:8000 tcp:8000` and
`dot.backend.url=http://127.0.0.1:8000`. On Android 17 (API 37), the debug
build asks for permission to reach a local-network address the first time it
opens; allow it, or requests to the backend time out.

**Accessibility permission.** TapBack explains what the service does before
asking. Then turn it on in **Settings → Accessibility → TapBack** (on some
devices under *Downloaded apps* or *Installed apps*). Without it, Ask and Learn
can't point at anything outside TapBack.

### Release builds

A release build carries no shared token and no Test Store key. It needs an
HTTPS backend (`tapback.release.backend.url`, run with `DOT_INSTALL_AUTH=1`) and
a Google Play RevenueCat key (`revenuecat.google.key`, `goog_…`). See
[backend/README.md](backend/README.md#hosting-it-for-release-builds).

## Architecture

```
Android app (Kotlin, Jetpack Compose)          TapBack service (Python, FastAPI)        External
──────────────────────────────────────         ─────────────────────────────────        ────────
Ask / Learn / Settings / Paywall      ─HTTPS─▶  /v1/identity   per-install id + key
Accessibility service                           /v1/allowance  what you may start  ──▶  RevenueCat REST API
  reads the screen, draws outline + card        /v1/tasks      admits a task once        (verifies dot_plus)
  never performs gestures                       /v1/next-step  one next step      ──▶  Google Gemini
RevenueCat Android SDK (buying only)            SQLite ledger  tasks and credits
```

- **The phone sees, the service decides.** The app builds a structured,
  filtered description of the screen; the service asks Gemini for one next
  step and returns it as structured JSON; the app checks that the step names a
  control that is really on screen before drawing it.
- **Task ledger.** Every Ask task or lesson is admitted once by the service and
  charged once, even if retried. A task TapBack never actually helped with
  gives its credit back. Request limits per task, per day and per minute guard
  against loops.
- **Plus is decided by the service.** The phone never tells the service it has
  Plus; the service asks RevenueCat.
- **Courses** are defined in Kotlin (`app/src/main/java/com/aaravsekhri/dot/courses/`),
  each lesson with an objective, a completion condition and sources.

| Path | Contents |
|---|---|
| `app/src/main/java/com/aaravsekhri/dot/guide/` | Accessibility service, overlay cards, AI session loop, screen observation |
| `app/src/main/java/com/aaravsekhri/dot/courses/` | The course catalogue and progress |
| `app/src/main/java/com/aaravsekhri/dot/account/` | Identity, allowance, RevenueCat |
| `app/src/main/java/com/aaravsekhri/dot/ui/` | Compose screens, Pebble, design system |
| `backend/app/` | FastAPI service: planning, ledger, billing verification |

## RevenueCat

TapBack Plus is sold through RevenueCat and verified on TapBack's own service.

| | |
|---|---|
| Android SDK | `com.revenuecat.purchases:purchases` 10.23.4 |
| Entitlement | `dot_plus` |
| Product | `dot_plus_weekly` — a 1-week subscription, **USD $0.99/week** |
| Offering | `default` (current), package `$rc_weekly` containing `dot_plus_weekly` |
| App user ID | The install's TapBack identity, so the purchase and the service's check refer to the same person |

**How a purchase is confirmed.**

1. The app loads the `default` offering and offers only `dot_plus_weekly`; any
   other package is treated as a set-up error, not sold.
2. RevenueCat's SDK runs the purchase. If RevenueCat can't be logged in as this
   install's identity, nothing is bought.
3. The app then asks TapBack's service to re-check. The service calls
   RevenueCat's REST API with its secret key and reads the `dot_plus`
   entitlement. Plus counts only when that fresh, verified answer shows an
   active, non-promotional `dot_plus_weekly` purchase. Re-checks are spaced
   past the service's refresh limit (8 seconds), so each one really asks
   RevenueCat again.
4. Course access and Plus tasks come from the service's answer, never from the
   phone.

**Sandbox vs. production.** What has been verified is a **RevenueCat Test Store**
(sandbox) purchase in a debug build: no money is charged, and the paywall says
so. Production billing needs a Google Play product, a `goog_` key and a hosted
backend, and **has not been set up or tested**.

**Setting up the Test Store.** In the RevenueCat dashboard: create the Test
Store and copy its public `test_` key; create product `dot_plus_weekly`
(1 week, $0.99); create entitlement `dot_plus` with that product; in offering
`default` add a Weekly package with it; create a secret `sk_` key. Put the
`test_` key in `local.properties` (`revenuecat.test.key`) and the secret key in
`backend/.env`, then rebuild the app and restart the backend.

## Testing

**Automated** (run on 2026-09-30 against this exported code):

- Android unit tests: **723 passed**, 0 failed (`./gradlew :app:testDebugUnitTest`).
- Backend tests: **117 passed** (`cd backend && ./.venv/bin/python -m pytest`).

These cover the guidance loop's timing (late answers, retries, waits), card
placement, screen observation and privacy filtering, the course catalogue, the
task ledger, entitlement parsing, and the purchase-confirmation rule. They use
fakes; they do not prove a live purchase.

**Manual, on an Android emulator** (2026-09-30, debug build, local backend):

- A RevenueCat Test Store purchase of `dot_plus_weekly` at $0.99/week completed.
  RevenueCat reported the `dot_plus` entitlement for the same TapBack user
  (store `test_store`, sandbox); TapBack's service reported it as verified;
  a previously locked course (YouTube) was then admitted as a Plus lesson and
  guided.
- Ask tasks were started and received real Gemini-planned steps; *End guide*
  stopped a running guide; a failed start showed the error and *Try again*.

**Not yet verified:** Restore purchases on a device; Test Store renewal and
expiry; production Google Play billing; a hosted (release) backend; physical
phones beyond the emulator; non-English languages.

## Limitations

- **Android only**, English only.
- **Needs TapBack's backend** for Ask and most lessons. The service in this
  repository runs locally for development; a public release would need it
  hosted (see [backend/README.md](backend/README.md)).
- **Guidance can be wrong.** Gemini decides each next step from the screen; the
  app checks the target exists, but the instruction can still miss. The person
  stays in control of every action.
- **Some screens can't be guided.** Apps that mark windows secure, and screens
  drawn without accessibility information (some games and custom views), give
  TapBack little or nothing to point at.
- **Lessons depend on other apps.** Courses for third-party apps assume the app
  is installed and, for some, an account (TapBack checks and says so). App
  updates can change their screens.
- **Not yet production-ready.** No Google Play billing, no hosted service, and
  the in-app Terms of Service and Privacy Policy still need the publisher's
  legal details (listed in [backend/README.md](backend/README.md#terms-privacy-and-what-the-owner-still-has-to-supply)).
- **Accessibility services are powerful.** TapBack asks for one, explains it
  first, and uses it only while a guide runs — but it is still a permission
  people should grant knowingly.

## License

[MIT](LICENSE) © 2026 Aarav Sekhri.

Third-party: the **Schibsted Grotesk** typeface is used under the
[SIL Open Font License 1.1](third_party/SchibstedGrotesk-OFL.txt); see
[NOTICE](NOTICE). Libraries pulled in by Gradle and pip (AndroidX, Jetpack
Compose, RevenueCat, FastAPI, Google Gen AI SDK and others) keep their own
licenses.
