package com.eko.f;

import android.util.Log;
import java.io.File;
import java.util.List;

/**
 * EkoNativeBridge — الجسر بين Java و C++ (USB-only)
 *
 * التغييرات الجذرية:
 *  - إزالة runWirelessSession بالكامل (لا TCP، لا wireless)
 *  - إزالة nativeRunWirelessSession
 *  - USB-only: فقط .tar و .tar.md5 (التحقق في C++)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
public final class EkoNativeBridge {

    private static final String TAG = "EkoNativeBridge";

    public static final EkoNativeBridge.Companion Companion = new EkoNativeBridge.Companion();

    private static boolean libraryLoaded = false;

    private final String cachePath;

    static {
        try {
            System.loadLibrary("turbo_engine");
            libraryLoaded = true;
            Log.i(TAG, "libturbo_engine.so loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            libraryLoaded = false;
            Log.e(TAG, "Failed to load libturbo_engine.so: " + e.getMessage());
        }
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    public EkoNativeBridge(File file) {
        file.getClass();
        this.cachePath = file.getAbsolutePath();
    }

    // ── Native declarations (USB-only) ───────────────────────────────────────

    private native long nativeCreateSession(NativeFlashCallbacks callbacks, String cachePath);
    private native void nativeDestroySession(long sessionPtr);
    public static native void nativeRequestCancel(long sessionPtr);

    /**
     * USB flash — يقبل فقط .tar و .tar.md5
     * التحقق من الصيغة يتم في C++ أيضاً ويرفع خطأ صريح عند أي صيغة أخرى
     */
    private native void nativeRunSession(long sessionPtr,
                                         EkoUsbTransport[] transports,
                                         int[] fileFds,
                                         String[] fileNames,
                                         long[] fileSizes,
                                         int pitFd,
                                         long pitSize,
                                         boolean nandErase,
                                         boolean rebootAfter);

    // ── runSession (USB-only) ─────────────────────────────────────────────────

    public final void runSession(List<EkoUsbTransport> transportList,
                                 int[] fileFds,
                                 String[] fileNames,
                                 long[] fileSizes,
                                 int pitFd,
                                 long pitSize,
                                 boolean nandErase,
                                 boolean rebootAfter,
                                 NativeFlashCallbacks callbacks,
                                 CancelFlag cancelFlag) throws Throwable {

        if (!libraryLoaded) {
            Log.e(TAG, "runSession: libturbo_engine.so not loaded");
            throw new IllegalStateException("Native library not loaded");
        }

        transportList.getClass();
        fileFds.getClass();
        fileNames.getClass();
        fileSizes.getClass();
        callbacks.getClass();
        cancelFlag.getClass();
        this.cachePath.getClass();

        long sessionPtr;
        try {
            sessionPtr = nativeCreateSession(callbacks, this.cachePath);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeCreateSession not found: " + e.getMessage());
            throw e;
        }

        if (sessionPtr == 0L) {
            throw new RuntimeException("nativeCreateSession returned null");
        }

        cancelFlag.setSessionPtr(sessionPtr);

        try {
            EkoUsbTransport[] arr = transportList.toArray(new EkoUsbTransport[0]);
            nativeRunSession(sessionPtr, arr,
                    fileFds, fileNames, fileSizes,
                    pitFd, pitSize, nandErase, rebootAfter);
            nativeDestroySession(sessionPtr);
        } catch (Throwable th) {
            try { nativeDestroySession(sessionPtr); } catch (Throwable ignored) {}
            throw th;
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public static final class Companion {
        private Companion() {}
    }

    // ── CancelFlag ────────────────────────────────────────────────────────────

    public static final class CancelFlag {
        private long sessionPtr = 0L;

        public void setSessionPtr(long ptr) {
            this.sessionPtr = ptr;
        }

        public void cancel() {
            if (sessionPtr != 0L && libraryLoaded) {
                try {
                    EkoNativeBridge.nativeRequestCancel(sessionPtr);
                } catch (UnsatisfiedLinkError e) {
                    Log.e(TAG, "nativeRequestCancel not found: " + e.getMessage());
                }
            }
        }
    }
}
