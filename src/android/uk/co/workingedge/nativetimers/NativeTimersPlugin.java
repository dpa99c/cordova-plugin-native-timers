package uk.co.workingedge.nativetimers;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Runs timers on a dedicated thread so WebView timer throttling cannot delay their callbacks.
 * Without a wake lock, Handler timing uses uptime and does not advance during CPU deep sleep.
 */
public class NativeTimersPlugin extends CordovaPlugin {
    private static final String TAG = "NativeTimers";
    private static final long WAKE_LOCK_MARGIN_MS = 5000L;
    /** Keeps JavaScript callback delivery awake briefly after the native timer fires. */
    private static final long POST_FIRE_WAKE_LOCK_HOLD_MS = 3000L;
    private static final long MAX_WAKE_LOCK_DELAY_MS = 60000L;
    private static final long LATE_LOG_THRESHOLD_MS = 1000L;
    private static final long MIN_INTERVAL_MS = 100L;

    private final ConcurrentHashMap<String, TimerEntry> timers = new ConcurrentHashMap<>();
    private HandlerThread timerThread;
    private Handler timerHandler;

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        timerThread = new HandlerThread(TAG);
        timerThread.start();
        timerHandler = new Handler(timerThread.getLooper());
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callback) {
        if ("setTimeout".equals(action)) {
            schedule(args, callback, false);
        } else if ("setInterval".equals(action)) {
            schedule(args, callback, true);
        } else if ("clear".equals(action)) {
            clear(args.optString(0, ""));
            callback.success();
        } else if ("clearAll".equals(action)) {
            clearAll();
            callback.success();
        } else {
            callback.error("Unknown NativeTimers action: " + action);
        }
        return true;
    }

    private void schedule(JSONArray args, CallbackContext callback, boolean repeating) {
        if (args == null || args.length() < 2) {
            callback.error("Timer ID and delay are required");
            return;
        }

        String id = args.optString(0, "");
        double requestedDelayMs = args.optDouble(1, Double.NaN);
        if (id.isEmpty() || Double.isNaN(requestedDelayMs) || Double.isInfinite(requestedDelayMs) ||
                requestedDelayMs < 0) {
            callback.error("Timer ID and a non-negative finite delay are required");
            return;
        }
        if (repeating && requestedDelayMs < MIN_INTERVAL_MS) {
            callback.error("Timer interval must be at least " + MIN_INTERVAL_MS + "ms");
            return;
        }
        if (timerHandler == null) {
            callback.error("Native timer thread is unavailable");
            return;
        }

        long delayMs = Math.round(requestedDelayMs);
        JSONObject options = args.optJSONObject(2);
        String label = options == null ? "" : options.optString("label", "");
        boolean requestWakeLock = !repeating && options != null && options.optBoolean("wakeLock", false);

        clear(id);

        PowerManager.WakeLock wakeLock = null;
        if (requestWakeLock && delayMs <= MAX_WAKE_LOCK_DELAY_MS) {
            try {
                Context context = cordova.getContext().getApplicationContext();
                PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (powerManager == null) {
                    callback.error("Power manager is unavailable for timer " + label);
                    return;
                }
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":" + label);
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(delayMs + WAKE_LOCK_MARGIN_MS);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Failed to acquire wake lock for timer " + label, exception);
                callback.error("Failed to acquire wake lock: " + exception.getMessage());
                return;
            }
        } else if (requestWakeLock) {
            Log.w(TAG, "Skipping wake lock for timer " + label + ": delay exceeds " +
                MAX_WAKE_LOCK_DELAY_MS + "ms");
        }

        long nowUptimeMs = SystemClock.uptimeMillis();
        TimerEntry entry = new TimerEntry(
            id,
            label,
            delayMs,
            repeating,
            System.currentTimeMillis() + delayMs,
            nowUptimeMs + delayMs,
            callback,
            wakeLock
        );
        entry.runnable = () -> fire(entry);
        timers.put(id, entry);

        PluginResult pending = new PluginResult(PluginResult.Status.NO_RESULT);
        pending.setKeepCallback(true);
        callback.sendPluginResult(pending);

        Log.d(TAG, "Set timer: id=" + id + " label=" + label + " delayMs=" + delayMs +
            " repeating=" + repeating + " wakeLock=" + (wakeLock != null));
        if (!timerHandler.postAtTime(entry.runnable, id, entry.nextUptimeMs)) {
            timers.remove(id, entry);
            releaseWakeLock(entry.wakeLock);
            callback.error("Failed to schedule timer " + id);
        }
    }

    private void fire(TimerEntry entry) {
        if (timers.get(entry.id) != entry) return;

        long firedAtWallMs = System.currentTimeMillis();
        long lateMs = firedAtWallMs - entry.dueAtWallMs;
        JSONObject event = new JSONObject();
        try {
            event.put("id", entry.id);
            event.put("label", entry.label);
            event.put("dueAt", entry.dueAtWallMs);
            event.put("firedAt", firedAtWallMs);
            event.put("lateMs", lateMs);
        } catch (JSONException exception) {
            Log.e(TAG, "Failed to build timer event for " + entry.id, exception);
            timers.remove(entry.id, entry);
            releaseWakeLock(entry.wakeLock);
            entry.callback.error("Failed to build timer event: " + exception.getMessage());
            return;
        }

        PluginResult result = new PluginResult(PluginResult.Status.OK, event);
        result.setKeepCallback(entry.repeating);
        entry.callback.sendPluginResult(result);
        Log.i(TAG, "Timer fired: id=" + entry.id + " label=" + entry.label + " lateMs=" + lateMs);
        if (lateMs > LATE_LOG_THRESHOLD_MS) {
            Log.w(TAG, "Timer fired late: id=" + entry.id + " label=" + entry.label + " lateMs=" + lateMs);
        }

        if (!entry.repeating) {
            timers.remove(entry.id, entry);
            if (entry.wakeLock != null) {
                timerHandler.postDelayed(
                    () -> releaseWakeLock(entry.wakeLock),
                    POST_FIRE_WAKE_LOCK_HOLD_MS
                );
            }
            return;
        }

        long nowUptimeMs = SystemClock.uptimeMillis();
        long nextUptimeMs = entry.nextUptimeMs + entry.intervalMs;
        while (nextUptimeMs <= nowUptimeMs) {
            nextUptimeMs += entry.intervalMs;
        }
        entry.nextUptimeMs = nextUptimeMs;
        entry.dueAtWallMs = firedAtWallMs + (nextUptimeMs - nowUptimeMs);
        if (!timerHandler.postAtTime(entry.runnable, entry.id, entry.nextUptimeMs)) {
            timers.remove(entry.id, entry);
            releaseWakeLock(entry.wakeLock);
            entry.callback.error("Failed to reschedule interval " + entry.id);
        }
    }

    private void clear(String id) {
        TimerEntry entry = timers.remove(id);
        if (entry == null) return;
        if (timerHandler != null) timerHandler.removeCallbacksAndMessages(id);
        releaseWakeLock(entry.wakeLock);
        PluginResult cancelled = new PluginResult(PluginResult.Status.NO_RESULT);
        entry.callback.sendPluginResult(cancelled);
        Log.d(TAG, "Cleared timer: id=" + id + " label=" + entry.label);
    }

    private void clearAll() {
        for (String id : timers.keySet()) {
            clear(id);
        }
    }

    private void releaseWakeLock(PowerManager.WakeLock wakeLock) {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onReset() {
        clearAll();
    }

    @Override
    public void onDestroy() {
        clearAll();
        if (timerThread != null) {
            timerThread.quitSafely();
            timerThread = null;
            timerHandler = null;
        }
        super.onDestroy();
    }

    private static final class TimerEntry {
        private final String id;
        private final String label;
        private final long intervalMs;
        private final boolean repeating;
        private final CallbackContext callback;
        private final PowerManager.WakeLock wakeLock;
        private long dueAtWallMs;
        private long nextUptimeMs;
        private Runnable runnable;

        private TimerEntry(
            String id,
            String label,
            long intervalMs,
            boolean repeating,
            long dueAtWallMs,
            long nextUptimeMs,
            CallbackContext callback,
            PowerManager.WakeLock wakeLock
        ) {
            this.id = id;
            this.label = label;
            this.intervalMs = intervalMs;
            this.repeating = repeating;
            this.dueAtWallMs = dueAtWallMs;
            this.nextUptimeMs = nextUptimeMs;
            this.callback = callback;
            this.wakeLock = wakeLock;
        }
    }
}
