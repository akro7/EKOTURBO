package com.eko.f;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;

/**
 * SplashActivity — شاشة البداية بتظهر قبل MainActivity
 * بتعرض أنيميشن عنكبوت وبعدين تنتقل للـ MainActivity
 */
public final class SplashActivity extends AppCompatActivity {

    private static final String TAG = "SplashActivity";
    private static final long SPLASH_DURATION_MS = 2800;

    // ── Language fix ─────────────────────────────────────────────────────────
    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = "en";
        try {
            lang = DataHelper.INSTANCE.getString(DataHelper.APP_LANGUAGE_KEY, null);
            if (lang == null || lang.isEmpty()) lang = "en";
        } catch (Throwable ignored) {}

        try {
            Locale locale = new Locale(lang);
            Locale.setDefault(locale);
            Configuration config = newBase.getResources().getConfiguration();
            config.setLocale(locale);
            super.attachBaseContext(newBase.createConfigurationContext(config));
        } catch (Throwable e) {
            Log.e(TAG, "attachBaseContext: " + e.getMessage());
            super.attachBaseContext(newBase);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // شاشة كاملة بدون status bar
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_splash);

        // Views
        View spiderWeb  = findViewById(R.id.splash_spider_web);
        View spiderBody = findViewById(R.id.splash_spider_body);
        TextView tvTitle = findViewById(R.id.splash_title);
        TextView tvSub   = findViewById(R.id.splash_subtitle);

        // أنيميشن spider web — scale + fade in
        if (spiderWeb != null) {
            Animation scaleIn = AnimationUtils.loadAnimation(this, R.anim.splash_web_in);
            spiderWeb.startAnimation(scaleIn);
        }

        // أنيميشن spider body — fade in مع delay
        if (spiderBody != null) {
            Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.splash_body_in);
            spiderBody.startAnimation(fadeIn);
        }

        // أنيميشن النصوص
        if (tvTitle != null) {
            Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.splash_text_in);
            tvTitle.startAnimation(slideUp);
        }
        if (tvSub != null) {
            Animation slideUp2 = AnimationUtils.loadAnimation(this, R.anim.splash_text_in);
            slideUp2.setStartOffset(300);
            tvSub.startAnimation(slideUp2);
        }

        // الانتقال للـ MainActivity بعد المدة المحددة
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                Intent intent = new Intent(SplashActivity.this, MainActivity.class);
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        }, SPLASH_DURATION_MS);
    }

    @Override
    public void onBackPressed() {
        // منع الرجوع أثناء الـ splash
    }
}
