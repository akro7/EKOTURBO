package com.eko.f;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.util.Log;
import java.util.Locale;

public final class EkoApplication extends Application {

    private EkoStore store;
    private WirelessFlashManager wirelessFlashManager;
    private final Object storeLock    = new Object();
    private final Object wirelessLock = new Object();

    // ── Accessors ─────────────────────────────────────────────────────────────

    public EkoStore getStore() {
        if (store == null) {
            synchronized (storeLock) {
                if (store == null) store = new EkoStore(this);
            }
        }
        return store;
    }

    public WirelessFlashManager getWirelessFlashManager() {
        if (wirelessFlashManager == null) {
            synchronized (wirelessLock) {
                if (wirelessFlashManager == null)
                    wirelessFlashManager = new WirelessFlashManager(this, getStore());
            }
        }
        return wirelessFlashManager;
    }

    // ── Language helpers ──────────────────────────────────────────────────────

    public static void applyLanguage(String langCode) {
        if (langCode == null || langCode.isEmpty()) return;
        try {
            Locale locale = Locale.forLanguageTag(langCode);
            Locale.setDefault(locale);
        } catch (Throwable ignored) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void attachBaseContext(Context context) {
        if (context == null) throw new NullPointerException("context == null");

        DataHelper dataHelper = DataHelper.INSTANCE;
        dataHelper.init(context);

        String langCode = dataHelper.getString(DataHelper.APP_LANGUAGE_KEY, null);

        if (langCode == null || langCode.isEmpty()) {
            String tag = null;
            try {
                Locale deviceLocale = LocaleList.getAdjustedDefault().get(0);
                if (deviceLocale != null) tag = deviceLocale.toLanguageTag();
            } catch (Throwable ignored) {}

            if (tag != null && !tag.isEmpty()
                    && dataHelper.getSupportedLanguages().containsKey(tag)) {
                langCode = tag;
            } else {
                langCode = "en";
            }
            dataHelper.setString(DataHelper.APP_LANGUAGE_KEY, langCode);
        }

        if (!langCode.isEmpty()) {
            Locale locale = Locale.forLanguageTag(langCode);
            Locale.setDefault(locale);
            Configuration config = context.getResources().getConfiguration();
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            context = context.createConfigurationContext(config);
        }

        super.attachBaseContext(context);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig == null) throw new NullPointerException("newConfig == null");
        super.onConfigurationChanged(newConfig);
        String lang = DataHelper.INSTANCE.getString(DataHelper.APP_LANGUAGE_KEY, null);
        if (lang != null && !lang.isEmpty()) applyLanguage(lang);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        EkoStore s = getStore();
        if (s.isWirelessEnabled()) {
            getWirelessFlashManager().setEnabled(true);
        }

        setupRemoteConfigDefaults();

        // ✅ Fix: حذف Firebase Crashlytics — الاستعاضة بـ Log فقط
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e("EkoApplication", "Uncaught exception on thread: " + thread.getName(), throwable);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            } else {
                System.exit(2);
            }
        });
    }

    private void setupRemoteConfigDefaults() {
        try {
            // Remote config bootstrap placeholder
        } catch (Throwable e) {
            Log.e("FirebaseRemoteConfig", "Could not set remote config defaults", e);
        }
    }
}
