# OmniDeck — Agent & Developer Guide (`AGENTS.md`)

This guide is designed for **AI coding assistants** (Antigravity, Claude Code, Cursor, Copilot) and **human developers** working on OmniDeck. It provides project context, architecture principles, cross-platform workflows, and a step-by-step decision tree for non-technical users and technical developers alike.

---

## 1. Project Overview & Philosophy

**OmniDeck** (`com.mahanapps.omnideck`) is an ultra-lightweight, zero-bloat system diagnostics hub and local browser companion built specifically for **Android TV (Leanback)**.

### Core Philosophy
1. **Zero External Runtime Dependencies**: The embedded HTTP server, JSON serializers, multipart form parsers, and WebSocket-style long pollers are built with native Java (`com.sun.net.httpserver` / standard Java sockets). No OkHttp, Retrofit, or heavy web frameworks that bloat heap memory.
2. **Strict Memory Budget (< 20 MB PSS)**: Android TVs typically have only 1 GB to 2 GB of total system RAM shared between the OS, launcher, audio/video decoders, and background apps. OmniDeck runs in the background with negligible memory overhead and zero UI thread stalls.
3. **Physical-Presence Security**: Features a rolling 6-digit PIN displayed on the TV screen. Any client connecting from a phone, tablet, or PC browser must enter the PIN to obtain a cryptographically secure 128-bit Bearer token.
4. **Android TV Resilience**: Supports Android 9.0 (API 28) up to Android 14+ (API 34). Handles TV deep sleep, Daydream screensavers, Low Memory Killer (LMK) process trimming, and dynamic storage safety floors.

---

## 2. Directory & Architecture Map

```
OmniDeck/
├── app/
│   ├── build.gradle                   # AGP 8.5.2, Java 17, compileSdk 34, minSdk 26
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    # Permissions, Leanback launcher activity, foreground service
│       │   ├── assets/web/            # Zero-dependency web companion SPA (HTML5/CSS3/Vanilla JS)
│       │   │   ├── index.html         # Responsive companion interface (phone & desktop)
│       │   │   └── speedtest-worker.js# WebWorker for client-to-TV throughput testing
│       │   ├── java/com/mahanapps/omnideck/
│       │   │   ├── android/
│       │   │   │   ├── OmniDeckActivity.java            # Leanback TV Dashboard UI (charts, PIN, gauges)
│       │   │   │   ├── OmniDeckService.java             # Foreground service hosting embedded HTTP server
│       │   │   │   ├── OmniDeckAccessibilityService.java# Screen capture & global action bridge
│       │   │   │   └── BootReceiver.java                # Auto-start on TV boot (optional)
│       │   │   ├── core/
│       │   │   │   ├── DiagnosticsEngine.java           # RAM, CPU, storage, bandwidth & process telemetry
│       │   │   │   ├── StorageManager.java              # Storage breakdown & dynamic safety floor guard
│       │   │   │   ├── PairingManager.java              # PIN generator, token auth, session deduplication
│       │   │   │   ├── DeviceResolver.java              # Client Hints & User-Agent hardware decoder
│       │   │   │   ├── SpeedTestManager.java            # Network speed test handler & history
│       │   │   │   ├── NetworkBandwidthTracker.java     # Sampling network throughput
│       │   │   │   └── ClientSession.java               # Active device session entity
│       │   │   └── server/
│       │   │       ├── HttpServer.java                  # Embedded lightweight HTTP server on port 8080
│       │   │       └── RouteHandler.java                # REST endpoints routing & multipart uploads
│       │   └── res/                                     # Leanback TV layouts, drawables, colors, styles
│       └── test/java/com/mahanapps/omnideck/core/       # JVM unit tests (PairingManager, Storage, etc.)
├── docs/
│   ├── DESIGN.md                      # Exhaustive technical design & Android API architecture
│   └── screenshots/                   # Verified TV and browser companion screenshots
├── tools/
│   ├── run_gradle.bat                 # Windows Java 17 autodiscovery Gradle runner
│   ├── run_gradle.sh                  # Linux/macOS Java 17 autodiscovery Gradle runner
│   ├── tv.py                          # Standalone Python ADB shell client (no adb binary needed)
│   ├── b64push.py                     # Resilient chunked base64 file transfer over ADB shell
│   └── test_omnideck_e2e.py           # 30-step end-to-end automated verification test suite
├── Makefile                           # Unified developer automation recipes
└── README.md                          # Human-facing installation & troubleshooting manual
```

---

## 3. Workflow Selector (Choose Your Goal)

When an AI agent or human starts working with this project, choose the workflow matching your immediate objective:

```
┌──────────────────────────────────────────────────────────┐
│              What do you want to do?                     │
└──────────────────────────┬───────────────────────────────┘
                           │
       ┌───────────────────┼───────────────────┐
       ▼                   ▼                   ▼
 [ Option 1 ]        [ Option 2 ]        [ Option 3 ]
  Setup Host         Find TV & Enable    Run Unit Tests
  Environment        ADB Debugging       & Build APK
       │                   │                   │
       ▼                   ▼                   ▼
 [ Option 4 ]        [ Option 5 ]        [ Option 6 ]
  Deploy to TV       End-to-End Test     Modify Code /
  & Verify           on Live Device      Add Features
```

---

### Option 1: Setup Host Environment (Cross-Platform)

Before building, ensure you have **Java 17** and the **Android SDK**:

#### On macOS
```bash
# 1. Install Java 17 & build tools via Homebrew
brew install openjdk@17 make python

# 2. Configure Android SDK path
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

#### On Ubuntu / Debian Linux
```bash
# 1. Install Java 17, Make and Python 3
sudo apt update && sudo apt install -y openjdk-17-jdk build-essential python3 python3-pip

# 2. Configure Android SDK path
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

#### On Windows (PowerShell)
```powershell
# 1. Install Java 17 via Winget or Chocolatey
winget install Microsoft.OpenJDK.17

# 2. Configure Android SDK path in local.properties
Set-Content -Path "local.properties" -Value "sdk.dir=C:\\Users\\$env:USERNAME\\AppData\\Local\\Android\\Sdk"
```

---

### Option 2: Find TV IP & Enable ADB Debugging

#### Step 2.1: Enable Developer Options on the TV
1. Turn on your TV and open **Settings** (gear icon top-right).
2. Go to **Device Preferences** (or **System**) → **About**.
3. Scroll down to **Android TV OS build** (or **Build**).
4. Press the **OK / Center button 7 times** on your remote until a toast appears: *"You are now a developer!"*.
5. Press Back → Navigate to **Developer options**.
6. Turn **USB debugging** to **ON**.
7. *(If available)* Turn **Network debugging** to **ON**.

#### Step 2.2: Find Your TV's IP Address
- **Method A (Direct on TV)**: Settings → Network & Internet → Click your connected Wi-Fi / Ethernet → Note the **IP address** (e.g., `192.168.1.150` or `10.215.61.35`).
- **Method B (From Computer Terminal)**:
  - Check your local ARP table:
    ```bash
    # Linux / macOS
    arp -a | grep -i "b8:\|dc:\|a4:\|70:"
    
    # Windows
    arp -a
    ```
  - Or ping your local subnet broadcast to find active hosts.

#### Step 2.3: Authorize Connection on TV
From your computer terminal:
```bash
adb connect <TV_IP>:5555
```
> **Watch the TV screen!** A popup will appear: *"Allow USB debugging from this computer?"*.
> Tick **"Always allow from this computer"** and select **OK**.

---

### Option 3: Run Unit Tests & Build APK

OmniDeck provides automated test execution and build recipes across all platforms:

```bash
# Run JVM unit tests (tests PairingManager, deduplication, storage floors)
make test
# OR via Gradle:
# Linux/macOS: ./gradlew testDebugUnitTest
# Windows:     .\gradlew.bat testDebugUnitTest

# Build the Debug APK
make build
# Output: app/build/outputs/apk/debug/app-debug.apk
```

---

### Option 4: Deploy & Launch on TV

#### Standard ADB Deployment:
```bash
# 1. Connect
adb connect <TV_IP>:5555

# 2. Install APK
adb -s <TV_IP>:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Grant system permissions
adb -s <TV_IP>:5555 shell appops set com.mahanapps.omnideck GET_USAGE_STATS allow
adb -s <TV_IP>:5555 shell settings put secure enabled_accessibility_services com.mahanapps.omnideck/.android.OmniDeckAccessibilityService
adb -s <TV_IP>:5555 shell settings put secure accessibility_enabled 1

# 4. Launch OmniDeck
adb -s <TV_IP>:5555 shell am start -n com.mahanapps.omnideck/.android.OmniDeckActivity
```

#### One-Command Make Recipe:
```bash
# Rebuilds, deploys, grants permissions and launches:
make restart TV_HOST=<TV_IP>
```

---

### Option 5: End-to-End Verification Suite

OmniDeck includes a dedicated Python 3 test suite (`tools/test_omnideck_e2e.py`) that tests:
- PIN retrieval & authentication
- Diagnostics telemetry & storage calculation accuracy
- Clipboard sync (English + Unicode/Hindi)
- Inactivity session timeout & client deduplication
- Remote screen capture across apps
- File upload safety floor enforcement (rejecting files when disk < 100 MB)
- Clean app exit (`POST /api/exit`)

Run the suite:
```bash
python tools/test_omnideck_e2e.py --host <TV_IP>
# OR via Makefile:
make test-e2e TV_HOST=<TV_IP>
```

---

### Option 6: Guidelines for AI Agents Modifying Code

When asked to extend or refactor OmniDeck, adhere strictly to these rules:

1. **Maintain Zero External Dependencies**:
   - Do NOT add external dependencies to `app/build.gradle` (like Retrofit, Gson, Jackson, OkHttp, Guava).
   - Use standard `org.json.JSONObject` / `org.json.JSONArray` for JSON.
   - Use standard Java I/O streams and NIO buffers.

2. **Respect Android TV Hardware Constraints**:
   - Never run heavy CPU loops on the main UI thread.
   - Background telemetry must sample at moderate intervals (e.g. 2,000 ms to 3,000 ms) and pause when no clients are connected or when TV enters sleep.
   - Always honor the **500 MB / 10% storage safety floor** in `StorageManager.java` to prevent bricking TV flash storage.

3. **Multi-Brand Compatibility**:
   - Code must run reliably on API 28 (Android 9, e.g., Xiaomi Mi TV) through API 34+ (Android 14, e.g., OnePlus, Sony, TCL, Google TV).
   - Never assume modern API methods exist without version checks (e.g. `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R`).

4. **Preserve User Privacy & Local Security**:
   - Never hardcode private IP addresses, personal paths, or credentials into repository files.
   - Keep `tools/adbkey` and `tools/adbkey.pub` gitignored.
   - All browser companion operations must remain on the local area network (LAN) with zero cloud dependencies.
