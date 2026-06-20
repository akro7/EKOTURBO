package com.eko.f;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;
import java.io.InputStream;

/**
 * OdinHandler V7.0 - Power-Aware Edition
 * مصمم لتجاوز قيود حماية الطاقة في سامسونج وتجنب خطأ Hardware not responding.
 */
public class OdinHandler {
    private static final String TAG = "EKO_ODIN_CORE";
    private final UsbDeviceConnection connection;
    private final UsbEndpoint out;
    private final UsbEndpoint in;

    private static final int CMD_PACKET_SIZE = 1024; 
    private static final int DATA_CHUNK_SIZE = 131072; 
    private static final int TIMEOUT_MS = 120000; // زيادة المهلة لـ 120 ثانية لضمان استقرار الكتابة

    public OdinHandler(UsbDeviceConnection connection, UsbEndpoint out, UsbEndpoint in) {
        this.connection = connection;
        this.out = out;
        this.in = in;
    }

    /**
     * تنفيذ مصافحة مع بروتوكول تحفيز المنفذ (ZLP Burst)
     */
    public boolean doHandshake() {
        try {
            Log.d(TAG, "LOKE V7: Initiating power-aware handshake...");
            
            // 1. تنظيف عميق للقنوات
            clearEndpoints();
            
            // 2. إرسال نبضات تحفيز (ZLP) لإيقاظ الـ USB Controller في الهاتف
            // هامة جداً عندما تكون البطارية منخفضة (7%-13%)
            for (int i = 0; i < 5; i++) {
                connection.bulkTransfer(out, new byte[0], 0, 50);
            }
            Thread.sleep(500); 

            byte[] initCmd = OdinProtocol.createHandshakePacket();
            byte[] response = new byte[CMD_PACKET_SIZE];
            
            // 3. دورة محاولات مع مهلة انتظار ممتدة (Extended Timeout)
            for (int i = 1; i <= 3; i++) {
                Log.d(TAG, "LOKE Handshake: Attempt " + i + "/3 with Power Stimulation");
                
                int sent = connection.bulkTransfer(out, initCmd, CMD_PACKET_SIZE, 5000);
                if (sent >= 0) {
                    // انتظار الرد بمهلة أطول (7 ثوانٍ) لمنح المعالج وقتاً للاستجابة
                    int read = connection.bulkTransfer(in, response, response.length, 7000);
                    if (read > 0) {
                        String respStr = new String(response, 0, read).trim();
                        if (respStr.toLowerCase().contains("loke") || respStr.toLowerCase().contains("odin") || read >= 4) {
                            Log.d(TAG, "Turbo Link Established Successfully.");
                            return true;
                        }
                    }
                }
                clearEndpoints();
                Thread.sleep(1000); // زيادة فترة الراحة بين المحاولات
            }
            
            return sendRawCommand("HELO");
            
        } catch (Exception e) {
            Log.e(TAG, "Critical Handshake Failure: " + e.getMessage());
            return false;
        }
    }

    /**
     * محرك دفع البيانات المطور بآلية التحقق من الاستجابة
     */
    public boolean flashStream(InputStream inputStream, String partName, long fileSize, ProgressListener listener) {
        try {
            byte[] startPacket = OdinProtocol.createStartPacket(partName, fileSize);
            if (!sendCustomPacket(startPacket)) {
                Log.e(TAG, "LOKE: Failed to open session for " + partName);
                return false;
            }

            byte[] buffer = new byte[DATA_CHUNK_SIZE];
            long totalBytesSent = 0;
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                // استخدام مهلة TIMEOUT_MS الممتدة
                int result = connection.bulkTransfer(out, buffer, bytesRead, TIMEOUT_MS);
                if (result < 0) {
                    Log.e(TAG, "Transfer Interrupted at " + totalBytesSent + " bytes");
                    return false;
                }

                totalBytesSent += bytesRead;
                if (listener != null && fileSize > 0) {
                    listener.onProgress((int) ((totalBytesSent * 100) / fileSize));
                }
            }

            byte[] endPacket = OdinProtocol.createEndPacket(partName);
            return sendCustomPacket(endPacket);

        } catch (Exception e) {
            Log.e(TAG, "Stream Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * إرسال حزم مخصصة مع انتظار ACK إلزامي
     */
    private boolean sendCustomPacket(byte[] packet) {
        byte[] fixedPacket = new byte[CMD_PACKET_SIZE];
        System.arraycopy(packet, 0, fixedPacket, 0, Math.min(packet.length, CMD_PACKET_SIZE));
        
        // مهلة 15 ثانية لأوامر التحكم (بدء/نهاية) لأنها تتطلب كتابة على الـ Flash
        int result = connection.bulkTransfer(out, fixedPacket, CMD_PACKET_SIZE, 15000);
        return result >= 0 && waitForACK();
    }

    private boolean waitForACK() {
        byte[] ackBuffer = new byte[CMD_PACKET_SIZE];
        // مهلة انتظار ACK طويلة لتناسب حالة البطارية الضعيفة
        int read = connection.bulkTransfer(in, ackBuffer, ackBuffer.length, 10000);
        return read >= 0; 
    }

    /**
     * إعادة ضبط صلبة (Standard USB Reset)
     */
    public void clearEndpoints() {
        if (connection != null) {
            try {
                // Clear Stall conditions
                connection.controlTransfer(0x02, 0x01, 0x00, out.getAddress(), null, 0, 200);
                connection.controlTransfer(0x02, 0x01, 0x00, in.getAddress(), null, 0, 200);
                
                byte[] trash = new byte[CMD_PACKET_SIZE];
                while (connection.bulkTransfer(in, trash, trash.length, 100) > 0) {
                    // Drain buffer
                }
            } catch (Exception e) {
                Log.e(TAG, "Clear Error: " + e.getMessage());
            }
        }
    }

    public boolean sendRawCommand(String cmd) {
        if (connection == null || out == null) return false;
        byte[] commandPacket = new byte[CMD_PACKET_SIZE];
        byte[] cmdBytes = cmd.getBytes();
        System.arraycopy(cmdBytes, 0, commandPacket, 0, Math.min(cmdBytes.length, CMD_PACKET_SIZE));
        
        int res = connection.bulkTransfer(out, commandPacket, CMD_PACKET_SIZE, 5000);
        return res >= 0 && waitForACK();
    }

    public interface ProgressListener {
        void onProgress(int progress);
    }
}
