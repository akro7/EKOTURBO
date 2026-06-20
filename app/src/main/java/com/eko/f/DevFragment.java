package com.eko.f;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;

public class DevFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // يتم استدعاء واجهة view_dev
        return inflater.inflate(R.layout.view_dev, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ربط كروت المطورين
        MaterialCardView cardOmar = view.findViewById(R.id.card_dev_cat);
        MaterialCardView cardAhmed = view.findViewById(R.id.card_dev_kojo);

        // تفعيل النقر لكارت OMaR
        if (cardOmar != null) {
            cardOmar.setOnClickListener(v -> {
                // فتح حساب عمر على تيليجرام
                openUrl("https://t.me/@DevCat0x3"); 
            });
        }

        // تفعيل النقر لكارت Ahmed
        if (cardAhmed != null) {
            cardAhmed.setOnClickListener(v -> {
                // فتح حساب أحمد على تيليجرام
                openUrl("https://t.me/A_KOJO");
            });
        }
    }

    // دالة لفتح الروابط في المتصفح أو تطبيق تيليجرام مباشرة
    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
