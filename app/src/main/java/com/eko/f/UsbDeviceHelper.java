package com.eko.f;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Utility to find USB devices that are attached and have been granted permission.
 */
public final class UsbDeviceHelper {

    // مُعرف شركة سامسونج (Vendor ID = 0x04E8)
    public static final int SAMSUNG_VENDOR_ID = 1256; 

    private UsbDeviceHelper() {}

    /**
     * Returns a list of USB devices that are currently connected AND
     * have been granted permission by the user (ready to use).
     *
     * @param usbManager The UsbManager system service; may be null.
     * @return Non-null, possibly empty list of ready devices.
     */
    public static List<UsbDevice> getReadyDevices(UsbManager usbManager) {
        if (usbManager == null) return Collections.emptyList();

        Map<String, UsbDevice> deviceMap;
        try {
            deviceMap = usbManager.getDeviceList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }

        if (deviceMap == null || deviceMap.isEmpty()) return Collections.emptyList();

        List<UsbDevice> ready = new ArrayList<>();
        for (UsbDevice device : deviceMap.values()) {
            try {
                if (usbManager.hasPermission(device)) {
                    ready.add(device);
                }
            } catch (Throwable ignored) {}
        }
        return ready;
    }

    /**
     * Returns true if at least one ready (permitted) USB device is attached.
     */
    public static boolean hasReadyDevice(UsbManager usbManager) {
        return !getReadyDevices(usbManager).isEmpty();
    }

    /**
     * دالة جديدة لجلب جهاز سامسونج المتصل سواء كان يمتلك صلاحية أم لا
     * (مفيدة لطلب الصلاحية إذا لم تكن موجودة)
     */
    public static UsbDevice getConnectedSamsungDevice(UsbManager usbManager) {
        if (usbManager == null) return null;
        try {
            Map<String, UsbDevice> deviceMap = usbManager.getDeviceList();
            if (deviceMap != null) {
                for (UsbDevice device : deviceMap.values()) {
                    if (device.getVendorId() == SAMSUNG_VENDOR_ID) {
                        return device;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
