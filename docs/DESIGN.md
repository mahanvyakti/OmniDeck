# OmniDeck — Technical Architecture & Implementation Details

This document provides a concise, exhaustive breakdown of OmniDeck's technical design, comparing the initial blueprint with the final production implementation across Android 8.0 (API 26) through Android 14 (API 34).

---

## 1. System Architecture Overview

OmniDeck is engineered as a zero-bloat (<20 MB PSS RAM), self-contained Android TV system hub and companion web server. It operates without external cloud dependencies, third-party binary daemons, or loopback ADB bridges.

```
                    ┌──────────────────────────────────────────────┐
                    │               Android TV Host                │
                    │                                              │
┌──────────────┐    │  ┌──────────────────┐  ┌──────────────────┐  │
│  Companion   │    │  │ OmniDeckActivity │  │ OmniDeckA11ySvc  │  │
│ Browser (Web)│    │  │ (Leanback D-Pad) │  │ (Screen/Typing)  │  │
└──────┬───────┘    │  └────────▲─────────┘  └────────▲─────────┘  │
       │ HTTP       │           │                     │            │
       │ (Port 8080)│  ┌────────▼─────────────────────┴─────────┐  │
       └───────────►│  │            OmniDeckService             │  │
                    │  │   ┌────────────────────────────────┐   │  │
                    │  │   │   HttpServerCore (0.0.0.0)     │   │  │
                    │  │   └────────┬──────────────┬────────┘   │  │
                    │  │            │              │            │  │
                    │  │   ┌────────▼──────┐ ┌─────▼────────┐   │  │
                    │  │   │ StorageGuard  │ │ SystemDoctor │   │  │
                    │  │   └───────────────┘ └──────────────┘   │  │
                    │  └────────────────────────────────────────┘  │
                    └──────────────────────────────────────────────┘
```

### Core Components

1. **`OmniDeckActivity`**: Responsive Leanback UI optimized for 960dp × 540dp base density. Supports D-pad spatial navigation, OLED dark mode theme, live telemetry cards, dynamic donut charts, and modal dialogs.
2. **`OmniDeckService`**: Android Foreground Service hosting the embedded HTTP daemon, telemetry sampling scheduler, and client notification dispatcher.
3. **`HttpServerCore`**: Pure Java HTTP/1.1 server (zero third-party dependencies) with a bounded thread pool (4 worker threads, queue limit of 8), streaming request handling, and semaphore-governed transfer locks.
4. **`OmniDeckAccessibilityService`**: System-level accessibility service providing cross-app full-screen capture and remote keystroke injection into TV input fields.
5. **`SystemDoctorAndroid`**: Diagnostics engine reading `/proc/meminfo`, `StorageStatsManager`, `MediaStore`, and network socket interfaces.
6. **`StorageGuard`**: Real-time disk capacity limiter preventing flash storage exhaustion.

---

## 2. Technical Blueprint vs. Actual Implementation

| Domain | Blueprint (Initial Plan) | Final Implementation | Reason for Evolution |
|---|---|---|---|
| **Packaging & Isolation** | Sub-module in launcher repo | Fully decoupled standalone package (`com.mahanapps.omnideck`) | Total isolation from launcher; zero dependency on vendor firmware. |
| **Screenshot Capture** | Basic `PixelCopy` within Activity window | Hybrid: `AccessibilityService.takeScreenshot()` (API 30+) + UI thread `PixelCopy` fallback (API 26-29) | Enables full screenshot capture across *any* running app (e.g., YouTube, Netflix, Settings). |
| **Device Identification** | Standard `User-Agent` string parsing | Client Hints API (`getHighEntropyValues`) + regex model database | Modern Android Chrome freezes User-Agent to `(Linux; Android 10; K)`. Client Hints resolve exact model names (e.g., Samsung Galaxy S25 Ultra). |
| **Storage Capacity** | Hardcoded static 500 MB floor | Dynamic adaptive floor reserve (`Math.max(100MB, avail / 2)`) if free space < 500 MB | Extremely storage-constrained TVs (e.g., <400 MB free) would otherwise brick file transfers entirely with HTTP 507. |
| **Storage Categorization** | Basic filesystem `StatFs` | `StorageStatsManager` + `MediaStore` queries | Accurately segments Apps & Data, Media (Photos/Videos/Audio), Downloads, and System OS partitions. |
| **Remote Actions** | Companion actions executed silently | Bi-directional reflection with animated card highlights & toasts on TV | Visual feedback on the TV when a companion device triggers RAM purge, speed test, or clipboard sync. |
| **Device Administration** | Remote "Disconnect All" permitted | Strictly TV-Only; remote endpoint returns `403 Forbidden` | Security: remote clients cannot maliciously disconnect other paired companions. |

---

## 3. Subsystem Deep Dive

### 3.1. Physical-Presence Security & Authentication
- **PIN Verification**: Generates a 6-digit rolling PIN displayed exclusively on the TV screen. 
- **Brute-Force Lockout**: 5 failed PIN attempts per IP triggers a 60-second cooldown and forces an immediate PIN rotation; 20 cumulative attempts closes pairing.
- **Session Tokens**: Successful pairing issues a high-entropy 128-bit hex Bearer token. Tokens are stored in-memory as SHA-256 hashes and mapped to client metadata (Device Model, OS, Client IP, Connection Timestamp).
- **Single-Use Download Tickets**: File downloads require a short-lived (60s), single-use ticket issued via authenticated `POST /api/vault/ticket` before downloading over `GET /api/vault/download`.

### 3.2. Cross-Version Android Compatibility Matrix

Tested and validated on:
- **OnePlus 32 Y1S** (Android 11 / API 30, MediaTek mt5867, 1 GB RAM, 720p 213dpi).
- **Xiaomi Mi TV 4X 50** (Android 9 / API 28, Amlogic txlx, 2 GB RAM, 4K canvas 320dpi).

```
                      API 28 (Android 9)               API 30+ (Android 11+)
                      ──────────────────               ─────────────────────
Screen Capture:       PixelCopy / DecorView            AccessibilityService.takeScreenshot()
Storage Access:       Legacy Storage (MANAGE_EXTERNAL) Scoped Storage + FileProvider URIs
Package Visibility:   Standard PackageManager          <queries> block + QUERY_ALL_PACKAGES
Foreground Service:   Standard startForeground         startForeground (type=dataSync)
```

### 3.3. File Vault & Path Traversal Prevention
- **Path Jail (`PathJail.java`)**: All file access is clamped to designated roots (`/sdcard/Download/OmniDeck` or standard media folders). Paths containing `..`, null bytes (`\0`), encoded separators (`%2f`), or symbolic links outside the root are rejected with HTTP 400/403.
- **Atomic Streaming (`UploadWriter.java`)**: Uploads are written to `.part_<timestamp>_<name>.part` in 32 KB chunks with streaming SHA-256 hash calculation. Files are synced to disk (`fd.sync()`) and atomically renamed upon completion. Orphan part files are automatically swept on startup.

### 3.4. System Doctor & Low Memory Killer (LMK) Interaction
- **Protected Process Introspection**: Automatically excludes packages flagged with `FLAG_SYSTEM`, active IMEs (Gboard, Katniss), default TV launcher, TV input services, and accessibility services.
- **Background Purge**: Uses `ActivityManager.killBackgroundProcesses(pkg)` on non-system third-party apps.
- **Zero Processes Killed Behavior**: When switching from memory-heavy apps (e.g., YouTube), the Linux kernel's Low Memory Killer daemon proactively terminates cached processes to free memory. In such cases, OmniDeck accurately reports 0 processes killed, which is normal kernel behavior.

### 3.5. Bi-directional Clipboard Sync
- Companion devices post UTF-8 text (up to 4 KB) to `/api/notes`.
- Automatically injected into Android's native `ClipboardManager` on the TV UI thread.
- Confined vertical history on TV allows selecting and copying past snippets or opening web links in TV browser/YouTube.
