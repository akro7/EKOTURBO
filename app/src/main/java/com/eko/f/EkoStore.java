package com.eko.f;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import java.util.List;

public final class EkoStore {

    private static final String PREFS_NAME = "com.eko.f.STORE";

    private static final String KEY_AUTO_REBOOT      = "auto_reboot";
    private static final String KEY_SKIP_SHA256      = "skip_sha256";
    private static final String KEY_WIRELESS_ENABLED = "wireless_enabled";
    private static final String KEY_NAND_ERASE       = "nand_erase";
    private static final String KEY_CONNECTION_MODE  = "connection_mode";
    private static final String KEY_PIT_URI      = "pit_uri";
    private static final String KEY_ROOT_ENABLED = "root_enabled";
    private static final String KEY_ROOT_TYPE    = "root_type";

    private final SharedPreferences prefs;
    private final Context           context;

    // LiveData observables
    private final MutableLiveData<String>  liveStatus      = new MutableLiveData<>("");
    private final MutableLiveData<Integer> liveProgress    = new MutableLiveData<>(0);
    private final MutableLiveData<String>  liveDeviceLabel = new MutableLiveData<>("");
    private final MutableLiveData<String>  liveLog         = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> liveFinished    = new MutableLiveData<>(null);

    // Runtime-only state
    private volatile boolean sessionActive   = false;
    private volatile boolean wirelessEnabled = false;
    private volatile String  wirelessStatus  = null;
    private volatile String  wirelessAddr    = null;
    private volatile List<Uri> packageUris   = null;

    private final StringBuilder logBuilder = new StringBuilder();

    public EkoStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs   = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.wirelessEnabled = prefs.getBoolean(KEY_WIRELESS_ENABLED, false);
    }

    // ── LiveData observers ────────────────────────────────────────────────────

    public interface FinishedCallback {
        void onFinished(boolean ok, String msg);
    }

    public void observeStatus(LifecycleOwner owner, Observer<String> observer) {
        liveStatus.observe(owner, observer);
    }

    public void observeProgress(LifecycleOwner owner, Observer<Integer> observer) {
        liveProgress.observe(owner, observer);
    }

    public void observeDeviceLabel(LifecycleOwner owner, Observer<String> observer) {
        liveDeviceLabel.observe(owner, observer);
    }

    public void observeLog(LifecycleOwner owner, Observer<String> observer) {
        liveLog.observe(owner, observer);
    }

    public void observeFinished(LifecycleOwner owner, FinishedCallback cb) {
        liveFinished.observe(owner, ok -> {
            if (ok != null) cb.onFinished(ok, "");
        });
    }

    // ── State setters ─────────────────────────────────────────────────────────

    public void setStatus(String status) {
        postMain(liveStatus, status);
    }

    public void setProgress(int partition, int current, int total) {
        int pct = total > 0 ? (current * 100 / total) : 0;
        postMain(liveProgress, pct);
    }

    public void setDeviceLabel(String label) {
        postMain(liveDeviceLabel, label != null ? label : "");
    }

    public void appendLog(String message) {
        synchronized (logBuilder) {
            logBuilder.append(message).append("\n");
            final String snapshot = logBuilder.toString();
            postMain(liveLog, snapshot);
        }
    }

    public void clearLog() {
        synchronized (logBuilder) {
            logBuilder.setLength(0);
        }
        postMain(liveLog, "");
    }

    public void logError(String message) {
        android.util.Log.e("EkoStore", message);
        appendLog("[ERROR] " + message);
    }

    private <T> void postMain(MutableLiveData<T> ld, T value) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            ld.setValue(value);
        } else {
            ld.postValue(value);
        }
    }

    // ── Package URIs ──────────────────────────────────────────────────────────

    public void setPackageUris(List<Uri> uris) {
        this.packageUris = uris;
    }

    public List<Uri> getPackageUris() {
        return packageUris;
    }

    // ── Options ───────────────────────────────────────────────────────────────

    public boolean isAutoReboot() {
        return prefs.getBoolean(KEY_AUTO_REBOOT, true);
    }
    public void setAutoReboot(boolean v) {
        prefs.edit().putBoolean(KEY_AUTO_REBOOT, v).apply();
    }

    public boolean getDefaultAutoReboot() {
        return isAutoReboot();
    }

    public boolean isSkipSha256() {
        return prefs.getBoolean(KEY_SKIP_SHA256, false);
    }
    public void setSkipSha256(boolean v) {
        prefs.edit().putBoolean(KEY_SKIP_SHA256, v).apply();
    }

    public boolean isWirelessEnabled() {
        return wirelessEnabled;
    }
    public void setWirelessEnabled(boolean v) {
        wirelessEnabled = v;
        prefs.edit().putBoolean(KEY_WIRELESS_ENABLED, v).apply();
    }

    public boolean isNandErase() {
        return prefs.getBoolean(KEY_NAND_ERASE, false);
    }
    public void setNandErase(boolean v) {
        prefs.edit().putBoolean(KEY_NAND_ERASE, v).apply();
    }

    public String getConnectionMode() {
        return prefs.getString(KEY_CONNECTION_MODE, "usb");
    }
    public void setConnectionMode(String mode) {
        prefs.edit().putString(KEY_CONNECTION_MODE, mode).apply();
    }

    // ── PIT URI ───────────────────────────────────────────────────────────────

    public Uri getPitUri() {
        String s = prefs.getString(KEY_PIT_URI, null);
        return s != null ? Uri.parse(s) : null;
    }
    public void setPitUri(Uri uri) {
        prefs.edit().putString(KEY_PIT_URI, uri != null ? uri.toString() : null).apply();
    }
    public void clearPitUri() {
        prefs.edit().remove(KEY_PIT_URI).apply();
    }

    // ── Session state ─────────────────────────────────────────────────────────

    public boolean isSessionActive() { return sessionActive; }

    public void setSessionActive(boolean v) { sessionActive = v; }

    public void cancelSession() {
        sessionActive = false;
        setStatus(context.getString(R.string.status_idle));
        postMain(liveProgress, 0);
    }

    public void setFinished(boolean success, String message) {
        sessionActive = false;
        String status = success
                ? context.getString(R.string.flash_status_done)
                : context.getString(R.string.flash_status_failed) + ": " + message;
        setStatus(status);
        postMain(liveProgress, 0);
        postMain(liveFinished, success);
    }

    // ── Wireless state ────────────────────────────────────────────────────────

    public void setWirelessStatus(String addr, String status) {
        wirelessAddr   = addr;
        wirelessStatus = status;
    }

    public String getWirelessStatus() { return wirelessStatus; }
    public String getWirelessAddr()   { return wirelessAddr;   }

    public void setWirelessDevice(String deviceId, String label, String peerLabel) {
        setDeviceLabel(label);
    }

    // ── Transfer speed ────────────────────────────────────────────────────
    private final MutableLiveData<Double> liveSpeed = new MutableLiveData<>(0.0);

    public void observeSpeed(LifecycleOwner owner, Observer<Double> observer) {
        liveSpeed.observe(owner, observer);
    }

    public void setTransferSpeed(double mbps) {
        postMain(liveSpeed, mbps);
    }

    public void clearDevices() {
        setDeviceLabel("");
    }

    // ── Root Mode ─────────────────────────────────────────────────────────────
    public boolean isRootEnabled() {
        return prefs.getBoolean(KEY_ROOT_ENABLED, false);
    }
    public void setRootEnabled(boolean v) {
        prefs.edit().putBoolean(KEY_ROOT_ENABLED, v).apply();
    }

    public String getRootType() {
        return prefs.getString(KEY_ROOT_TYPE, "auto");
    }
    public void setRootType(String type) {
        prefs.edit().putString(KEY_ROOT_TYPE, type).apply();
    }

}
