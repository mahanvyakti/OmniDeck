# Contributing to OmniDeck 📺⚡

Thank you for contributing to OmniDeck! We welcome issues, bug reports, and pull requests that keep OmniDeck ultra-fast, zero-bloat, and dependable on Android TV hardware.

---

## 1. Core Principles & Constraints (Must Read)

Before opening a PR or writing code, ensure your changes adhere to these 4 non-negotiable architectural rules:

1. **Zero External Runtime Dependencies**:
   - The embedded HTTP server, JSON serializers, and multipart form handlers are built with pure native Java.
   - **Do NOT add external libraries** (e.g., OkHttp, Retrofit, Gson, Jackson, Apache Commons). Keep the APK tiny and heap overhead negligible.
2. **Strict Memory Budget (< 20 MB PSS)**:
   - Target Android TVs typically have only 1 GB to 2 GB of total system RAM.
   - Avoid memory leaks, long-lived static context references, or continuous unthrottled background polling.
3. **Storage Reserve Floor Protection**:
   - All filesystem and upload operations must respect the **500 MB (or 10% disk)** reserve floor in `StorageGuard.java` to prevent bricking Android TV flash memory.
4. **Broad Android TV Compatibility (API 28 to 34+)**:
   - Your code must run smoothly on Android 9 (Xiaomi Mi TV) through Android 14+ (OnePlus, Sony, Google TV). Always check `Build.VERSION.SDK_INT` before calling newer APIs.

---

## 2. Getting Started

1. **Prerequisites**: JDK 17, Android SDK API 34.
2. **Clone & Branch**:
   ```bash
   git clone https://github.com/mahanvyakti/OmniDeck.git
   cd OmniDeck
   git checkout -b feature/your-feature-name
   ```
3. **Configure SDK Path**:
   ```bash
   # Linux / macOS:
   echo "sdk.dir=$HOME/Android/Sdk" > local.properties
   # Windows:
   echo "sdk.dir=C:\\Users\\<USER>\\AppData\\Local\\Android\\Sdk" > local.properties
   ```

---

## 3. Pre-PR Checklist

Before pushing or opening a Pull Request / Merge Request, run:

- [ ] **Run Unit Tests**:
  ```bash
  make test
  # OR: ./gradlew testDebugUnitTest
  ```
- [ ] **Check Code Coverage**:
  ```bash
  make coverage
  # Report generated at: app/build/reports/jacoco/jacocoTestReport/html/index.html
  ```
- [ ] **Verify Debug Build**:
  ```bash
  make build
  # Generates: app/build/outputs/apk/debug/app-debug.apk
  ```
- [ ] **Secrets & Keys Hygiene**:
  Ensure no personal IP addresses, `tools/adbkey` files, or local configuration files are tracked (`git status`).

---

## 4. Submitting a Pull Request

1. Keep PRs atomic and focused on a single bug fix or feature.
2. Link relevant GitHub issues in your PR description (e.g., `Closes #12`).
3. Ensure all CI checks (Unit tests, JaCoCo coverage, and Android lint) pass green.
