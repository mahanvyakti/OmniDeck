package com.mahanapps.omnideck.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe persistent storage for the shared NoteBoard and Clipboard History.
 * Tracks who copied each note (device details & timestamp) and automatically
 * synchronizes incoming notes to the Android TV system clipboard.
 */
public final class NoteBoardStore {

    private static final String HISTORY_FILE = "clipboard_history.json";
    private static final int MAX_CHARS = 32768; // 32 KB character limit
    private static final int MAX_HISTORY = 20;

    public static final class ClipItem {
        public final String id;
        public final String text;
        public final String sender;
        public final String osType;
        public final long timestampMs;

        public ClipItem(String id, String text, String sender, String osType, long timestampMs) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.text = text != null ? text : "";
            this.sender = sender != null && !sender.trim().isEmpty() ? sender.trim() : "Living Room TV";
            this.osType = osType != null ? osType : "device";
            this.timestampMs = timestampMs > 0 ? timestampMs : System.currentTimeMillis();
        }

        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("text", text);
                obj.put("sender", sender);
                obj.put("osType", osType);
                obj.put("timestampMs", timestampMs);
                return obj;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static ClipItem fromJson(JSONObject obj) {
            return new ClipItem(
                    obj.optString("id", UUID.randomUUID().toString()),
                    obj.optString("text", ""),
                    obj.optString("sender", "Unknown Device"),
                    obj.optString("osType", "device"),
                    obj.optLong("timestampMs", System.currentTimeMillis())
            );
        }
    }

    public interface OnNoteChangeListener {
        void onNoteChanged(ClipItem activeClip);
    }

    private final Context context;
    private final File historyFile;
    private final CopyOnWriteArrayList<OnNoteChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ClipItem> history = new ArrayList<>();

    public NoteBoardStore(Context context) {
        this.context = context.getApplicationContext();
        this.historyFile = new File(this.context.getFilesDir(), HISTORY_FILE);
        loadHistory();
    }

    public synchronized ClipItem getActiveClip() {
        if (history.isEmpty()) {
            return new ClipItem(
                    "welcome",
                    "Welcome to OmniDeck!\nType or paste notes on your phone/PC companion, and they appear here instantly.",
                    "OmniDeck System",
                    "device",
                    System.currentTimeMillis()
            );
        }
        return history.get(0);
    }

    public synchronized String getNote() {
        return getActiveClip().text;
    }

    public synchronized List<ClipItem> getHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public synchronized String getHistoryJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("active", getActiveClip().toJson());
            JSONArray arr = new JSONArray();
            for (ClipItem c : history) {
                arr.put(c.toJson());
            }
            root.put("history", arr);
            return root.toString();
        } catch (Exception e) {
            return "{\"active\":{},\"history\":[]}";
        }
    }

    public synchronized void setNote(String note) {
        addClip(note, "Living Room Companion", "device");
    }

    public synchronized void addClip(String text, String sender, String osType) {
        if (text == null) text = "";
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }
        android.util.Log.i("OmniDeck", "Note updated: " + text);

        ClipItem newItem = new ClipItem(
                UUID.randomUUID().toString(),
                text,
                sender,
                osType,
                System.currentTimeMillis()
        );

        // Prepend to history
        history.add(0, newItem);
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }

        saveHistory();

        // Automatically sync to TV system clipboard on UI thread
        mainHandler.post(() -> copyToClipboard(newItem));
        notifyListeners(newItem);
    }

    public synchronized void clearHistory() {
        history.clear();
        saveHistory();
        ClipItem empty = new ClipItem(
                "empty",
                "",
                "System",
                "android",
                System.currentTimeMillis()
        );
        mainHandler.post(() -> copyToClipboard(empty));
        notifyListeners(empty);
    }

    public void addListener(OnNoteChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(OnNoteChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(ClipItem clip) {
        for (OnNoteChangeListener l : listeners) {
            try {
                l.onNoteChanged(clip);
            } catch (Exception ignored) {}
        }
    }

    public boolean copyToClipboard() {
        return copyToClipboard(getActiveClip());
    }

    public boolean copyToClipboard(ClipItem clip) {
        if (clip == null || clip.text == null || clip.text.isEmpty()) {
            return false;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData data = ClipData.newPlainText("OmniDeck (" + clip.sender + ")", clip.text);
                clipboard.setPrimaryClip(data);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void loadHistory() {
        if (!historyFile.exists()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(historyFile)) {
            byte[] data = new byte[(int) historyFile.length()];
            int read = fis.read(data);
            if (read > 0) {
                String jsonStr = new String(data, 0, read, StandardCharsets.UTF_8);
                JSONObject root = new JSONObject(jsonStr);
                JSONArray arr = root.optJSONArray("history");
                if (arr != null) {
                    history.clear();
                    for (int i = 0; i < arr.length(); i++) {
                        history.add(ClipItem.fromJson(arr.getJSONObject(i)));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void saveHistory() {
        try (FileOutputStream fos = new FileOutputStream(historyFile)) {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (ClipItem c : history) {
                arr.put(c.toJson());
            }
            root.put("history", arr);
            fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }
}
