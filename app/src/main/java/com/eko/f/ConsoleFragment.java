package com.eko.f;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ConsoleFragment extends Fragment {

    private TextView   tvLog;
    private TextView   tvPitStatus;
    private ScrollView scrollView;
    private View       btnClear;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_console, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvLog       = view.findViewById(R.id.tv_log);
        tvPitStatus = view.findViewById(R.id.tv_pit_status);
        scrollView  = view.findViewById(R.id.scroll_log);
        btnClear    = view.findViewById(R.id.btn_clear_log);

        if (btnClear != null) btnClear.setOnClickListener(v -> clearLog());

        observeLog();
    }

    // ── Store observation ─────────────────────────────────────────────────────

    private void observeLog() {
        EkoStore store = getStore();
        if (store == null) return;

        // مراقبة الـ log
        store.observeLog(requireActivity(), logText -> {
            if (tvLog == null) return;
            String text = logText != null ? logText : "";
            tvLog.setText(text);
            // تحديث PIT status من الـ log
            if (tvPitStatus != null) {
                if (text.contains("PIT_OK") || text.contains("PIT OK") || text.contains("pit: download complete")) {
                    tvPitStatus.setText("PIT: ✓ Exchange successful");
                    tvPitStatus.setTextColor(0xFF50FFAB);
                } else if (text.contains("PIT GET failed") || text.contains("pit: GET failed") || text.contains("pit: failed to receive")) {
                    tvPitStatus.setText("PIT: ✗ Exchange failed");
                    tvPitStatus.setTextColor(0xFFFF5555);
                } else if (text.contains("PIT_EXCHANGE") || text.contains("PIT exchange") || text.contains("Downloading PIT")) {
                    tvPitStatus.setText("PIT: ⟳ Exchanging…");
                    tvPitStatus.setTextColor(0xFFBD93F9);
                }
            }
            if (scrollView != null) {
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        });

        // مراقبة الحالة
        store.observeStatus(requireActivity(), status -> {
            if (tvPitStatus != null && status != null && !status.isEmpty()) {
                if (status.contains("PIT")) {
                    tvPitStatus.setText("PIT: " + status);
                }
            }
        });
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void clearLog() {
        EkoStore store = getStore();
        if (store != null) store.clearLog();
        if (tvLog != null) tvLog.setText("");
        if (tvPitStatus != null) {
            tvPitStatus.setText("PIT: waiting for device…");
            tvPitStatus.setTextColor(0xFFBD93F9);
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private EkoStore getStore() {
        if (getActivity() == null) return null;
        return ((EkoApplication) requireActivity().getApplication()).getStore();
    }
}
