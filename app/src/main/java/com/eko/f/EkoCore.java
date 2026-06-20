package com.eko.f;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.Locale;

public final class EkoCore extends AppCompatActivity {

    private static final String TAG             = "EkoCore";
    private static final String KEY_SELECTED_TAB = "selectedTabId";
    private static final String KEY_UPDATE_SHOWN = "updatePromptShown";

    private MaterialToolbar      toolbar;
    private FrameLayout          bottomNavContainer;
    private BottomNavigationView bottomNav;

    // USB overlay
    private View                          usbOverlayRoot;
    private android.widget.TextView       usbOverlayDeviceName;
    private android.hardware.usb.UsbDevice pendingUsbDevice; // الجهاز المنتظر إذن
    private static final String ACTION_USB_PERMISSION = "com.eko.f.USB_PERMISSION";

    // BroadcastReceiver لاستقبال نتيجة requestPermission
    private final android.content.BroadcastReceiver usbPermissionReceiver =
            new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;

            android.hardware.usb.UsbDevice device;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(
                        android.hardware.usb.UsbManager.EXTRA_DEVICE,
                        android.hardware.usb.UsbDevice.class);
            } else {
                //noinspection deprecation
                device = intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
            }

            boolean granted = intent.getBooleanExtra(
                    android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false);

            Log.d(TAG, "USB permission result: granted=" + granted +
                    " device=" + (device != null ? device.getProductName() : "null"));

            // سجّل في الـ log دايماً
            try {
                EkoStore store = ((EkoApplication) getApplication()).getStore();
                if (store != null) {
                    store.appendLog(granted
                            ? "> USB PERMISSION GRANTED: " + (device != null ? device.getProductName() : "device")
                            : "> USB PERMISSION DENIED");
                }
            } catch (Throwable ignored) {}

            if (granted && device != null) {
                // أبلغ ErosFragment بالجهاز
                if (erosFragment != null) {
                    erosFragment.onUsbPermissionGranted(device);
                }
            } else if (!granted) {
                Toast.makeText(EkoCore.this,
                        "USB permission denied — tap Allow to flash",
                        Toast.LENGTH_LONG).show();
            }
            pendingUsbDevice = null;
        }
    };

    // ── إضافة جميع الـ Fragments ──
    private ErosFragment    erosFragment;
    private OptFragment     optFragment;
    private ConsoleFragment consoleFragment;
    private DevFragment     devFragment;
    private MusicFragment   musicFragment;

    private int      selectedTabId;
    private Fragment activeFragment;
    private boolean  updatePromptShown = false;

    // ── Language — محمي من NPE إذا DataHelper لم يكن جاهزاً ──────────────────

    @Override
    public void attachBaseContext(Context context) {
        if (context == null) throw new NullPointerException("context == null");

        String lang = "en";
        try {
            DataHelper dataHelper = DataHelper.INSTANCE;
            String saved = dataHelper.getString(DataHelper.APP_LANGUAGE_KEY, null);
            if (saved != null && !saved.isEmpty()) lang = saved;
        } catch (Throwable e) {
            Log.w(TAG, "DataHelper not ready in attachBaseContext: " + e.getMessage());
        }

        try {
            Locale locale = Locale.forLanguageTag(lang);
            Configuration config = context.getResources().getConfiguration();
            config.setLocale(locale);
            super.attachBaseContext(context.createConfigurationContext(config));
        } catch (Throwable e) {
            Log.e(TAG, "attachBaseContext locale error: " + e.getMessage());
            super.attachBaseContext(context);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        // FLAG_KEEP_SCREEN_ON
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.eros_main);

        toolbar            = findViewById(R.id.toolbar);
        bottomNavContainer = findViewById(R.id.bottom_navigation_container);

        // ── USB Floating Overlay setup ─────────────────────────────────────
        // المشكلة كانت: الـ include بيرجع ViewGroup بـ id=usb_overlay
        // لكن الـ root الفعلي اللي فيه الـ children هو usb_overlay_root
        // الحل: ابحث عن usb_overlay_root مباشرة في الـ layout
        usbOverlayRoot = findViewById(R.id.usb_overlay_root);
        if (usbOverlayRoot == null) {
            // fallback: جرب عبر الـ include wrapper
            View includeWrapper = findViewById(R.id.usb_overlay);
            if (includeWrapper != null) {
                usbOverlayRoot = includeWrapper.findViewById(R.id.usb_overlay_root);
                if (usbOverlayRoot == null) usbOverlayRoot = includeWrapper;
            }
        }

        usbOverlayDeviceName = usbOverlayRoot != null
                ? usbOverlayRoot.findViewById(R.id.usb_overlay_device_name) : null;

        if (usbOverlayRoot != null) {
            android.widget.CheckBox cbRemember =
                    usbOverlayRoot.findViewById(R.id.usb_overlay_remember);

            usbOverlayRoot.findViewById(R.id.usb_overlay_deny).setOnClickListener(v -> {
                usbOverlayRoot.setVisibility(View.GONE);
                pendingUsbDevice = null;
            });

            usbOverlayRoot.findViewById(R.id.usb_overlay_allow).setOnClickListener(v -> {
                usbOverlayRoot.setVisibility(View.GONE);

                android.hardware.usb.UsbDevice dev = pendingUsbDevice;
                if (dev == null) return;

                boolean alwaysAllow = cbRemember != null && cbRemember.isChecked();

                android.hardware.usb.UsbManager usbMgr =
                        (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);

                // لو الإذن ممنوح أصلاً — أبلغ Fragment مباشرة
                if (usbMgr.hasPermission(dev)) {
                    if (erosFragment != null) erosFragment.onUsbPermissionGranted(dev);
                    pendingUsbDevice = null;
                    return;
                }

                // اطلب الإذن — alwaysAllow يحفظه Android تلقائياً عبر FLAG_UPDATE_CURRENT
                android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                        this, 0,
                        new Intent(ACTION_USB_PERMISSION),
                        android.app.PendingIntent.FLAG_MUTABLE |
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                usbMgr.requestPermission(dev, pi);

                if (alwaysAllow) {
                    // حفظ VendorId عشان المرة الجاية نعطي إذن تلقائي
                    getSharedPreferences("eko_usb_prefs", MODE_PRIVATE)
                            .edit()
                            .putBoolean("always_allow_vid_" + dev.getVendorId(), true)
                            .apply();
                }
            });
        }

        // تسجيل الـ receiver لاستقبال نتيجة requestPermission
        android.content.IntentFilter permFilter =
                new android.content.IntentFilter(ACTION_USB_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permFilter,
                    android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, permFilter);
        }

        // Handle USB_DEVICE_ATTACHED from intent (cold start)
        handleUsbIntent(getIntent());

        // guard: التأكد إن الـ container موجود في الـ layout
        if (bottomNavContainer == null) {
            Log.e(TAG, "bottom_navigation_container not found in eros_main.xml — check your layout");
            finish();
            return;
        }

        // Build BottomNavigationView
        bottomNav = new BottomNavigationView(this);
        bottomNav.setId(View.generateViewId());
        bottomNav.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        bottomNav.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bottomNav.setBackgroundColor(0xFF050508);

        // Active indicator — cyan glow pill
        bottomNav.setItemActiveIndicatorColor(
                android.content.res.ColorStateList.valueOf(0x2200E5FF));

        bottomNav.inflateMenu(R.menu.bottom_nav_menu);
        bottomNav.setItemRippleColor(null);
        bottomNav.setLabelVisibilityMode(1);

        // Icon colors — cyan when active, muted when not
        android.content.res.ColorStateList iconColors = new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{0xFF00E5FF, 0xFF2E3A55}
        );
        bottomNav.setItemIconTintList(iconColors);

        // Text colors — cyan when active, muted when not
        android.content.res.ColorStateList textColors = new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{0xFF00E5FF, 0xFF2E3A55}
        );
        bottomNav.setItemTextColor(textColors);

        bottomNavContainer.addView(bottomNav);

        // عنوان التطبيق
        String versionName = "unknown";
        try {
            versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {}
        String buildType = (getApplicationInfo().flags & 2) != 0 ? "dbg" : "rel";
        String title = getString(R.string.main_title) + " " + versionName + " (" + buildType + ")";
        setTitle(title);
        if (toolbar != null) {
            toolbar.setTitle(title);
            toolbar.setTitleTextColor(0xFF050507); // dark text on cyan background
            // في tab Eko الافتراضي، نخفي الـ toolbar (fragment له header خاص)
            toolbar.setVisibility(View.GONE);
        }

        FragmentManager fm = getSupportFragmentManager();

        if (savedState == null) {
            // إنشاء Fragments جديدة
            erosFragment    = new ErosFragment();
            optFragment     = new OptFragment();
            consoleFragment = new ConsoleFragment();
            devFragment     = new DevFragment();
            musicFragment   = new MusicFragment();

            FragmentTransaction ft = fm.beginTransaction();
            ft.add(R.id.fragment_host_container, erosFragment,    "EROS_FRAGMENT_TAG").hide(erosFragment);
            ft.add(R.id.fragment_host_container, optFragment,     "OPT_FRAGMENT_TAG").hide(optFragment);
            ft.add(R.id.fragment_host_container, consoleFragment, "CONSOLE_FRAGMENT_TAG").hide(consoleFragment);
            ft.add(R.id.fragment_host_container, devFragment,     "DEV_FRAGMENT_TAG").hide(devFragment);
            ft.add(R.id.fragment_host_container, musicFragment,   "MUSIC_FRAGMENT_TAG").hide(musicFragment);
            ft.show(erosFragment);
            ft.commitNow();

            selectedTabId  = R.id.nav_eros;
            activeFragment = erosFragment;

        } else {
            // استعادة Fragments
            erosFragment    = (ErosFragment)    fm.findFragmentByTag("EROS_FRAGMENT_TAG");
            optFragment     = (OptFragment)     fm.findFragmentByTag("OPT_FRAGMENT_TAG");
            consoleFragment = (ConsoleFragment) fm.findFragmentByTag("CONSOLE_FRAGMENT_TAG");
            devFragment     = (DevFragment)     fm.findFragmentByTag("DEV_FRAGMENT_TAG");
            musicFragment   = (MusicFragment)   fm.findFragmentByTag("MUSIC_FRAGMENT_TAG");

            // إذا فقد FragmentManager الـ fragments، أعد إنشاءها
            if (erosFragment == null || optFragment == null || consoleFragment == null || devFragment == null || musicFragment == null) {
                Log.w(TAG, "Fragments lost after restore — recreating");
                erosFragment    = erosFragment    != null ? erosFragment    : new ErosFragment();
                optFragment     = optFragment     != null ? optFragment     : new OptFragment();
                consoleFragment = consoleFragment != null ? consoleFragment : new ConsoleFragment();
                devFragment     = devFragment     != null ? devFragment     : new DevFragment();
                musicFragment   = musicFragment   != null ? musicFragment   : new MusicFragment();

                FragmentTransaction ft = fm.beginTransaction();
                if (fm.findFragmentByTag("EROS_FRAGMENT_TAG") == null)
                    ft.add(R.id.fragment_host_container, erosFragment, "EROS_FRAGMENT_TAG").hide(erosFragment);
                if (fm.findFragmentByTag("OPT_FRAGMENT_TAG") == null)
                    ft.add(R.id.fragment_host_container, optFragment, "OPT_FRAGMENT_TAG").hide(optFragment);
                if (fm.findFragmentByTag("CONSOLE_FRAGMENT_TAG") == null)
                    ft.add(R.id.fragment_host_container, consoleFragment, "CONSOLE_FRAGMENT_TAG").hide(consoleFragment);
                if (fm.findFragmentByTag("DEV_FRAGMENT_TAG") == null)
                    ft.add(R.id.fragment_host_container, devFragment, "DEV_FRAGMENT_TAG").hide(devFragment);
                if (fm.findFragmentByTag("MUSIC_FRAGMENT_TAG") == null)
                    ft.add(R.id.fragment_host_container, musicFragment, "MUSIC_FRAGMENT_TAG").hide(musicFragment);
                ft.show(erosFragment);
                ft.commitNow();

                selectedTabId  = R.id.nav_eros;
                activeFragment = erosFragment;
            } else {
                selectedTabId     = savedState.getInt(KEY_SELECTED_TAB, R.id.nav_eros);
                updatePromptShown = savedState.getBoolean(KEY_UPDATE_SHOWN, false);
                activeFragment    = fragmentForTab(selectedTabId);
            }
        }

        bottomNav.setSelectedItemId(selectedTabId);
        bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
    }

    // ── USB Device Attach Handling ────────────────────────────────────────────

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleUsbIntent(intent);
    }

    private void handleUsbIntent(Intent intent) {
        if (intent == null) return;
        if (!android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) return;

        android.hardware.usb.UsbDevice device;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            device = intent.getParcelableExtra(
                    android.hardware.usb.UsbManager.EXTRA_DEVICE,
                    android.hardware.usb.UsbDevice.class);
        } else {
            //noinspection deprecation
            device = intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
        }

        if (device == null) return;

        pendingUsbDevice = device;

        android.hardware.usb.UsbManager usbMgr =
                (android.hardware.usb.UsbManager) getSystemService(USB_SERVICE);

        // لو الإذن ممنوح بالفعل — connect مباشرة
        if (usbMgr != null && usbMgr.hasPermission(device)) {
            if (erosFragment != null) erosFragment.onUsbPermissionGranted(device);
            pendingUsbDevice = null;
            return;
        }

        // تحقق من "Always allow" المحفوظ
        boolean alwaysSaved = getSharedPreferences("eko_usb_prefs", MODE_PRIVATE)
                .getBoolean("always_allow_vid_" + device.getVendorId(), false);
        if (alwaysSaved && usbMgr != null) {
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    this, 0,
                    new Intent(ACTION_USB_PERMISSION),
                    android.app.PendingIntent.FLAG_MUTABLE |
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            usbMgr.requestPermission(device, pi);
            return;
        }

        // أظهر الـ custom overlay
        if (usbOverlayRoot == null) return;
        String name = device.getProductName();
        if (name == null || name.isEmpty()) name = device.getManufacturerName();
        if (name == null || name.isEmpty()) name = "USB Device";
        if (usbOverlayDeviceName != null) usbOverlayDeviceName.setText(name);

        // reset checkbox
        android.widget.CheckBox cb = usbOverlayRoot.findViewById(R.id.usb_overlay_remember);
        if (cb != null) cb.setChecked(false);

        usbOverlayRoot.setVisibility(View.VISIBLE);
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        out.putInt(KEY_SELECTED_TAB, selectedTabId);
        out.putBoolean(KEY_UPDATE_SHOWN, updatePromptShown);
        super.onSaveInstanceState(out);
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(usbPermissionReceiver); } catch (Throwable ignored) {}
        if (isFinishing() && !isChangingConfigurations()) {
            try {
                if (getApplication() instanceof EkoApplication) {
                    EkoStore store = ((EkoApplication) getApplication()).getStore();
                    if (store != null) store.cancelSession();
                } else {
                    Log.w(TAG, "Application is not EkoApplication — cannot cancel session");
                }
            } catch (Throwable e) {
                Log.e(TAG, "onDestroy cancelSession error: " + e.getMessage());
            }
        }
        super.onDestroy();
    }

    // ── Tab navigation ────────────────────────────────────────────────────────

    private Fragment fragmentForTab(int tabId) {
        // ── تعريف جميع الواجهات بشكل صريح ──
        if (tabId == R.id.nav_eros && erosFragment != null)   return erosFragment;
        if (tabId == R.id.nav_opt && optFragment != null)     return optFragment;
        if (tabId == R.id.nav_log && consoleFragment != null) return consoleFragment;
        if (tabId == R.id.nav_dev && devFragment != null)     return devFragment;
        if (tabId == R.id.nav_music && musicFragment != null) return musicFragment;
        
        return erosFragment != null ? erosFragment : new ErosFragment();
    }

    private void showTab(int tabId) {
        Fragment target = fragmentForTab(tabId);
        if (target == null || activeFragment == target) return;

        try {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.setCustomAnimations(
                    R.anim.fade_in,  R.anim.fade_out,
                    R.anim.fade_in,  R.anim.fade_out);
            if (activeFragment != null) ft.hide(activeFragment);
            ft.show(target);
            ft.commitNow();
            activeFragment = target;
            selectedTabId  = tabId;

            // إخفاء الـ Toolbar في شاشة Eko (لها header خاص)
            // وإظهاره في باقي الشاشات
            if (toolbar != null) {
                if (tabId == R.id.nav_eros) {
                    toolbar.setVisibility(View.GONE);
                } else {
                    toolbar.setVisibility(View.VISIBLE);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "showTab error: " + e.getMessage());
        }
    }

    // ── Update dialog ─────────────────────────────────────────────────────────

    public void showUpdateDialog(String url, String versionName, boolean forceUpdate) {
        if (isFinishing() || isDestroyed() || updatePromptShown) return;

        Intent browserIntent = null;
        boolean resolvable   = false;
        try {
            browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            resolvable    = browserIntent.resolveActivity(getPackageManager()) != null;
        } catch (Throwable ignored) {}

        boolean mustUpdate = forceUpdate && resolvable;
        if (forceUpdate && !resolvable) {
            Log.e(TAG, "Force-update URL not resolvable: '" + url + "'. Treating as advisory.");
        }

        String dialogTitle = mustUpdate
                ? getString(R.string.critical_update)
                : getString(R.string.optional_update);
        String dialogMsg = mustUpdate
                ? getString(R.string.critical_update_desc, versionName)
                : getString(R.string.optional_update_desc, versionName);

        final Intent finalIntent    = browserIntent;
        final boolean[] dismissed   = {false};

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(dialogTitle)
                .setMessage(dialogMsg)
                .setCancelable(!mustUpdate)
                .setPositiveButton(R.string.update_now, (dialog, which) -> {
                    dismissed[0] = true;
                    boolean opened = false;
                    if (finalIntent != null) {
                        try {
                            startActivity(finalIntent);
                            opened = true;
                        } catch (Throwable ignored) {}
                    }
                    if (!opened) {
                        Log.e(TAG, "Could not open update URL: '" + url + "'");
                        Toast.makeText(this, R.string.browser_error, Toast.LENGTH_LONG).show();
                    }
                    if (mustUpdate) {
                        if (opened)
                            Toast.makeText(this, R.string.install_and_restart, Toast.LENGTH_LONG).show();
                        finishAffinity();
                    }
                });

        if (!mustUpdate) {
            builder.setNegativeButton(R.string.later, null);
        }

        if (mustUpdate) {
            builder.setOnDismissListener(dialog -> {
                if (!dismissed[0] && !isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, R.string.update_mandatory, Toast.LENGTH_LONG).show();
                    finishAffinity();
                }
            });
        }

        updatePromptShown = true;
        builder.show();
    }
}
