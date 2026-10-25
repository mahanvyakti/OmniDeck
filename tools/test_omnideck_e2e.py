#!/usr/bin/env python3
"""
Comprehensive End-to-End Test Suite for OmniDeck (com.mahanapps.omnideck).
Verifies:
1. TV Package & Activity status
2. Web Companion UI (HTML, CSS, OLED Bento layout, 'By Mahan Vyakti')
3. 6-Digit PIN Pairing & Multi-Device Session Tracking
4. Diagnostic Telemetry (/api/status: RAM, Storage breakdown, Network)
5. Shared NoteBoard & Hindi/Unicode Sync
6. Storage Floor Enforced File Vault Upload & Download
7. Speed Test Execution
8. Path Traversal & Security Constraints
9. Home Launcher Integrity (com.gathin.tvhome remains default)
"""

import os
import sys
import time
import json
import re
import urllib.request
import urllib.error
import urllib.parse
import subprocess

TV_IP = os.environ.get("TV_HOST")
if not TV_IP:
    print("TV_HOST environment variable not set. Defaulting to 192.168.1.100 (or pass via TV_HOST=xxx)")
    TV_IP = "192.168.1.100"
os.environ["TV_HOST"] = TV_IP
TV_PORT = 8080
BASE_URL = f"http://{TV_IP}:{TV_PORT}"
PYTHON_EXE = os.path.join(os.path.dirname(__file__), "venv", "Scripts", "python.exe")
TV_SCRIPT = os.path.join(os.path.dirname(__file__), "tv.py")

def tv_cmd(cmd_str):
    res = subprocess.run([PYTHON_EXE, TV_SCRIPT, cmd_str], capture_output=True, encoding="utf-8", errors="replace")
    return (res.stdout or "").strip()

def http_get(path, headers=None, timeout=25):
    url = f"{BASE_URL}{path}"
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.getcode(), resp.headers, resp.read()

def http_post_json(path, data_dict, headers=None):
    url = f"{BASE_URL}{path}"
    payload = json.dumps(data_dict).encode("utf-8")
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=payload, headers=req_headers, method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.getcode(), resp.headers, resp.read()

def http_post_file(path, filename, content_bytes, headers=None):
    url = f"{BASE_URL}{path}?name={urllib.parse.quote(filename)}"
    req_headers = {"Content-Type": "application/octet-stream", "Content-Length": str(len(content_bytes))}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=content_bytes, headers=req_headers, method="POST")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.getcode(), resp.headers, resp.read()

def test_suite():
    passed = 0
    total = 0

    def check(name, condition, details=""):
        nonlocal passed, total
        total += 1
        if condition:
            print(f"  [PASS] {name} {details}")
            passed += 1
        else:
            print(f"  [FAIL] {name} {details}")

    print("==================================================")
    print("OmniDeck End-to-End Verification Suite")
    print("==================================================")

    # 1. TV Launcher Integrity Check
    print("\n--- Test 1: TV Launcher Integrity ---")
    home_res = tv_cmd("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    is_tvhome = "com.gathin.tvhome" in home_res
    check("Default Home Launcher", is_tvhome, f"(Resolved: {home_res.splitlines()[-1] if home_res else 'None'})")

    # 2. Package verification
    print("\n--- Test 2: App & Package Check ---")
    pkg_list = tv_cmd("pm list packages com.mahanapps.omnideck")
    check("New Package com.mahanapps.omnideck Installed", "package:com.mahanapps.omnideck" in pkg_list)

    old_pkg = tv_cmd("pm list packages com.gathin.omnideck")
    check("Old Package com.gathin.omnideck Uninstalled", "com.gathin.omnideck" not in old_pkg)

    # 3. Web Companion HTML / OLED Bento Theme / Creator Check
    print("\n--- Test 3: Web Companion Interface ---")
    try:
        code, headers, html_bytes = http_get("/")
        html = html_bytes.decode("utf-8", errors="replace")
        check("HTTP 200 Root Dashboard", code == 200)
        check("OLED Bento Theme Assets", "#000" in html or "#0b0e14" in html or "bento" in html.lower())
        check("Creator Attribution 'By Mahan Vyakti'", "By Mahan Vyakti" in html or "Mahan Vyakti" in html)
        check("Multi-Device Tracker Elements Present", "connected-devices" in html or "paired" in html.lower() or "device" in html.lower())
    except Exception as e:
        check("HTTP Root Reachable", False, str(e))

    # 4. PIN Retrieval & Pairing
    print("\n--- Test 4: Physical-Presence PIN Pairing ---")
    pin = None
    # 1. Try Logcat for active PIN
    time.sleep(1)
    for attempt in range(3):
        logcat_out = tv_cmd("logcat -d -t 50 -s OmniDeck:I")
        pin_matches = re.findall(r'Active PIN:\s*(\d{6})', logcat_out)
        if pin_matches:
            pin = pin_matches[-1]
            break
        time.sleep(1)

    if pin:
        print(f"  Extracted PIN from OmniDeck logcat: {pin}")

    if not pin:
        # 2. Try UI Hierarchy dump
        tv_cmd("input keyevent KEYCODE_WAKEUP")
        dump_xml = tv_cmd("uiautomator dump /sdcard/ui_test.xml && cat /sdcard/ui_test.xml")
        pin_match = re.search(r'text="(\d{3}\s\d{3})"', dump_xml)
        if pin_match:
            pin = pin_match.group(1).replace(" ", "")
            print(f"  Extracted PIN from TV screen: {pin}")

    token = None
    if pin:
        try:
            # Pair as Device 1 (Windows Desktop)
            code, h, resp = http_post_json("/api/pair", {"pin": pin, "device": "Windows Desktop (Chrome)"})
            res_json = json.loads(resp.decode("utf-8"))
            if res_json.get("success"):
                token = res_json.get("token")
                check("PIN Pairing Succeeded", True, f"Issued Token: {token[:8]}...")
            else:
                check("PIN Pairing Succeeded", False, str(res_json))
        except Exception as e:
            check("PIN Pairing Succeeded", False, str(e))
    else:
        check("PIN Screen Extraction", False, "No PIN found on screen")

    # 5. Diagnostic Status API (/api/status)
    print("\n--- Test 5: Diagnostic Status & Telemetry ---")
    if token:
        try:
            code, h, resp = http_get("/api/status", headers={"Authorization": f"Bearer {token}"})
            status = json.loads(resp.decode("utf-8"))
            check("RAM Telemetry Present", "ram" in status and status["ram"]["totalKb"] > 0, f"Used: {status['ram']['usedPercent']}%")
            check("Storage Breakdown Present", "storage" in status and status["storage"]["totalBytes"] > 0)
            check("Hard 500 MB Reserve Enforced", status["storage"]["floorBytes"] >= 500 * 1024 * 1024)
            check("Network Throughput Metric Present", "network" in status and "rxKbPerSec" in status["network"])
            check("Creator Metadata", status.get("creator") == "Mahan Vyakti")
            check("Paired Devices Tracking", len(status.get("pairedDevices", [])) >= 1, f"Count: {len(status.get('pairedDevices', []))}")
        except Exception as e:
            check("Telemetry API Valid", False, str(e))
    else:
        check("Telemetry API Authenticated", False, "Skipped due to no token")

    # 6. Multi-Device Pairing (Device 2: iPhone)
    print("\n--- Test 6: Multi-Device Pairing Support ---")
    if pin and token:
        try:
            code, h, resp2 = http_post_json("/api/pair", {"pin": pin, "device": "iPhone 15 Pro (Safari)"})
            res_json2 = json.loads(resp2.decode("utf-8"))
            token2 = res_json2.get("token")
            check("Device 2 Pairing", res_json2.get("success") and token2 is not None)

            # Check status reflects 2 devices
            code, h, resp = http_get("/api/status", headers={"Authorization": f"Bearer {token}"})
            st = json.loads(resp.decode("utf-8"))
            devs = st.get("pairedDevices", [])
            check("Multi-Device Tracking (>= 2 devices)", len(devs) >= 2, f"Active: {[d['label'] for d in devs]}")
        except Exception as e:
            check("Device 2 Pairing", False, str(e))

    # 7. NoteBoard & Clipboard Sync
    print("\n--- Test 7: Shared NoteBoard (Unicode & Hindi Sync) ---")
    if token:
        try:
            hindi_note = "नमस्ते Mahan Vyakti! OmniDeck Clipboard Test 🚀 " + str(time.time())
            code, h, resp = http_post_json("/api/notes", {"note": hindi_note}, headers={"Authorization": f"Bearer {token}"})
            check("NoteBoard POST Success", code == 200)

            code, h, resp = http_get("/api/notes", headers={"Authorization": f"Bearer {token}"})
            read_note = json.loads(resp.decode("utf-8")).get("note", "")
            check("NoteBoard Unicode Retention", read_note == hindi_note)

            # Check TV screen preview reflects text
            time.sleep(1)
            logcat_notes = tv_cmd("logcat -d -s OmniDeck:I")
            check("TV Screen Note Preview", "नमस्ते Mahan Vyakti" in logcat_notes)
        except Exception as e:
            check("NoteBoard API Valid", False, str(e))

    # 8. File Vault Upload with Floor Reserve Guard
    print("\n--- Test 8: Safe File Vault Upload & Download ---")
    if token:
        try:
            test_content = ("OmniDeck Safe Transfer Payload\nCreated by Mahan Vyakti\n" * 100).encode("utf-8")
            fname = "test_note_vibehack.txt"
            code, h, resp = http_post_file("/api/vault/upload", fname, test_content, headers={"Authorization": f"Bearer {token}"})
            res_upload = json.loads(resp.decode("utf-8"))
            check("Vault Upload Success", res_upload.get("success") is True, f"Bytes: {res_upload.get('bytes')}")

            # List files
            code, h, resp = http_get("/api/vault/list", headers={"Authorization": f"Bearer {token}"})
            raw_files = json.loads(resp.decode("utf-8"))
            file_list = raw_files.get("files", []) if isinstance(raw_files, dict) else raw_files
            found = any(f.get("name") == fname for f in file_list)
            check("Uploaded File in Vault Listing", found)

            # Ticket & Download
            code, h, resp = http_get(f"/api/vault/ticket?name={fname}", headers={"Authorization": f"Bearer {token}"})
            ticket = json.loads(resp.decode("utf-8")).get("ticket")
            check("Single-Use Download Ticket Issued", ticket is not None)

            if ticket:
                code, h, dl_bytes = http_get(f"/api/vault/download?ticket={ticket}")
                check("Ticket-Guarded Download Content Matches", dl_bytes == test_content)
        except Exception as e:
            check("Vault Transfer", False, str(e))

    # 9. Path Traversal & Security Constraints
    print("\n--- Test 9: Path Traversal Exploit Rejection ---")
    if token:
        try:
            traversal_names = ["../../etc/passwd", "..%2F..%2Fsystem", "test\0null.txt"]
            for tn in traversal_names:
                try:
                    code, h, resp = http_get(f"/api/vault/ticket?name={urllib.parse.quote(tn)}", headers={"Authorization": f"Bearer {token}"})
                    res_j = json.loads(resp.decode("utf-8"))
                    check(f"Reject Traversal: {tn}", not res_j.get("success") or res_j.get("ticket") is None)
                except urllib.error.HTTPError as he:
                    check(f"Reject Traversal: {tn}", he.code in [400, 403, 404])
        except Exception as e:
            check("Traversal Defense", False, str(e))

    # 10. Speed Test Endpoint
    print("\n--- Test 10: Speed Test Telemetry ---")
    if token:
        try:
            code, h, resp = http_get("/api/speedtest", headers={"Authorization": f"Bearer {token}"})
            speed_data = json.loads(resp.decode("utf-8"))
            check("Speed Test Returns Success", speed_data.get("success") is True, f"Speed: {speed_data.get('mbps')} Mbps")
        except Exception as e:
            check("Speed Test Execution", False, str(e))

    # 11. Screenshot Capture API (Task 1)
    print("\n--- Test 11: Screenshot Capture Endpoint ---")
    if token:
        try:
            code, h, resp = http_get("/api/screenshot", headers={"Authorization": f"Bearer {token}"})
            is_png = resp.startswith(b"\x89PNG\r\n\x1a\n")
            check("Screenshot Returned Valid PNG", is_png and len(resp) > 5000, f"Bytes: {len(resp)}")
            content_disp = h.get("Content-Disposition", "")
            check("Screenshot Attachment Header", "attachment; filename=" in content_disp, content_disp)

            time.sleep(1)
            ls_ss = tv_cmd("ls -t /sdcard/Pictures/Screenshots | head -n 1")
            check("Screenshot Saved to TV /sdcard/Pictures/Screenshots", ls_ss.startswith("Screenshot_") and ls_ss.endswith(".png"), f"File: {ls_ss}")
        except Exception as e:
            check("Screenshot Capture Execution", False, str(e))

    print("\n==================================================")
    print(f"Results: {passed} / {total} Passed ({int((passed / total) * 100)}%)")
    print("==================================================")
    return passed == total

if __name__ == "__main__":
    success = test_suite()
    sys.exit(0 if success else 1)
