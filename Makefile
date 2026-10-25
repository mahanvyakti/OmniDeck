# ==============================================================================
# OmniDeck — Standalone Android TV Hub & Web Companion
# Development & Automation Makefile
# ==============================================================================

# Target TV Configuration (Override via CLI: make TV_HOST=192.168.1.50)
TV_HOST ?= 192.168.1.100
TV_PORT ?= 5555
HTTP_PORT ?= 8080

# OS and Gradle Wrapper Detection
ifeq ($(OS),Windows_NT)
  GRADLEW := tools\run_gradle.bat
  PYTHON := python
else
  GRADLEW := bash tools/run_gradle.sh
  PYTHON := python3
endif

APK_DEBUG := app/build/outputs/apk/debug/app-debug.apk

.PHONY: help build release test clean install push-install start stop restart logcat test-e2e permissions screenshot status

help:
	@echo "OmniDeck Build & Automation Recipes:"
	@echo "  make build           - Build debug APK"
	@echo "  make release         - Build release APK"
	@echo "  make test            - Run JVM unit test suite"
	@echo "  make clean           - Clean build artifacts and Gradle caches"
	@echo "  make install         - Install debug APK via standard ADB (TV_HOST=$(TV_HOST))"
	@echo "  make push-install    - Install debug APK via standalone python bridge (tools/b64push.py)"
	@echo "  make start           - Launch OmniDeck on target TV"
	@echo "  make stop            - Force-stop OmniDeck on target TV"
	@echo "  make restart         - Rebuild, reinstall and restart OmniDeck on TV"
	@echo "  make logcat          - Stream live OmniDeck logcat output"
	@echo "  make test-e2e        - Run comprehensive 30-item end-to-end verification suite"
	@echo "  make permissions     - Grant required system permissions via ADB"
	@echo "  make screenshot      - Capture and save TV screen via OmniDeck API"
	@echo "  make status          - Query diagnostic telemetry from TV Web Hub"

# --- Compilation & Testing ---

build:
	$(GRADLEW) assembleDebug

release:
	$(GRADLEW) assembleRelease

test:
	$(GRADLEW) testDebugUnitTest

clean:
	$(GRADLEW) clean

# --- Device Deployment & Management ---

connect:
	adb connect $(TV_HOST):$(TV_PORT)

install: build
	adb -s $(TV_HOST):$(TV_PORT) install -r $(APK_DEBUG)

push-install: build
	TV_HOST=$(TV_HOST) $(PYTHON) tools/b64push.py $(APK_DEBUG) /data/local/tmp/omnideck.apk
	TV_HOST=$(TV_HOST) $(PYTHON) tools/tv.py "pm install -r /data/local/tmp/omnideck.apk && rm /data/local/tmp/omnideck.apk"

permissions:
	TV_HOST=$(TV_HOST) $(PYTHON) tools/tv.py "pm grant com.mahanapps.omnideck android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; pm grant com.mahanapps.omnideck android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null; appops set com.mahanapps.omnideck GET_USAGE_STATS allow; settings put secure enabled_accessibility_services com.mahanapps.omnideck/.android.OmniDeckAccessibilityService; settings put secure accessibility_enabled 1"

start:
	TV_HOST=$(TV_HOST) $(PYTHON) tools/tv.py "input keyevent KEYCODE_WAKEUP; am start -n com.mahanapps.omnideck/.android.OmniDeckActivity"

stop:
	TV_HOST=$(TV_HOST) $(PYTHON) tools/tv.py "am force-stop com.mahanapps.omnideck"

restart: build push-install permissions start

logcat:
	TV_HOST=$(TV_HOST) $(PYTHON) tools/tv.py "logcat -s OmniDeck:V OmniDeckService:V OmniDeckA11y:V"

test-e2e:
	TV_HOST=$(TV_HOST) $(PYTHON) tools/test_omnideck_e2e.py

screenshot:
	TV_HOST=$(TV_HOST) $(PYTHON) tools/tv.py "screencap -p /sdcard/tv_capture.png"
	TV_HOST=$(TV_HOST) $(PYTHON) tools/tv.py --pull /sdcard/tv_capture.png ./tv_capture.png
	@echo "Screenshot saved to ./tv_capture.png"

status:
	curl -s http://$(TV_HOST):$(HTTP_PORT)/api/status
