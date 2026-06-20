package com.eko.f;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FlashService — Thor-style USB-only Samsung flash service
 *
 * الفلسفة (مأخوذة من libthor):
 *   1. disable cdc_acm kernel module قبل الفلاش
 *   2. فقط .tar و .tar.md5 — رفض صارم لأي صيغة أخرى
 *   3. Root + USB فقط — لا wireless، لا TCP
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
public final class FlashService extends Service {

    private static final String TAG        = "FlashService";
    private static final int    NOTIF_ID   = 101;
    private static final String CHANNEL_ID = "flash_channel";

    public static final String ACTION_START_FLASH                = "com.eko.f.action.START_FLASH";
    public static final String ACTION_RESTORE_FLASH_NOTIFICATION = "com.eko.f.action.RESTORE_FLASH_NOTIFICATION";
    public static final String ACTION_CANCEL_FLASH               = "com.eko.f.action.CANCEL_FLASH";

    public static final String EXTRA_PACKAGE_URIS     = "package_uris";
    public static final String EXTRA_PIT_URI          = "pit_uri";
    public static final String EXTRA_AUTO_REBOOT      = "auto_reboot";
    public static final String EXTRA_CONNECTION_MODE  = "connection_mode"; // "usb" | "wireless"

    // ── Thor-style: الصيغتان المدعومتان فقط ──────────────────────────────────
    private static final String[] VALID_EXTENSIONS = {".tar", ".tar.md5"};

    private FlashSession          currentSession;
    private PowerManager.WakeLock wakeLock;
    private boolean               receiverRegistered = false;
    private volatile boolean      stopping           = false;
    private Integer               foregroundType     = null;
    private boolean               foregroundActive   = false;

    // ── Session watchdog ──────────────────────────────────────────────────────
    private static final long WATCHDOG_TIMEOUT_MS = 300_000L;
    private final Handler    watchdogHandler = new Handler(Looper.getMainLooper());
    private volatile long    lastProgressTime = 0L;
    private Runnable         watchdogRunnable;

    private final UsbDetachReceiver usbDetachReceiver = new UsbDetachReceiver(this);

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) NotificationChannelHelper.createChannel(nm, CHANNEL_ID,
                    getString(R.string.notification_channel_name));
        }
        if (foregroundType == null) foregroundType = 1;
        promoteToForeground(buildNotification(getString(R.string.flash_status_preparing)), false);

        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(usbDetachReceiver, filter, RECEIVER_NOT_EXPORTED);
            else
                registerReceiver(usbDetachReceiver, filter);
            receiverRegistered = true;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (foregroundType == null) foregroundType = 1;
        promoteToForeground(buildNotification(getString(R.string.flash_status_preparing)), false);

        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }

        String action = intent.getAction();

        if (ACTION_CANCEL_FLASH.equals(action)) {
            cancelSession("User cancelled");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_RESTORE_FLASH_NOTIFICATION.equals(action)) {
            if (currentSession == null) { stopSelf(startId); return START_NOT_STICKY; }
            return START_STICKY;
        }

        if (!ACTION_START_FLASH.equals(action)) { stopSelf(startId); return START_NOT_STICKY; }

        if (currentSession != null) {
            cancelSession(getString(R.string.error_session_replaced));
            currentSession = null;
        }

        List<Uri> packageUris = getUriListExtra(intent);
        Uri       pitUri      = getUriExtra(intent);
        boolean   autoReboot  = intent.getBooleanExtra(EXTRA_AUTO_REBOOT,
                getStore().getDefaultAutoReboot());

        // ── منح الأذونات للـ URIs ──────────────────────────────────────────
        if (packageUris != null) {
            for (Uri uri : packageUris) {
                try {
                    grantUriPermission(getPackageName(), uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
            }
        }
        if (pitUri != null) {
            try {
                grantUriPermission(getPackageName(), pitUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}
        }

        if (packageUris == null || packageUris.isEmpty()) {
            getStore().logError("No firmware files selected");
            getStore().setFinished(false, "No files");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // ── Thor-style: التحقق من الصيغ قبل أي شيء ────────────────────────
        for (Uri uri : packageUris) {
            String name = resolveFileName(uri);
            if (!isSupportedFormat(name)) {
                String err = "Unsupported file: " + name
                        + "\nOnly .tar and .tar.md5 files are supported";
                getStore().logError(err);
                getStore().setFinished(false, err);
                cleanupForeground();
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        }

        // ── USB check ──────────────────────────────────────────────────────
        UsbManager usbManager = (UsbManager) getSystemService(USB_SERVICE);
        List<UsbDevice> devices = UsbDeviceHelper.getReadyDevices(usbManager);

        getStore().appendLog("> FlashService — USB check");
        if (usbManager != null) {
            Map<String, UsbDevice> allDev = usbManager.getDeviceList();
            getStore().appendLog(">   Total USB devices: " + (allDev != null ? allDev.size() : 0));
            if (allDev != null) {
                for (UsbDevice d : allDev.values()) {
                    String dName = d.getProductName();
                    if (dName == null || dName.isEmpty()) dName = "Samsung";
                    getStore().appendLog(">   " + dName
                            + " VID=" + d.getVendorId()
                            + " perm=" + usbManager.hasPermission(d));
                }
            }
        }
        getStore().appendLog(">   Ready devices: " + devices.size());

        if (devices.isEmpty()) {
            String err = getString(R.string.error_no_ready_devices);
            getStore().logError(err);
            getStore().setFinished(false, err);
            cleanupForeground();
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        foregroundType = Build.VERSION.SDK_INT >= 29 ? 16 : 1;
        if (!promoteToForeground(buildNotification("Preparing flash…"), true)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        acquireWakeLock();

        final int       capturedId   = startId;
        final List<Uri> capturedUris = packageUris;
        final Uri       capturedPit  = pitUri;
        final boolean   capturedAR   = autoReboot;

        Thread t = new Thread(() ->
                runFlashSession(capturedUris, capturedPit, capturedAR, capturedId),
                "eko-flash");
        t.setDaemon(true);
        currentSession = new FlashSession(t);
        t.start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (receiverRegistered) {
            try { unregisterReceiver(usbDetachReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        cancelSession(getString(R.string.error_session_replaced));
        cleanupForeground();
        releaseWakeLock();
        super.onDestroy();
    }

    // ── USB detach ────────────────────────────────────────────────────────────

    public void onUsbDeviceDetached(UsbDevice device) {
        if (currentSession == null) return;
        cancelSession("USB device detached: " + (device != null ? device.getDeviceName() : "?"));
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private void startWatchdog(EkoStore store) {
        lastProgressTime = System.currentTimeMillis();
        watchdogRunnable = new Runnable() {
            @Override public void run() {
                long idle = System.currentTimeMillis() - lastProgressTime;
                if (idle >= WATCHDOG_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog: session timeout after " + idle + "ms");
                    store.appendLog("> [WATCHDOG] Session timed out after "
                            + (idle / 1000) + "s — cancelling");
                    cancelSession("Session timeout — reconnect device and retry");
                    stopWatchdog();
                } else {
                    watchdogHandler.postDelayed(this, 5_000L);
                }
            }
        };
        watchdogHandler.postDelayed(watchdogRunnable, 5_000L);
    }

    private void stopWatchdog() {
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
    }

    private void pingWatchdog() {
        lastProgressTime = System.currentTimeMillis();
    }

    // ── Flash session core ────────────────────────────────────────────────────

    private void runFlashSession(List<Uri> uris, Uri pitUri,
                                 boolean autoReboot, int startId) {
        EkoStore store = getStore();
        startWatchdog(store);

        // take persistable permissions
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}
        }
        if (pitUri != null) {
            try {
                getContentResolver().takePersistableUriPermission(
                        pitUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}
        }

        List<ParcelFileDescriptor> pfds = new ArrayList<>();
        int[]    fileFds   = new int[0];
        String[] fileNames = new String[0];
        long[]   fileSizes = new long[0];

        try {
            store.appendLog("> EKO TURBO — SESSION START (Thor-style)");
            store.appendLog("> Supported formats: .tar / .tar.md5 only");
            store.appendLog("> Files: " + uris.size());

            fileFds   = new int[uris.size()];
            fileNames = new String[uris.size()];
            fileSizes = new long[uris.size()];

            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                try {
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd == null) throw new IOException("Cannot open: " + uri);
                    pfds.add(pfd);
                    fileFds[i]   = pfd.getFd();
                    fileNames[i] = resolveFileName(uri);
                    fileSizes[i] = pfd.getStatSize();
                    store.appendLog("> File[" + i + "]: " + fileNames[i] +
                            " (" + (fileSizes[i] / 1024 / 1024) + " MB) fd=" + fileFds[i]);

                    // تحقق نهائي من الصيغة
                    if (!isSupportedFormat(fileNames[i])) {
                        store.setFinished(false, "Rejected: " + fileNames[i]
                                + " — only .tar/.tar.md5 allowed");
                        return;
                    }
                } catch (SecurityException e) {
                    store.logError("Permission denied: " + resolveFileName(uri));
                    store.setFinished(false, "Permission denied — re-select file from within app");
                    return;
                } catch (Exception e) {
                    store.logError("Cannot open file " + uri + ": " + e.getMessage());
                    store.setFinished(false, "File open failed: " + e.getMessage());
                    return;
                }
            }

            // ── فتح PIT ───────────────────────────────────────────────────
            int  pitFd   = -1;
            long pitSize = 0;
            ParcelFileDescriptor pitPfd = null;

            if (pitUri != null) {
                try {
                    pitPfd = getContentResolver().openFileDescriptor(pitUri, "r");
                    if (pitPfd != null) {
                        pitFd   = pitPfd.getFd();
                        pitSize = pitPfd.getStatSize();
                        store.appendLog("> PIT: fd=" + pitFd + " size=" + pitSize + " bytes");
                    }
                } catch (Exception e) {
                    store.appendLog("> PIT open failed — using device PIT: " + e.getMessage());
                }
            }

            if (pitFd < 0) store.appendLog("> PIT: none — device PIT will be used");

            store.setStatus(getString(R.string.flash_status_flashing));

            // ── Thor-style: disable cdc_acm قبل الفلاش ────────────────────
            disableCdcAcm(store);

            runUsbSession(fileFds, fileNames, fileSizes, pitFd, pitSize, autoReboot, store);

            if (pitPfd != null) try { pitPfd.close(); } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(TAG, "Flash session error", t);
            String msg = t.getMessage();
            if (msg == null) msg = t.getClass().getSimpleName();
            store.logError("Session error: " + msg);
            store.setFinished(false, msg);
        } finally {
            stopWatchdog();
            for (ParcelFileDescriptor pfd : pfds) {
                try { pfd.close(); } catch (Throwable ignored) {}
            }
            cleanupForeground();
            releaseWakeLock();
            stopSelf(startId);
        }
    }

    // ── Thor-style: disable cdc_acm ──────────────────────────────────────────
    // Thor فعلها بنفس الطريقة: يحرر USB من kernel driver قبل Odin protocol
    private void disableCdcAcm(EkoStore store) {
        store.appendLog("> [Thor-style] Disabling cdc_acm kernel module…");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "modprobe -r cdc_acm 2>/dev/null; " +
                    "rmmod cdc_acm 2>/dev/null; " +
                    "echo done"
            });
            p.waitFor();
            store.appendLog("> cdc_acm disabled (or not loaded — OK)");
        } catch (Exception e) {
            // مش مشكلة لو فشل — USB Host API بيتعامل مع الجهاز مباشرة
            store.appendLog("> cdc_acm disable skipped: " + e.getMessage());
        }
    }

    // ── USB flash ─────────────────────────────────────────────────────────────

    private void runUsbSession(int[] fileFds, String[] fileNames, long[] fileSizes,
                               int pitFd, long pitSize, boolean autoReboot,
                               EkoStore store) throws Throwable {

        UsbManager usbManager = (UsbManager) getSystemService(USB_SERVICE);
        List<UsbDevice> devices = UsbDeviceHelper.getReadyDevices(usbManager);

        if (devices.isEmpty()) {
            store.setFinished(false, "No authorized USB device");
            return;
        }

        UsbDevice dev = devices.get(0);

        RootManager rootManager = store.isRootEnabled()
                ? RootManager.getInstance(this)
                : null;

        EkoUsbTransport transport = EkoUsbTransport_Root.openWithRoot(
                usbManager, dev, rootManager, store);

        if (transport == null) {
            boolean hasPerm = (usbManager != null) && usbManager.hasPermission(dev);
            String err = "Cannot open USB device [perm=" + hasPerm + "]"
                    + " — try reconnecting or toggle Root mode";
            store.appendLog("> ERROR: " + err);
            store.appendLog(">   ↳ Make sure device is in Download Mode and USB cable is stable");
            store.setFinished(false, err);
            return;
        }

        store.appendLog("> USB fd=" + transport.nativeFd +
                " epIn=0x"  + Integer.toHexString(transport.bulkInAddress) +
                " epOut=0x" + Integer.toHexString(transport.bulkOutAddress));
        store.setDeviceLabel(dev.getProductName() != null ? dev.getProductName() : "Samsung Device");
        updateNotification("Connected: " + dev.getProductName());

        EkoNativeBridge          bridge    = new EkoNativeBridge(getCacheDir());
        NativeFlashCallbacks     callbacks = buildCallbacks(store);
        EkoNativeBridge.CancelFlag cancelFlag = new EkoNativeBridge.CancelFlag();

        List<EkoUsbTransport> transportList = new ArrayList<>();
        transportList.add(transport);

        store.appendLog("> Starting native flash session with " + fileFds.length + " file(s)…");
        bridge.runSession(
                transportList,
                fileFds,
                fileNames,
                fileSizes,
                pitFd,
                pitSize,
                getStore().isNandErase(),
                autoReboot,
                callbacks,
                cancelFlag);
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    private NativeFlashCallbacks buildCallbacks(EkoStore store) {
        return new NativeFlashCallbacks() {
            @Override public void onDeviceError(int code, String msg) {
                store.logError("Device error " + code + ": " + msg);
            }
            @Override public void onDevices(String[] d) {}
            @Override public void onError(String msg) {
                store.logError(msg);
                updateNotification("Error: " + msg);
            }
            @Override public void onFinished(boolean ok, String msg) {
                store.setFinished(ok, msg);
                String n = ok ? getString(R.string.flash_status_done)
                              : getString(R.string.flash_status_failed) + ": " + msg;
                updateNotification(n);
            }
            @Override public void onItemActive(int i) {}
            @Override public void onItemDone(int i) {}
            @Override public void onLog(int lvl, String msg) {
                store.appendLog("> " + msg);
            }
            @Override public void onModel(String model) {
                store.setDeviceLabel(model);
                updateNotification("Flashing: " + model);
            }
            @Override public void onPlanItem(int i,int a,int b,int c,
                                              String s1,String s2,String s3,long sz) {}
            @Override public void onPlanReady(int count, long total) {
                store.appendLog("> Plan ready: " + count + " partitions, "
                        + (total / 1024 / 1024) + " MB total");
            }
            @Override public void onProgress(long done, long total, long speed, long elapsed) {
                pingWatchdog();
                int pct = total > 0 ? (int)(done * 100L / total) : 0;
                store.setProgress(0, pct, 100);
                long mbps = speed / 1024 / 1024;
                updateNotification("Flashing " + pct + "% — " + mbps + " MB/s");
            }
            @Override public void onStage(String stage) {
                pingWatchdog();
                store.setStatus(stage);
                store.appendLog("> Stage: " + stage);
            }
        };
    }

    // ── Format validation (Thor-style) ────────────────────────────────────────

    /**
     * يقبل فقط .tar و .tar.md5 — كما في Thor's string[30]:
     * "Invalid extension! Valid: '.tar' and '.tar.md5'"
     */
    private static boolean isSupportedFormat(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        for (String ext : VALID_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveFileName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {}
        String path = uri.getPath();
        if (path != null) {
            int cut = path.lastIndexOf('/');
            if (cut >= 0) return path.substring(cut + 1);
            return path;
        }
        return "firmware.tar.md5";
    }

    private void cancelSession(String reason) {
        if (getStore().isSessionActive()) {
            getStore().setFinished(false, reason);
        }
    }

    private boolean promoteToForeground(Notification n, boolean first) {
        if (n == null || stopping) return false;
        if (foregroundType == null) foregroundType = 1;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, foregroundType);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, foregroundType);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foregroundActive = true;
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "startForeground connectedDevice failed, fallback: " + e.getMessage());
            foregroundType = 1;
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, foregroundType);
                else                             startForeground(NOTIF_ID, n);
                foregroundActive = true;
                return true;
            } catch (RuntimeException e2) {
                Log.e(TAG, "startForeground fallback also failed", e2);
                if (first) { cleanupForeground(); stopSelf(); }
                return false;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "startForeground failed", e);
            if (first) { cleanupForeground(); stopSelf(); }
            return false;
        }
    }

    private void updateNotification(String text) {
        if (!foregroundActive) return;
        Notification n = buildNotification(text);
        if (n == null) return;
        try { getSystemService(NotificationManager.class).notify(NOTIF_ID, n); }
        catch (Throwable ignored) {}
    }

    private void cleanupForeground() {
        foregroundActive = false;
        foregroundType   = null;
        try { stopForeground(true); }  catch (Throwable ignored) {}
        try { getSystemService(NotificationManager.class).cancel(NOTIF_ID); }
        catch (Throwable ignored) {}
    }

    private Notification buildNotification(String text) {
        try {
            PendingIntent tap = PendingIntent.getActivity(this, 0,
                    new Intent(this, EkoCore.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE);

            PendingIntent cancelPi = PendingIntent.getService(this, 2,
                    new Intent(this, FlashService.class).setAction(ACTION_CANCEL_FLASH),
                    PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) builder = new Notification.Builder(this, CHANNEL_ID);
            else                             builder = new Notification.Builder(this);

            builder.setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(text)
                    .setContentIntent(tap)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi);

            Notification n = builder.build();
            n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
            return n;
        } catch (Throwable t) {
            Log.e(TAG, "buildNotification failed", t);
            return null;
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eko:flash");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        wakeLock = null;
    }

    @SuppressWarnings("unchecked")
    private List<Uri> getUriListExtra(Intent intent) {
        try {
            return Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableArrayListExtra(EXTRA_PACKAGE_URIS, Uri.class)
                    : intent.getParcelableArrayListExtra(EXTRA_PACKAGE_URIS);
        } catch (Throwable ignored) { return new ArrayList<>(); }
    }

    private Uri getUriExtra(Intent intent) {
        try {
            return Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(EXTRA_PIT_URI, Uri.class)
                    : intent.getParcelableExtra(EXTRA_PIT_URI);
        } catch (Throwable ignored) { return null; }
    }

    private EkoStore getStore() {
        return ((EkoApplication) getApplication()).getStore();
    }

    private static final class FlashSession {
        final Thread thread;
        FlashSession(Thread t) { this.thread = t; }
    }
}
