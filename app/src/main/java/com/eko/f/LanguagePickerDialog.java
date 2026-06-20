package com.eko.f;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shows a dialog letting the user pick the app display language.
 * Persists the selection via EkoStore / DataHelper and triggers a callback.
 */
public final class LanguagePickerDialog {

    private LanguagePickerDialog() {}

    /**
     * @param context  Activity or Fragment context
     * @param store    EkoStore instance (used to persist + read supported languages)
     * @param callback Called with the selected language code after the user picks one
     */
    public static void show(Context context, EkoStore store, OnLanguageSelected callback) {
        if (context == null || store == null) return;

        Map<String, String> supported = DataHelper.INSTANCE.getSupportedLanguages();
        if (supported == null || supported.isEmpty()) return;

        // Build parallel arrays: codes and display names
        final List<String> codes = new ArrayList<>(supported.keySet());
        final String[] names     = new String[codes.size()];
        for (int i = 0; i < codes.size(); i++) {
            names[i] = supported.get(codes.get(i));
        }

        // Pre-select current language
        String current = DataHelper.INSTANCE.getString(DataHelper.APP_LANGUAGE_KEY, "en");
        int checkedItem = codes.indexOf(current);
        if (checkedItem < 0) checkedItem = 0;

        final int[] selected = {checkedItem};

        new AlertDialog.Builder(context)
                .setTitle(R.string.language_picker_title)
                .setSingleChoiceItems(names, checkedItem, (dialog, which) -> selected[0] = which)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String chosenCode = codes.get(selected[0]);
                    DataHelper.INSTANCE.setString(DataHelper.APP_LANGUAGE_KEY, chosenCode);
                    if (callback != null) {
                        String displayName = supported.get(chosenCode);
                        callback.onSelected(displayName != null ? displayName : chosenCode);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── Callback interface ────────────────────────────────────────────────────

    public interface OnLanguageSelected {
        void onSelected(String displayName);
    }
}
