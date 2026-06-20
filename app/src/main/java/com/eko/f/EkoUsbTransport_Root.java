package com.eko.f;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

/**
 * EkoUsbTransport_Root
 * ─────────────────────────────────────────────────────────────────────────────
 * نسخة من EkoUsbTransport تستخدم صلاحيات الروت للوصول لـ USB
 * بدون dialog أو permission request.
 *
 * الطريقة:
 *   - لو الجهاز عنده permission بالفعل → نستخدم standard mode مباشرة (الأسرع والأضمن)
 *   - لو مفيش permission + في روت → نجرب chmod 666 عبر su
 *   - لو مفيش permission + مفيش روت → نرجع null
 */
public final class EkoUsbTransport_Root {

    private static final String TAG = "EkoUsbRoot";

    /**
     * نقطة الدخول الرئيسية.
     *
     * FIX: إذا كان الجهاز عنده permission بالفعل، نتجاوز Root mode تماماً.
     * Root mode يسبب مشاكل عندما يحاول openDevice بدون Android permission system —
     * النتيجة connection غير مكتملة يفشل معها claimInterface.
     *
     * @param manager     UsbManager من الـ context
     * @param device      الجهاز المطلوب فتحه
     * @param rootManager RootManager.getInstance(ctx) أو null
     * @param store       EkoStore للـ logging (ممكن null)
     * @return EkoUsbTransport جاهز أو null لو فشل
     */
    public static EkoUsbTransport openWithRoot(
            UsbManager manager,
            UsbDevice device,
            RootManager rootManager,
            EkoStore store) {

        if (device == null || manager == null) return null;

        // ── FIX #1: لو عندنا permission بالفعل، استخدم standard mode مباشرة ──
        // Root mode بيعمل مشاكل لما نفتح connection بدون Android USB permission:
        // - claimInterface بترجع false حتى لو الـ endpoints موجودين
        // - الجهاز يبقى في bad state بعد كده ويمنع standard mode تشتغل
        if (manager.hasPermission(device)) {
            log(store, "> USB standard mode (permission already granted — skipping root)");
            EkoUsbTransport t = EkoUsbTransport.openDevice(manager, device);
            if (t != null) return t;

            // لو فشل (نادر)، جرب مرة تانية بعد delay
            log(store, "  openDevice failed on first try — retrying in 300ms...");
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            t = EkoUsbTransport.openDevice(manager, device);
            if (t != null) return t;

            log(store, "  ✗ openDevice failed even with permission — device may be busy");
            return null;
        }

        // ── مسار Root: لما مفيش permission (نادر مع device_filter.xml) ──────
        if (rootManager != null && rootManager.isRooted()) {
            log(store, "> USB Root mode [" + rootManager.getRootTypeName() + "]");
            EkoUsbTransport t = openViaRoot(manager, device, rootManager, store);
            if (t != null) {
                log(store, "✓ USB opened via root: " + t.getId());
                return t;
            }
            // FIX #2: delay بعد فشل Root قبل standard fallback
            log(store, "⚠ Root open failed — waiting 400ms before standard fallback...");
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        }

        // ── Fallback: standard mode (لازم permission) ─────────────────────────
        if (!manager.hasPermission(device)) {
            log(store, "✗ No USB permission and no root — connect device and grant permission");
            return null;
        }
        log(store, "> USB standard mode (fallback)");
        return EkoUsbTransport.openDevice(manager, device);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // الفتح عبر الروت (لما مفيش Android USB permission)
    // ─────────────────────────────────────────────────────────────────────────

    private static EkoUsbTransport openViaRoot(
            UsbManager manager,
            UsbDevice device,
            RootManager root,
            EkoStore store) {

        String devPath = device.getDeviceName();
        log(store, "  devPath = " + devPath);

        // ── خطوة 1: chmod بصلاحية روت ───────────────────────────────────────
        int chmodResult = root.execCommand("chmod 666 " + devPath);
        if (chmodResult != 0) {
            log(store, "  chmod failed (" + chmodResult + ") — trying chown instead");
            root.execCommand("chown " + android.os.Process.myUid() + " " + devPath);
        } else {
            log(store, "  chmod 666 OK");
        }

        // ── خطوة 2: delay عشان الكيرنيل يحدّث الـ permissions ──────────────
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        // ── خطوة 3: حاول openDevice ──────────────────────────────────────────
        UsbDeviceConnection connection = null;
        for (int attempt = 1; attempt <= 3 && connection == null; attempt++) {
            try {
                connection = manager.openDevice(device);
                if (connection == null && attempt < 3) {
                    log(store, "  openDevice returned null (attempt " + attempt + "/3) — retrying...");
                    Thread.sleep(200);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log(store, "  openDevice exception: " + e.getMessage());
                return null;
            }
        }

        if (connection == null) {
            log(store, "  openDevice returned null after 3 attempts — root path failed");
            return null;
        }

        // ── خطوة 4: Claim الـ interface ──────────────────────────────────────
        return claimInterface(device, connection, store);
    }

    /**
     * Claim الـ interface من connection موجودة.
     * FIX: تمييز بين "مفيش bulk endpoints" و "claimInterface فشل"
     */
    static EkoUsbTransport claimInterface(
            UsbDevice device,
            UsbDeviceConnection connection,
            EkoStore store) {

        int ifaceCount = device.getInterfaceCount();
        log(store, "  scanning " + ifaceCount + " interface(s) for bulk endpoints...");

        boolean foundEndpoints = false;

        for (int i = 0; i < ifaceCount; i++) {
            UsbInterface usbInterface = device.getInterface(i);
            UsbEndpoint epIn = null, epOut = null;

            for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                UsbEndpoint ep = usbInterface.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN)        epIn  = ep;
                    else if (ep.getDirection() == UsbConstants.USB_DIR_OUT)  epOut = ep;
                }
            }

            if (epIn != null && epOut != null) {
                foundEndpoints = true;
                // FIX: لو claimInterface فشل، اطبع رسالة واضحة (مش "No suitable interface")
                if (connection.claimInterface(usbInterface, true)) {
                    int nativeFd = connection.getFileDescriptor();
                    log(store, "  ✓ claimed interface " + i + ", nativeFd=" + nativeFd
                            + " epIn=0x" + Integer.toHexString(epIn.getAddress())
                            + " epOut=0x" + Integer.toHexString(epOut.getAddress()));
                    return new EkoUsbTransport(
                            device, connection, usbInterface, nativeFd,
                            i, epIn.getAddress(), epOut.getAddress(), null);
                } else {
                    // FIX: رسالة تشخيصية واضحة بدل "No suitable interface found"
                    log(store, "  ✗ claimInterface() returned false for interface " + i
                            + " (kernel driver conflict — try 'adb root' or reconnect)");
                }
            }
        }

        if (!foundEndpoints) {
            log(store, "  ✗ No bulk IN+OUT endpoints found in any interface");
        } else {
            log(store, "  ✗ Found endpoints but claimInterface failed on all interfaces");
        }

        connection.close();
        return null;
    }

    // ── Logger helper ─────────────────────────────────────────────────────────
    private static void log(EkoStore store, String msg) {
        Log.d(TAG, msg);
        if (store != null) store.appendLog(msg);
    }
}
