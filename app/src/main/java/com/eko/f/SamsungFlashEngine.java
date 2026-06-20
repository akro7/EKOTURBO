package com.eko.f;

import android.util.Log;

public class SamsungFlashEngine {

    private static final String TAG = "SamsungFlashEngine";

    // تحميل المكتبة (المحرك) عند تشغيل الكلاس
    static {
        try {
            System.loadLibrary("heimdall-engine");
            Log.i(TAG, "Heimdall Engine loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load heimdall-engine: " + e.getMessage());
        }
    }

    /**
     * الحصول على إصدار المحرك للتأكد من الربط
     */
    public native String getEngineVersion();

    /**
     * الكشف عن وجود جهاز سامسونج في وضع الـ Download Mode
     * @return true لو الجهاز متصل، false لو مش موجود
     */
    public native boolean detectDevice();

    /**
     * تفليش ملف معين على بارتيشن معين
     * @param filePath المسار الكامل للملف المستخرج (مثل system.img)
     * @param partitionName اسم البارتيشن (مثل SYSTEM أو BOOT)
     * @return 0 في حالة النجاح، وأرقام أخرى في حالة الخطأ
     */
    public native int flashPartition(String filePath, String partitionName);
}
