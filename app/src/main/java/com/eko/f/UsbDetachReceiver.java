package com.eko.f; // تأكد أن حرف p صغير في كلمة package

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

public class UsbDetachReceiver extends BroadcastReceiver {

    private static final String TAG = "UsbDetachReceiver";

    // قمنا بإزالة final لأننا سنحتاج لتعيينها لاحقاً أو التعامل معها إذا كانت null
    private FlashService service;

    /**
     * 1. المنشئ الافتراضي (ضروري جداً لمنع الانهيار عند التشغيل)
     * أندرويد يستخدم هذا المنشئ عندما يتم استدعاء الـ Receiver من الـ Manifest
     */
    public UsbDetachReceiver() {
        this.service = null;
    }

    /**
     * 2. منشئ مخصص (إذا كنت تقوم بتسجيل الـ Receiver يدوياً من داخل الـ Service)
     */
    public UsbDetachReceiver(FlashService service) {
        this.service = service;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) return;

        UsbDevice device;
        try {
            device = android.os.Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                    : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read USB device from intent", t);
            return;
        }

        String deviceName = (device != null) ? device.getDeviceName() : "unknown";
        Log.i(TAG, "USB device detached: " + deviceName);

        // تأكد من أن الـ service ليس null قبل استدعاء الوظائف منه
        if (service != null) {
            try {
                service.onUsbDeviceDetached(device);
            } catch (Throwable t) {
                Log.e(TAG, "Error handling USB detach", t);
            }
        } else {
            // إذا كان الـ service ملغياً، يمكننا محاولة إرسال Intent للـ Service ليقوم بالتنظيف
            Log.w(TAG, "Service is null, broadcasting intent to service instead.");
            Intent serviceIntent = new Intent(context, FlashService.class);
            serviceIntent.setAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            serviceIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
            context.startService(serviceIntent);
        }
    }
}
