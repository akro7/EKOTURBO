package com.eko.f;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

public class OptFragment extends Fragment {

    private MaterialSwitch switchReboot;
    private MaterialSwitch switchChecksum;
    private MaterialSwitch switchWireless;
    private MaterialSwitch switchNand;
    private TextView       tvPitPath;
    private TextView       tvCurrentLang;
    private MaterialSwitch switchRoot;
    private TextView       tvRootStatus;

    private ActivityResultLauncher<Intent> pitFilePicker;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pitFilePicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            try {
                                requireContext().getContentResolver()
                                        .takePersistableUriPermission(uri,
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (SecurityException ignored) {}
                            getStore().setPitUri(uri);
                            updatePitLabel(uri);
                            Toast.makeText(getContext(), "PIT file selected ✓", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.view_opt, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        switchReboot   = view.findViewById(R.id.switch_reboot);
        switchChecksum = view.findViewById(R.id.switch_checksum);
        switchWireless = view.findViewById(R.id.switch_wireless);
        switchNand     = view.findViewById(R.id.switch_nand);
        tvPitPath      = view.findViewById(R.id.tv_pit_path);
        tvCurrentLang  = view.findViewById(R.id.tv_current_lang);
        switchRoot     = view.findViewById(R.id.switch_root);
        tvRootStatus   = view.findViewById(R.id.tv_root_status);

        EkoStore store = getStore();

        // ── استعادة القيم المحفوظة ──
        if (switchReboot   != null) switchReboot.setChecked(store.isAutoReboot());
        if (switchChecksum != null) switchChecksum.setChecked(store.isSkipSha256());
        if (switchWireless != null) switchWireless.setChecked(store.isWirelessEnabled());
        if (switchNand     != null) switchNand.setChecked(store.isNandErase());

        refreshLangLabel();

        Uri pit = store.getPitUri();
        if (pit != null) updatePitLabel(pit);

        // ── Auto Reboot ──
        if (switchReboot != null) {
            switchReboot.setOnCheckedChangeListener((btn, v) -> {
                store.setAutoReboot(v);
            });
        }

        // ── Skip SHA ──
        if (switchChecksum != null) {
            switchChecksum.setOnCheckedChangeListener((btn, v) -> {
                store.setSkipSha256(v);
                if (v) Toast.makeText(getContext(),
                        "⚠ SHA check disabled — use with official ROMs only",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // ── Wireless Mode ──
        if (switchWireless != null) {
            switchWireless.setOnCheckedChangeListener((btn, checked) -> {
                store.setWirelessEnabled(checked);
                try {
                    ((EkoApplication) requireActivity().getApplication())
                            .getWirelessFlashManager().setEnabled(checked);
                } catch (Throwable ignored) {}
                Toast.makeText(getContext(),
                        checked ? "Wireless mode ON — listening for devices"
                                : "USB mode active",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // ── NAND Erase ── (تحذير قبل التفعيل)
        if (switchNand != null) {
            switchNand.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("⚠ NAND Erase Warning")
                            .setMessage(
                                    "NAND Erase will WIPE ALL PARTITIONS before flashing.\n\n" +
                                    "This is IRREVERSIBLE and may brick your device " +
                                    "if used without a valid PIT file.\n\n" +
                                    "Are you absolutely sure?")
                            .setCancelable(false)
                            .setPositiveButton("ENABLE NAND ERASE", (d, w) -> {
                                store.setNandErase(true);
                                Toast.makeText(getContext(),
                                        "⚠ NAND Erase ENABLED", Toast.LENGTH_LONG).show();
                            })
                            .setNegativeButton("Cancel", (d, w) -> {
                                btn.setChecked(false);
                                store.setNandErase(false);
                            })
                            .show();
                } else {
                    store.setNandErase(false);
                }
            });
        }

        // ── PIT File Picker ──
        View btnPickPit = view.findViewById(R.id.btn_pick_pit);
        if (btnPickPit != null) {
            btnPickPit.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                pitFilePicker.launch(intent);
            });
        }

        // ── Root Mode ─────────────────────────────────────────────────────────
        final MaterialSwitch finalSwitchRoot = switchRoot;
        final TextView finalTvRootStatus = tvRootStatus;
        if (finalSwitchRoot != null) {
            new Thread(() -> {
                RootManager rm = RootManager.getInstance(requireContext());
                int rootType   = rm.detectRootType();
                String typeStr = rm.getRootTypeName();
                requireActivity().runOnUiThread(() -> {
                    if (finalTvRootStatus != null) {
                        if (rootType != RootManager.TYPE_NONE) {
                            finalTvRootStatus.setText("✓ " + typeStr + " detected");
                            finalTvRootStatus.setTextColor(0xFF50FA7B);
                            finalSwitchRoot.setEnabled(true);
                        } else {
                            finalTvRootStatus.setText("✗ No root detected");
                            finalTvRootStatus.setTextColor(0xFFFF5555);
                            finalSwitchRoot.setEnabled(false);
                        }
                    }
                    finalSwitchRoot.setChecked(
                            store.isRootEnabled() && rootType != RootManager.TYPE_NONE);
                });
            }).start();

            finalSwitchRoot.setOnCheckedChangeListener((btn, checked) -> {
                RootManager rm = RootManager.getInstance(requireContext());
                if (checked && !rm.isRooted()) {
                    btn.setChecked(false);
                    Toast.makeText(getContext(), "✗ Root not available", Toast.LENGTH_SHORT).show();
                    return;
                }
                store.setRootEnabled(checked);
                Toast.makeText(getContext(),
                        checked ? "✓ Root mode ON — bypass USB permission dialog"
                                : "USB standard mode",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // ── Language Picker — MaterialCardView ──
        MaterialCardView cardLang = view.findViewById(R.id.btn_lang_select);
        if (cardLang != null) {
            cardLang.setOnClickListener(v -> showLanguagePicker());
        }
    }

    // ── Language Picker ───────────────────────────────────────────────────────

    private void showLanguagePicker() {
        LanguagePickerDialog.show(requireContext(), getStore(), selectedLangName -> {
            refreshLangLabel();
            Toast.makeText(requireContext(),
                    "Language → " + selectedLangName, Toast.LENGTH_SHORT).show();

            // إعادة تشغيل التطبيق من الجذر لتطبيق اللغة على كل الـ Activities
            requireView().postDelayed(() -> {
                Activity act = getActivity();
                if (act == null) return;
                // نستخدم MainActivity كـ entry point حتى attachBaseContext يطبق اللغة صح
                Intent restart = new Intent(act, MainActivity.class);
                restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(restart);
                act.finish();
                act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }, 400);
        });
    }

    private void refreshLangLabel() {
        if (tvCurrentLang == null) return;
        try {
            String code = DataHelper.INSTANCE.getString(DataHelper.APP_LANGUAGE_KEY, "en");
            String name = DataHelper.INSTANCE.getSupportedLanguages().get(code);
            tvCurrentLang.setText(name != null && !name.isEmpty() ? name : getString(R.string.language_en));
        } catch (Throwable ignored) {
            tvCurrentLang.setText(getString(R.string.language_en));
        }
    }

    // ── PIT label ─────────────────────────────────────────────────────────────

    private void updatePitLabel(Uri uri) {
        if (tvPitPath == null) return;
        String seg = uri.getLastPathSegment();
        tvPitPath.setText(seg != null ? seg : uri.toString());
    }

    // ── Store helper ──────────────────────────────────────────────────────────

    private EkoStore getStore() {
        return ((EkoApplication) requireActivity().getApplication()).getStore();
    }
}
