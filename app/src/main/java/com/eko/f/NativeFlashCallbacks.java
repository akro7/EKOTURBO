package com.eko.f;

public interface NativeFlashCallbacks {
    void onDeviceError(int errorCode, String message);
    void onDevices(String[] devices);
    void onError(String message);
    void onFinished(boolean success, String message);
    void onItemActive(int index);
    void onItemDone(int index);
    void onLog(int level, String message);
    void onModel(String model);
    void onPlanItem(int index, int a, int b, int c, String str1, String str2, String str3, long size);
    void onPlanReady(int count, long totalSize);
    void onProgress(long done, long total, long speed, long elapsed);
    void onStage(String stage);
}
