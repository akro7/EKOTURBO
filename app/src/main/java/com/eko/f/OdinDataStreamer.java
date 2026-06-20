package com.eko.f;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;
import java.io.InputStream;

/**
 * OdinDataStreamer V5 - Extreme Turbo Stream Core
 * محرك دفع البيانات المطور لدعم السرعات العالية واستقرار البث.
 */
public class OdinDataStreamer {
    private static final String TAG = "OdinDataStreamer";
    private final UsbDeviceConnection connection;
    private final UsbEndpoint outEndpoint;

    // مهلة زمنية ذكية: 60 ثانية للبيانات الثقيلة
    private static final int STREAM_TIMEOUT = 60000;
    // عدد محاولات إعادة الإرسال في حال حدوث Timeout بسيط
    private static final int MAX_RETRIES = 3;

    public OdinDataStreamer(UsbDeviceConnection conn, UsbEndpoint out) {
        this.connection = conn;
        this.outEndpoint = out;
    }

    /**
     * يقوم ببث الملف مع نظام مراقبة ذكي للسرعة والأخطاء
     */
    public void streamFile(InputStream inputStream, long totalSize, DataProgressListener listener) throws Exception {
        if (inputStream == null || connection == null || outEndpoint == null) {
            throw new Exception("STREAM_INIT_FAILED: Essential components are null");
        }

        // حجم الحزمة المثالي لبروتوكول سامسونج (128 كيلوبايت)
        byte[] buffer = new byte[OdinProtocol.CHUNK_SIZE];
        int bytesRead;
        long totalSent = 0;
        int retryCount = 0;

        try {
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                
                // إرسال البيانات مع نظام إعادة المحاولة الذكي
                int result = connection.bulkTransfer(outEndpoint, buffer, bytesRead, STREAM_TIMEOUT);
                
                if (result < 0) {
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        Log.w(TAG, "Transfer lag detected. Retry " + retryCount + "/" + MAX_RETRIES);
                        Thread.sleep(100); // وقت راحة بسيط للمعالج قبل إعادة المحاولة
                        
                        // إعادة محاولة إرسال نفس الحزمة
                        result = connection.bulkTransfer(outEndpoint, buffer, bytesRead, STREAM_TIMEOUT);
                        if (result < 0) throw new Exception("HARDWARE_TIMEOUT: Device stopped responding.");
                    } else {
                        throw new Exception("STREAM_CRITICAL_FAILURE: Connection lost at " + totalSent);
                    }
                }

                totalSent += bytesRead;
                retryCount = 0; // تصغير العداد عند النجاح

                // تحديث الواجهة (UI)
                if (listener != null && totalSize > 0) {
                    // حساب النسبة المئوية بدقة
                    final int progress = (int) ((totalSent * 100) / totalSize);
                    final long currentSent = totalSent;
                    listener.onProgressUpdate(progress, currentSent);
                }
            }
            
            // التأكد من إرسال حزمة صفرية (ZLP) إذا كان الملف ينتهي بمضاعفات حجم الحزمة
            // بعض إصدارات Bootloader تتطلب ذلك لإنهاء العملية بنجاح
            if (totalSize % outEndpoint.getMaxPacketSize() == 0) {
                connection.bulkTransfer(outEndpoint, new byte[0], 0, 1000);
            }

        } catch (Exception e) {
            Log.e(TAG, "Streaming Error: " + e.getMessage());
            throw e;
        }
    }

    /**
     * واجهة مراقبة التقدم - تربط المحرك بالواجهة الرسومية (UI)
     */
    public interface DataProgressListener {
        /**
         * @param progress النسبة المئوية (0-100)
         * @param bytesSent إجمالي البايتات المرسلة لحساب السرعة
         */
        void onProgressUpdate(int progress, long bytesSent);
    }
}
