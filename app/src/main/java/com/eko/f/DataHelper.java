package com.eko.f;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DataHelper {
    public static final String APP_LANGUAGE_KEY = "app_language";
    private static SharedPreferences sharedPreferences;
    public static final DataHelper INSTANCE = new DataHelper();

    private static final Map<String, String> supportedLanguages = new LinkedHashMap<String, String>() {{
        put("",    "System Default");
        put("en",  "English");
        put("ar",  "العربية");
        put("de",  "Deutsch");
        put("es",  "Español");
        put("fa",  "فارسی");
        put("fil", "Filipino");
        put("fr",  "Français");
        put("hi",  "हिन्दी");
        put("in",  "Indonesia");
        put("it",  "Italiano");
        put("ja",  "日本語");
        put("ko",  "한국어");
        put("pl",  "Polski");
        put("pt",  "Português");
        put("ro",  "Română");
        put("ru",  "Русский");
        put("th",  "ภาษาไทย");
        put("tr",  "Türkçe");
        put("uk",  "Українська");
        put("vi",  "Tiếng Việt");
        put("zh",  "中文");
    }};

    private DataHelper() {}

    public final String getString(String key, String defValue) {
        if (key == null) throw new NullPointerException("key == null");
        SharedPreferences prefs = sharedPreferences;
        if (prefs != null) return prefs.getString(key, defValue);
        throw new IllegalStateException("DataHelper not initialized");
    }

    public final Map<String, String> getSupportedLanguages() {
        return supportedLanguages;
    }

    public final void init(Context context) {
        if (context == null) throw new NullPointerException("context == null");
        SharedPreferences prefs = context.getSharedPreferences("com.eko.f.PREFERENCES", 0);
        if (prefs == null) throw new IllegalStateException("SharedPreferences null");
        sharedPreferences = prefs;
    }

    public final void setString(String key, String value) {
        if (key == null) throw new NullPointerException("key == null");
        if (value == null) throw new NullPointerException("value == null");
        SharedPreferences prefs = sharedPreferences;
        if (prefs == null) throw new IllegalStateException("DataHelper not initialized");
        prefs.edit().putString(key, value).apply();
    }
}
