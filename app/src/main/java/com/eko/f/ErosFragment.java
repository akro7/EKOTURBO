package com.eko.f;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;

public class ErosFragment extends Fragment {

    // ── Views ──
    private TextView       tvStatus;
    private TextView       tvDeviceInfo;
    private TextView       tvBattery;
    private ProgressBar    progressBar;
    private MaterialButton btnCancel;
    private MaterialButton btnBL, btnAP, btnCP, btnCSC, btnUserdata;
    private MaterialButton btnStartFlash;

    private volatile boolean flashInProgress = false;

    // ── Battery receiver ──
    private BroadcastReceiver batteryReceiver;

    // ── File URIs ──
    private Uri uriBL, uriAP, uriCP, uriCSC, uriUserdata;
    private String currentPickingType = "";

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> multiPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) handleSingleFile(uri);
                    }
                });

        multiPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        // ملف واحد
                        if (result.getData().getData() != null) {
                            autoAssign(result.getData().getData());
                        }
                        // ملفات متعددة
                        ClipData clip = result.getData().getClipData();
                        if (clip != null) {
                            for (int i = 0; i < clip.getItemCount(); i++)
                                autoAssign(clip.getItemAt(i).getUri());
                        }
                        checkFlashReady();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_eros, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvStatus      = view.findViewById(R.id.tv_status);
        tvDeviceInfo  = view.findViewById(R.id.tv_device_info);
        tvBattery     = view.findViewById(R.id.tv_battery);
        progressBar   = view.findViewById(R.id.progress_bar);
        btnCancel     = view.findViewById(R.id.btn_cancel);
        btnBL         = view.findViewById(R.id.btn_bl);
        btnAP         = view.findViewById(R.id.btn_ap);
        btnCP         = view.findViewById(R.id.btn_cp);
        btnCSC        = view.findViewById(R.id.btn_csc);
        btnUserdata   = view.findViewById(R.id.btn_userdata);
        btnStartFlash = view.findViewById(R.id.btn_flash);

        if (btnBL       != null) btnBL.setOnClickListener(v       -> openPicker("BL"));
        if (btnAP       != null) btnAP.setOnClickListener(v       -> openPicker("AP"));
        if (btnCP       != null) btnCP.setOnClickListener(v       -> openPicker("CP"));
        if (btnCSC      != null) btnCSC.setOnClickListener(v      -> openPicker("CSC"));
        if (btnUserdata != null) btnUserdata.setOnClickListener(v -> openPicker("USERDATA"));
        if (btnStartFlash != null) btnStartFlash.setOnClickListener(v -> startFlash());
        if (btnCancel   != null) btnCancel.setOnClickListener(v   -> cancelFlash());

        // ── Battery receiver ──
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (tvBattery == null || level < 0 || scale <= 0) return;
                int pct = (int)(100f * level / scale);
                tvBattery.setText("BAT: " + pct + "%");
                if (pct <= 15)      tvBattery.setTextColor(0xFFFF5555);  // red
                else if (pct <= 30) tvBattery.setTextColor(0xFFFFB86C);  // orange
                else                tvBattery.setTextColor(0xFF6272A4);  // normal
            }
        };
        try {
            requireContext().registerReceiver(batteryReceiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) {}

        // ── EKO TURBO gradient: cyan → purple ──
        TextView tvEkoTitle = view.findViewById(R.id.tv_eko_title);
        if (tvEkoTitle != null) {
            tvEkoTitle.post(() -> {
                float w = tvEkoTitle.getWidth();
                if (w > 0) {
                    LinearGradient grad = new LinearGradient(0, 0, w, 0,
                            new int[]{0xFF00E5FF, 0xFFBD93F9},
                            null, Shader.TileMode.CLAMP);
                    tvEkoTitle.getPaint().setShader(grad);
                    tvEkoTitle.invalidate();
                }
            });
        }

        observeStore();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (batteryReceiver != null) {
            try { requireContext().unregisterReceiver(batteryReceiver); } catch (Throwable ignored) {}
            batteryReceiver = null;
        }
        tvBattery = null;
    }

    // ── يُستدعى من EkoCore بعد منح إذن USB ──────────────────────────────────
    public void onUsbPermissionGranted(UsbDevice device) {
        if (!isAdded() || getView() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (tvDeviceInfo != null)
                tvDeviceInfo.setText(device.getProductName() != null
                        ? device.getProductName() : "DEVICE");
            if (tvStatus != null)
                tvStatus.setText("الجهاز متصل وجاهز للتفليش ✓");
            checkFlashReady();
        });
    }

    // ── Store observation ─────────────────────────────────────────────────────

    private void observeStore() {
        EkoStore store = getStore();
        if (store == null) return;

        store.observeStatus(requireActivity(), s -> {
            if (tvStatus != null) tvStatus.setText(s != null ? s : "");
        });
        store.observeProgress(requireActivity(), pct -> {
            if (progressBar != null) {
                progressBar.setProgress(pct);
                progressBar.setVisibility(View.VISIBLE);
            }
        });
        store.observeDeviceLabel(requireActivity(), label -> {
            if (tvDeviceInfo != null && label != null && !label.isEmpty())
                tvDeviceInfo.setText(label);
        });
        // إعادة تفعيل الزر بمجرد انتهاء الجلسة (نجاح أو فشل)
        store.observeFinished(requireActivity(), (ok, msg) -> {
            flashInProgress = false;
            boolean hasFiles = (uriBL!=null||uriAP!=null||uriCP!=null||uriCSC!=null||uriUserdata!=null);
            if (btnStartFlash != null) btnStartFlash.setEnabled(hasFiles);
            if (btnCancel != null) btnCancel.setVisibility(View.GONE);
        });
    }

    // ── File pickers ──────────────────────────────────────────────────────────

    private void openMultiPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        multiPickerLauncher.launch(intent);
    }

    private void openPicker(String type) {
        currentPickingType = type;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    // توزيع تلقائي بحسب اسم الملف
    private void autoAssign(Uri uri) {
        if (uri == null) return;
        grantPerm(uri);
        String name = getFileName(uri).toUpperCase();
        if      (name.startsWith("BL_") || name.contains("_BL_") || name.contains("BOOTLOADER")) assignBL(uri);
        else if (name.startsWith("AP_") || name.contains("_AP_")  || name.contains("SYSTEM"))     assignAP(uri);
        else if (name.startsWith("CP_") || name.contains("_CP_")  || name.contains("BASEBAND"))   assignCP(uri);
        else if (name.startsWith("CSC") || name.contains("CSC"))                                   assignCSC(uri);
        else if (name.contains("USERDATA") || name.contains("USER"))                               assignUserdata(uri);
        else assignAP(uri); // افتراضي
    }

    private void handleSingleFile(Uri uri) {
        grantPerm(uri);
        String fn = getFileName(uri);
        switch (currentPickingType) {
            case "BL":       assignBL(uri);       break;
            case "AP":       assignAP(uri);       break;
            case "CP":       assignCP(uri);       break;
            case "CSC":      assignCSC(uri);      break;
            case "USERDATA": assignUserdata(uri); break;
        }
        checkFlashReady();
    }

    private void assignBL(Uri u)  { uriBL = u;  if(btnBL!=null)       btnBL.setText("BL : "+getFileName(u)); }
    private void assignAP(Uri u)  { uriAP = u;  if(btnAP!=null)       btnAP.setText("AP : "+getFileName(u)); }
    private void assignCP(Uri u)  { uriCP = u;  if(btnCP!=null)       btnCP.setText("CP : "+getFileName(u)); }
    private void assignCSC(Uri u) { uriCSC = u; if(btnCSC!=null)      btnCSC.setText("CSC : "+getFileName(u)); }
    private void assignUserdata(Uri u) { uriUserdata = u; if(btnUserdata!=null) btnUserdata.setText("USERDATA : "+getFileName(u)); }

    private void checkFlashReady() {
        boolean has = (uriBL!=null||uriAP!=null||uriCP!=null||uriCSC!=null||uriUserdata!=null);
        if (btnStartFlash != null) btnStartFlash.setEnabled(has);

        if (has && tvStatus != null) {
            EkoStore store = getStore();
            if (store != null && !store.isWirelessEnabled()) {
                UsbManager mgr = (UsbManager) requireContext().getSystemService(Context.USB_SERVICE);
                UsbDevice dev  = UsbDeviceHelper.getConnectedSamsungDevice(mgr);
                if (dev == null)
                    tvStatus.setText("ملف جاهز — وصّل الجهاز في Download Mode");
                else if (!mgr.hasPermission(dev))
                    tvStatus.setText("الجهاز متصل — بانتظار صلاحية USB");
                else
                    tvStatus.setText("الجهاز متصل وجاهز للتفليش ✓");
            }
        }
    }

    // ── Flash / Cancel ────────────────────────────────────────────────────────

    private void startFlash() {
        // ── منع التشغيل المزدوج ──────────────────────────────────────────────
        if (flashInProgress) {
            Toast.makeText(getContext(), "Flash already in progress…", Toast.LENGTH_SHORT).show();
            return;
        }
        flashInProgress = true;
        if (btnStartFlash != null) btnStartFlash.setEnabled(false);
        ArrayList<Uri> list = new ArrayList<>();
        if (uriBL       != null) list.add(uriBL);
        if (uriAP       != null) list.add(uriAP);
        if (uriCP       != null) list.add(uriCP);
        if (uriCSC      != null) list.add(uriCSC);
        if (uriUserdata != null) list.add(uriUserdata);

        if (list.isEmpty()) {
            flashInProgress = false;
            Toast.makeText(getContext(), getString(R.string.error_no_files_selected), Toast.LENGTH_SHORT).show();
            return;
        }

        EkoStore store = getStore();
        if (store == null) return;

        store.appendLog("> START FLASH — " + list.size() + " file(s)");

        if (!store.isWirelessEnabled()) {
            UsbManager mgr = (UsbManager) requireContext().getSystemService(Context.USB_SERVICE);
            List<UsbDevice> devices = UsbDeviceHelper.getReadyDevices(mgr);

            if (mgr != null) {
                Map<String, UsbDevice> allDevices = mgr.getDeviceList();
                store.appendLog("> USB devices found: " + (allDevices != null ? allDevices.size() : 0));
                if (allDevices != null) {
                    for (UsbDevice d : allDevices.values()) {
                        boolean hasPerm = mgr.hasPermission(d);
                        store.appendLog(">   " + d.getProductName()
                                + " [VID=" + d.getVendorId() + "]"
                                + " perm=" + hasPerm);
                    }
                }
            }
            store.appendLog("> Ready devices: " + devices.size());

            if (devices.isEmpty()) {
                flashInProgress = false;
                store.appendLog("> [ERROR] No device with USB permission found");
                Toast.makeText(getContext(),
                        "وصّل الجهاز في Download Mode ومنح صلاحية USB",
                        Toast.LENGTH_LONG).show();
                if (tvStatus != null) tvStatus.setText("لا يوجد جهاز — تأكد من Download Mode والإذن");
                return;
            }
        }

        // ── نسخ الملفات لـ cache لضمان صلاحية الـ Service عليها ──────────────
        store.appendLog("> Copying files to secure cache…");
        File cacheDir = new File(requireContext().getCacheDir(), "eko_flash");
        cacheDir.mkdirs();

        // نحذف أي ملفات قديمة من الـ cache
        File[] old = cacheDir.listFiles();
        if (old != null) for (File f : old) f.delete();

        ArrayList<Uri> cachedUris = new ArrayList<>();
        for (Uri src : list) {
            Uri cached = copyUriToCache(src, cacheDir, store);
            if (cached == null) return; // copyUriToCache طبعت الـ error بالفعل
            cachedUris.add(cached);
        }

        // نسخ ملف PIT لو موجود
        Uri pitSrc    = store.getPitUri();
        Uri pitCached = null;
        if (pitSrc != null) {
            File pitCacheDir = new File(requireContext().getCacheDir(), "eko_pit");
            pitCacheDir.mkdirs();
            File[] oldPit = pitCacheDir.listFiles();
            if (oldPit != null) for (File f : oldPit) f.delete();

            try (InputStream in = requireContext().getContentResolver().openInputStream(pitSrc)) {
                if (in != null) {
                    String pitName = getFileName(pitSrc);
                    if (pitName == null || pitName.isEmpty()) pitName = "device.pit";
                    File dest = new File(pitCacheDir, pitName);
                    try (OutputStream out = new FileOutputStream(dest)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    pitCached = android.net.Uri.fromFile(dest);
                    store.appendLog(">   PIT cached: " + pitName);
                }
            } catch (SecurityException e) {
                // الـ PIT URI القديم انتهت صلاحيته — نمسحه ونطلب إعادة الاختيار
                store.clearPitUri();
                store.appendLog("> [!] PIT permission expired — cleared. Go to OPT → re-select PIT file.");
                requireActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(),
                        "⚠ PIT permission expired — go to OPT and re-select the PIT file",
                        Toast.LENGTH_LONG).show());
                // نكمل بدون PIT
            } catch (Exception e) {
                store.appendLog("> PIT copy failed — flashing without PIT: " + e.getMessage());
            }
        }

        store.appendLog("> Launching FlashService…");
        store.setPackageUris(cachedUris);

        Intent intent = new Intent(requireContext(), FlashService.class);
        intent.setAction(FlashService.ACTION_START_FLASH);
        intent.putParcelableArrayListExtra(FlashService.EXTRA_PACKAGE_URIS, cachedUris);
        intent.putExtra(FlashService.EXTRA_AUTO_REBOOT, store.isAutoReboot());
        intent.putExtra(FlashService.EXTRA_CONNECTION_MODE,
                store.isWirelessEnabled() ? "wireless" : "usb");
        if (pitCached != null)
            intent.putExtra(FlashService.EXTRA_PIT_URI, pitCached);

        // الـ cache URIs هي file:// — لا تحتاج permissions إضافية
        // لكن نضيفها احتياطاً للـ content:// URIs لو وجدت
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                requireContext().startForegroundService(intent);
            else
                requireContext().startService(intent);

            store.appendLog("> FlashService started ✓");
            if (tvStatus != null) tvStatus.setText("⟳ جاري التفليش…");
            if (btnCancel != null) btnCancel.setVisibility(View.VISIBLE);
            // flashInProgress يتفك لما الـ service تنتهي عبر observeStore
        } catch (Throwable e) {
            flashInProgress = false;
            if (btnStartFlash != null) btnStartFlash.setEnabled(true);
            store.appendLog("> [ERROR] Failed to start FlashService: " + e.getMessage());
            Toast.makeText(getContext(), "خطأ في بدء التفليش: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * ينسخ URI إلى cache ويرجع file:// URI للنسخة المحلية.
     * يضمن إمكانية وصول الـ Service بدون أي URI permissions.
     */
    private Uri copyUriToCache(Uri src, File destDir, EkoStore store) {
        String name = getFileName(src);
        if (name == null || name.isEmpty()) name = "file_" + System.currentTimeMillis();
        File dest = new File(destDir, name);
        try (InputStream in  = requireContext().getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new Exception("Cannot open input stream for: " + src);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            store.appendLog(">   Cached: " + name + " (" + (dest.length() / 1024 / 1024) + " MB)");
            return android.net.Uri.fromFile(dest);
        } catch (SecurityException e) {
            store.logError("Permission denied: " + name
                    + "\nاختر الملف من داخل التطبيق مباشرة");
            store.setFinished(false, "Permission denied for: " + name);
            return null;
        } catch (Exception e) {
            store.logError("Failed to cache file " + name + ": " + e.getMessage());
            store.setFinished(false, "File cache failed: " + e.getMessage());
            return null;
        }
    }

    private void cancelFlash() {
        // إرسال cancel للـ service
        Intent cancel = new Intent(requireContext(), FlashService.class);
        cancel.setAction(FlashService.ACTION_CANCEL_FLASH);
        requireContext().startService(cancel);

        EkoStore store = getStore();
        if (store != null) store.cancelSession();
        if (tvStatus != null) tvStatus.setText(getString(R.string.status_idle));
        if (btnStartFlash != null) {
            boolean hasFiles = (uriBL!=null||uriAP!=null||uriCP!=null||uriCSC!=null||uriUserdata!=null);
            btnStartFlash.setEnabled(hasFiles);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void grantPerm(Uri uri) {
        try {
            requireContext().getContentResolver()
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
    }

    private String getFileName(Uri uri) {
        String r = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = requireContext().getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx != -1) r = c.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (r == null) {
            r = uri.getPath();
            int cut = r != null ? r.lastIndexOf('/') : -1;
            if (cut != -1) r = r.substring(cut + 1);
        }
        return r != null ? r : "Unknown";
    }

    private EkoStore getStore() {
        if (getActivity() == null) return null;
        return ((EkoApplication) requireActivity().getApplication()).getStore();
    }
}
