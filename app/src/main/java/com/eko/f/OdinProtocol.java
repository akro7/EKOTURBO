package com.eko.f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.Adler32;

/**
 * OdinProtocol V6.0 - Ultra Stream Architecture
 * النسخة المطورة لضمان توافق كامل مع بروتوكول LOKE الحديث وتجنب أخطاء الاستجابة.
 */
public class OdinProtocol {
    
    // الثوابت الأساسية للبروتوكول
    public static final int CHUNK_SIZE = 131072; // 128KB لنقل البيانات الخام
    public static final int PACKET_SIZE = 1024;  // حجم حزم التحكم الثابت
    
    // التوقيعات الرسمية (Magic Bytes)
    private static final byte[] MAGIC_LOKE = "LOKE".getBytes(); 
    private static final byte[] MAGIC_EOF  = "EOF_".getBytes();
    private static final byte[] MAGIC_ODIN = "OdIn".getBytes(); 

    /**
     * حزمة المصافحة (Handshake) - تم تحسينها لزيادة التوافق
     */
    public static byte[] createHandshakePacket() {
        ByteBuffer buffer = ByteBuffer.allocate(PACKET_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // بروتوكول سامسونج يتوقع "OdIn" في بداية حزمة التهيئة
        buffer.put(MAGIC_ODIN);
        buffer.position(4);
        buffer.putInt(0); // Query Device Info
        
        return buffer.array();
    }

    /**
     * إنشاء حزمة بدء التشغيل (Session Start) لفتح قناة الكتابة
     */
    public static byte[] createStartPacket(String partName, long fileSize) {
        ByteBuffer buffer = ByteBuffer.allocate(PACKET_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN); 

        buffer.put(MAGIC_LOKE); 
        buffer.position(8);
        buffer.putInt(1); // Flag: SESSION_START

        buffer.position(12);
        buffer.putLong(fileSize);

        buffer.position(20);
        buffer.put(padPartitionName(partName));

        // حساب وحقن التوقيع الرقمي Adler32
        injectChecksum(buffer);
        return buffer.array();
    }

    /**
     * أمر مسح الـ NAND - ضروري لبعض عمليات التفليش النظيف
     */
    public static byte[] createNandErasePacket() {
        ByteBuffer buffer = ByteBuffer.allocate(PACKET_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        buffer.put(MAGIC_LOKE);
        buffer.position(8);
        buffer.putInt(3); // Flag: NAND_ERASE
        
        injectChecksum(buffer);
        return buffer.array();
    }

    /**
     * حزمة إنهاء الجلسة (Session End) لإغلاق الملف وتأكيد النجاح
     */
    public static byte[] createEndPacket(String partName) {
        ByteBuffer buffer = ByteBuffer.allocate(PACKET_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        buffer.put(MAGIC_LOKE);
        buffer.position(8);
        buffer.putInt(2); // Flag: SESSION_END
        
        buffer.position(20);
        buffer.put(padPartitionName(partName));

        // إضافة توقيع نهاية الملف في الأوفست 504
        buffer.position(504); 
        buffer.put(MAGIC_EOF);
        
        injectChecksum(buffer);
        return buffer.array();
    }

    /**
     * أمر إعادة التشغيل (System Reboot) - يرسل بعد انتهاء كامل العمليات
     */
    public static byte[] createRebootCommand() {
        ByteBuffer buffer = ByteBuffer.allocate(PACKET_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        buffer.put(MAGIC_LOKE);
        buffer.position(8);
        buffer.putInt(4); // Flag: REBOOT_DEVICE
        
        // حقن الـ Checksum لضمان قبول الجهاز للأمر
        injectChecksum(buffer);
        return buffer.array();
    }

    /**
     * معالجة اسم القسم: يقوم بتنظيف الاسم وتعبئته ليكون 32 بايت
     * مثال: "AP_G998B_XX.tar" -> "AP"
     */
    private static byte[] padPartitionName(String name) {
        byte[] padded = new byte[32];
        if (name != null) {
            // استخراج الجزء الأساسي فقط (مثل BL, AP, CP, CSC)
            String cleanName = name.split("_")[0]
                                   .split("\\.")[0]
                                   .replace("btn", "") // تنظيف التاجات البرمجية
                                   .toUpperCase();
                                   
            byte[] nameBytes = cleanName.getBytes();
            System.arraycopy(nameBytes, 0, padded, 0, Math.min(nameBytes.length, 32));
        }
        return padded;
    }

    /**
     * حقن الـ Checksum Adler32 في الأوفست 508 لضمان سلامة حزمة التحكم
     */
    private static void injectChecksum(ByteBuffer buffer) {
        // يتم حساب التشيك سوم لأول 508 بايت من الحزمة
        long checksum = calculateChecksum(buffer.array(), 0, 508);
        buffer.position(508);
        buffer.putInt((int) checksum);
    }

    private static long calculateChecksum(byte[] data, int offset, int length) {
        Adler32 adler = new Adler32();
        adler.update(data, offset, length);
        return adler.getValue();
    }
}
