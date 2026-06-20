package com.eko.f;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

public final class EkoUsbTransport {
    public static final int TRANSPORT_KIND_USB = 0;

    public final int bulkInAddress;
    public final int bulkOutAddress;
    private final UsbInterface claimedInterface;
    private volatile boolean closed;
    private final UsbDeviceConnection connection;
    private final UsbDevice device;
    public final int interfaceNumber;
    public final int nativeFd;

    private EkoUsbTransport(UsbDevice device, UsbDeviceConnection connection,
                            UsbInterface claimedInterface, int nativeFd,
                            int interfaceNumber, int bulkInAddress, int bulkOutAddress) {
        this.device = device;
        this.connection = connection;
        this.claimedInterface = claimedInterface;
        this.nativeFd = nativeFd;
        this.interfaceNumber = interfaceNumber;
        this.bulkInAddress = bulkInAddress;
        this.bulkOutAddress = bulkOutAddress;
    }

    // Package-internal constructor (mirrors synthetic constructor pattern)
    public EkoUsbTransport(UsbDevice device, UsbDeviceConnection connection,
                           UsbInterface claimedInterface, int nativeFd,
                           int interfaceNumber, int bulkInAddress, int bulkOutAddress,
                           Object unused) {
        this(device, connection, claimedInterface, nativeFd, interfaceNumber, bulkInAddress, bulkOutAddress);
    }

    /**
     * الدالة الأهم: تقوم بفتح الجهاز، البحث عن واجهة التفليش، والاستحواذ عليها
     */
    public static EkoUsbTransport openDevice(UsbManager manager, UsbDevice device) {
        if (manager == null || device == null) return null;
        
        // التأكد من وجود صلاحية
        if (!manager.hasPermission(device)) return null;

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) return null;

        // البحث في جميع واجهات الجهاز عن واجهة نقل البيانات (Bulk Transfer)
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            UsbEndpoint epIn = null;
            UsbEndpoint epOut = null;

            // البحث عن نقاط الإرسال والاستقبال (Endpoints)
            for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                UsbEndpoint ep = usbInterface.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        epIn = ep;
                    } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        epOut = ep;
                    }
                }
            }

            // إذا وجدنا نقطتي الإرسال والاستقبال، نستحوذ على الواجهة
            if (epIn != null && epOut != null) {
                boolean claimed = connection.claimInterface(usbInterface, true);
                if (claimed) {
                    int nativeFd = connection.getFileDescriptor();
                    return new EkoUsbTransport(
                            device, 
                            connection, 
                            usbInterface, 
                            nativeFd,
                            i, 
                            epIn.getAddress(), 
                            epOut.getAddress()
                    );
                }
            }
        }
        
        // في حال فشل الاستحواذ على أي واجهة، نغلق الاتصال
        connection.close();
        return null;
    }

    public final void close() {
        this.closed = true;
    }

    public final void closeConnection() {
        this.closed = true;
        try {
            if (this.connection != null && this.claimedInterface != null) {
                this.connection.releaseInterface(this.claimedInterface);
            }
            if (this.connection != null) {
                this.connection.close();
            }
        } catch (Throwable ignored) {}
    }

    public final String getId() {
        String productName = this.device.getProductName();
        if (productName == null || productName.isEmpty()) {
            productName = "Samsung Device";
        }
        return productName + " (" + this.device.getDeviceName() + ")";
    }

    public final int getTransportKind() {
        return TRANSPORT_KIND_USB;
    }

    public final boolean isConnected() {
        return !this.closed;
    }
    
    public UsbDevice getDevice() {
        return device;
    }
}
