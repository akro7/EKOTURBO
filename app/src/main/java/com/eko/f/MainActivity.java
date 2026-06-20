package com.eko.f;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {

    private static final String TAG                    = "MainActivity";
    private static final int    REQUEST_POST_NOTIFICATIONS = 100;
    private static final String PREFS_NAME             = "com.eko.f.startup";
    private static final String KEY_WARNING_ACCEPTED   = "startup_warning_accepted";

    private AlertDialog currentDialog;
    private Toast       currentToast;

    // ═══════════════════════════════════════════════════════════
    // 1. ضبط اللغة — محمي من NPE إذا DataHelper لم يكن جاهزاً بعد
    // ═══════════════════════════════════════════════════════════

    @Override
    protected void attachBaseContext(Context newBase) {
        String langCode = null;

        try {
            langCode = DataHelper.INSTANCE.getString(DataHelper.APP_LANGUAGE_KEY, null);
        } catch (Throwable e) {
            Log.w(TAG, "DataHelper not ready in attachBaseContext: " + e.getMessage());
        }

        if (langCode == null || langCode.isEmpty()) {
            try {
                Locale sysLocale = LocaleList.getAdjustedDefault().get(0);
                String tag = sysLocale.toLanguageTag();
                String extracted = tag.contains("-") ? tag.substring(0, tag.indexOf("-")) : tag;

                boolean supported = false;
                try {
                    supported = !extracted.isEmpty()
                            && DataHelper.INSTANCE.getSupportedLanguages().containsKey(extracted);
                } catch (Throwable ignored) {}

                langCode = supported ? extracted : "en";
            } catch (Throwable e) {
                langCode = "en";
            }
        }

        try {
            Locale targetLocale = new Locale(langCode);
            Locale.setDefault(targetLocale);
            Configuration config = newBase.getResources().getConfiguration();
            config.setLocale(targetLocale);
            super.attachBaseContext(newBase.createConfigurationContext(config));
        } catch (Throwable e) {
            Log.e(TAG, "attachBaseContext locale error: " + e.getMessage());
            super.attachBaseContext(newBase);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 2. onCreate وربط واجهة المطورين (EKO Dev)
    // ═══════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ربط كروت المطورين من الـ XML (إن وجدت في هذه الواجهة) وتفعيل النقر
        View cardOmar = findViewById(R.id.card_dev_cat);
        View cardAhmed = findViewById(R.id.card_dev_kojo);

        if (cardOmar != null) {
            cardOmar.setOnClickListener(v -> openUrl("https://t.me/DevCatowa"));
        }

        if (cardAhmed != null) {
            cardAhmed.setOnClickListener(v -> openUrl("https://t.me/A_KOJO"));
        }

        startChecks();
    }

    // دالة لفتح روابط التيليجرام
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open URL: " + e.getMessage());
            showToast("حدث خطأ أثناء محاولة فتح الرابط");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 3. سلسلة الفحوصات
    // ═══════════════════════════════════════════════════════════

    private void startChecks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                showToast(getString(R.string.allow_battery_optimization));
                showBatteryOptimizationRequest();
                return;
            }
        } catch (Throwable e) {
            Log.e(TAG, "Battery optimization check failed: " + e.getMessage());
        }

        checkNotificationPermission();
    }

    // ═══════════════════════════════════════════════════════════
    // 4. Battery Optimization
    // ═══════════════════════════════════════════════════════════

    private void showBatteryOptimizationRequest() {
        if (isFinishing()) return;
        dismissDialog();

        Intent intent = new Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, 200);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open battery settings: " + e.getMessage());
            showToast(getString(R.string.allow_battery_optimization));
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 200) {
            startChecks();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 5. Notification Permission
    // ═══════════════════════════════════════════════════════════

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                boolean showSettings = ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.POST_NOTIFICATIONS);

                showNotificationPermissionDialog(showSettings);
                return;
            }
        }
        checkStartupWarning();
    }

    private void showNotificationPermissionDialog(boolean showSettingsOption) {
        if (isFinishing()) return;
        dismissDialog();

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.notification_permission_title))
                .setMessage(getString(R.string.notification_permission_message))
                .setCancelable(false);

        if (showSettingsOption) {
            builder.setPositiveButton(getString(R.string.notification_permission_settings),
                    (dialog, which) -> openNotificationSettings());
        } else {
            builder.setPositiveButton(getString(R.string.notification_permission_allow),
                    (dialog, which) -> ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            REQUEST_POST_NOTIFICATIONS));
        }

        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> finish());
        currentDialog = builder.show();
    }

    private void openNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName()));
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open notification settings: " + e.getMessage());
            showToast(getString(R.string.notification_settings_unavailable));
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startChecks();
            } else {
                boolean showSettings = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.POST_NOTIFICATIONS);
                showNotificationPermissionDialog(showSettings);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 6. Startup Warning
    // ═══════════════════════════════════════════════════════════

    private void checkStartupWarning() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean accepted = prefs.getBoolean(KEY_WARNING_ACCEPTED, false);

            if (!accepted) {
                showStartupWarningDialog();
            } else {
                proceedToEkoCore();
            }
        } catch (Throwable e) {
            Log.e(TAG, "checkStartupWarning error: " + e.getMessage());
            proceedToEkoCore(); // تابع حتى لو SharedPreferences فشل
        }
    }

    private void showStartupWarningDialog() {
        if (isFinishing()) return;
        dismissDialog();

        currentDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.startup_warning_title))
                .setMessage(getString(R.string.startup_warning_message))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.startup_warning_accept), (dialog, which) -> {
                    try {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                .edit()
                                .putBoolean(KEY_WARNING_ACCEPTED, true)
                                .apply();
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to save warning accepted: " + e.getMessage());
                    }
                    proceedToEkoCore();
                })
                .setNegativeButton(getString(R.string.startup_warning_exit),
                        (dialog, which) -> finish())
                .show();
    }

    // ═══════════════════════════════════════════════════════════
    // 7. الانتقال لـ EkoCore — محمي من أخطاء FlashService
    // ═══════════════════════════════════════════════════════════

    private void proceedToEkoCore() {
        if (isFinishing() || isDestroyed()) return;
        dismissDialog();

        // تشغيل FlashService مع معالجة الأخطاء
        try {
            Intent flashIntent = new Intent(this, FlashService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(flashIntent);
            } else {
                startService(flashIntent);
            }
        } catch (Throwable e) {
            Log.e(TAG, "FlashService start failed: " + e.getMessage());
        }

        // الانتقال لـ EkoCore (تأكد أن اسم ملف الواجهة الأساسية هو EkoCore.class كما هو)
        try {
            Intent coreIntent = new Intent(this, EkoCore.class);
            coreIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(coreIntent);
            finish();
        } catch (Throwable e) {
            Log.e(TAG, "EkoCore start failed: " + e.getMessage());
            showToast("Failed to start EkoCore: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 8. updateConnectionModeBadge (مطلوبة من OptFragment)
    // ═══════════════════════════════════════════════════════════

    public void updateConnectionModeBadge(boolean wirelessEnabled) {
        // hook للتوسعة المستقبلية
    }

    // ═══════════════════════════════════════════════════════════
    // 9. أدوات مساعدة
    // ═══════════════════════════════════════════════════════════

    private void showToast(String message) {
        try {
            if (currentToast != null) currentToast.cancel();
            currentToast = Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG);
            currentToast.show();
        } catch (Throwable ignored) {}
    }

    private void dismissDialog() {
        try {
            if (currentDialog != null && currentDialog.isShowing()) {
                currentDialog.dismiss();
            }
        } catch (Throwable ignored) {}
        currentDialog = null;
    }
}
