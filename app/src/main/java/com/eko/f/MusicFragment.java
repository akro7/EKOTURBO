package com.eko.f;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class MusicFragment extends Fragment {

    private static final String TAG = "MusicFragment";

    // ── Model ──────────────────────────────────────────────────────────────────
    private static class Song {
        final String displayName;   // دايماً "Quran"
        final int    rawResId;
        final String resName;       // اسم المورد الأصلي (للتشغيل)

        Song(String displayName, int rawResId, String resName) {
            this.displayName = displayName;
            this.rawResId    = rawResId;
            this.resName     = resName;
        }
    }

    // ── State ──────────────────────────────────────────────────────────────────
    private final List<Song>    songs          = new ArrayList<>();
    private MediaPlayer         mediaPlayer;
    private int                 currentIndex   = -1;
    private boolean             isPlaying      = false;
    private final Handler       handler        = new Handler(Looper.getMainLooper());

    // ── Views ──────────────────────────────────────────────────────────────────
    private MaterialCardView            nowPlayingCard;
    private TextView                    tvNowPlayingName;
    private TextView                    btnPlayPause;
    private TextView                    btnPrev;
    private TextView                    btnNext;
    private LinearProgressIndicator     musicProgress;
    private LinearLayout                songsContainer;

    // ── Song card views list ───────────────────────────────────────────────────
    private final List<MaterialCardView> cardViews = new ArrayList<>();

    // ── Inflate ───────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_music, container, false);
    }

    // ── Bind & Init ───────────────────────────────────────────────────────────

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        nowPlayingCard   = view.findViewById(R.id.now_playing_card);
        tvNowPlayingName = view.findViewById(R.id.tv_now_playing_name);
        tvNowPlayingName.setSelected(true); // needed for marquee scrolling
        btnPlayPause     = view.findViewById(R.id.btn_play_pause);
        btnPrev          = view.findViewById(R.id.btn_prev);
        btnNext          = view.findViewById(R.id.btn_next);
        musicProgress    = view.findViewById(R.id.music_progress);
        songsContainer   = view.findViewById(R.id.songs_container);

        loadSongsFromRaw();
        buildSongCards();

        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPrev.setOnClickListener(v -> playPrev());
        btnNext.setOnClickListener(v -> playNext());
    }

    // ── Load all raw resources that are audio ─────────────────────────────────

    private void loadSongsFromRaw() {
        songs.clear();
        try {
            Field[] fields = R.raw.class.getFields();
            for (Field f : fields) {
                try {
                    int resId    = f.getInt(null);
                    String name  = f.getName();
                    // قبول ogg و mp3 وأي صوت
                    songs.add(new Song("Quran", resId, name));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "loadSongsFromRaw error: " + e.getMessage());
        }
        Log.d(TAG, "Found " + songs.size() + " songs in raw");
    }

    // ── Build song cards dynamically ──────────────────────────────────────────

    private void buildSongCards() {
        songsContainer.removeAllViews();
        cardViews.clear();

        if (songs.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("لا توجد أغاني في مجلد raw بعد.\nأضف ملفات .ogg أو .mp3 وأعد تشغيل التطبيق.");
            empty.setTextColor(0xFF6272A4);
            empty.setTextSize(14);
            empty.setPadding(32, 48, 32, 48);
            songsContainer.addView(empty);
            return;
        }

        for (int i = 0; i < songs.size(); i++) {
            final int idx  = i;
            Song song      = songs.get(i);

            // Card wrapper
            MaterialCardView card = new MaterialCardView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dpToPx(12);
            card.setLayoutParams(lp);
            card.setRadius(dpToPx(20));
            card.setCardElevation(dpToPx(8));
            card.setCardBackgroundColor(0xFF0D0D18);
            card.setStrokeColor(0x1AFFFFFF);
            card.setStrokeWidth(dpToPx(1));
            card.setClickable(true);
            card.setFocusable(true);

            // Inner layout
            LinearLayout inner = new LinearLayout(requireContext());
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
            inner.setGravity(android.view.Gravity.CENTER_VERTICAL);

            // Play icon badge
            TextView badge = new TextView(requireContext());
            badge.setId(View.generateViewId());
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44));
            badgeLp.setMarginEnd(dpToPx(14));
            badge.setLayoutParams(badgeLp);
            badge.setGravity(android.view.Gravity.CENTER);
            badge.setText("🎵");
            badge.setTextSize(20);
            badge.setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.music_play_btn_bg));

            // Text block
            LinearLayout textBlock = new LinearLayout(requireContext());
            textBlock.setOrientation(LinearLayout.VERTICAL);
            textBlock.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvName = new TextView(requireContext());
            tvName.setText(song.displayName);
            tvName.setTextColor(0xFFFFFFFF);
            tvName.setTextSize(15);
            tvName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tvName.setMaxLines(1);
            tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);

            TextView tvSub = new TextView(requireContext());
            tvSub.setText("Quran");
            tvSub.setTextColor(0xFF6272A4);
            tvSub.setTextSize(10);

            textBlock.addView(tvName);
            textBlock.addView(tvSub);

            // Number badge on right
            TextView tvNum = new TextView(requireContext());
            tvNum.setText(String.format("%02d", i + 1));
            tvNum.setTextColor(0x5500E5FF);
            tvNum.setTextSize(11);
            tvNum.setTypeface(android.graphics.Typeface.MONOSPACE);

            inner.addView(badge);
            inner.addView(textBlock);
            inner.addView(tvNum);
            card.addView(inner);

            // Click → play
            card.setOnClickListener(v -> playSong(idx));

            songsContainer.addView(card);
            cardViews.add(card);

            // Stagger animation: كل كارت يدخل بتأخير
            card.setAlpha(0f);
            card.setTranslationX(120f);
            handler.postDelayed(() -> {
                if (!isAdded()) return;
                card.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(350)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
            }, 80L * idx);
        }
    }

    // ── Playback ───────────────────────────────────────────────────────────────

    private void playSong(int index) {
        if (index < 0 || index >= songs.size()) return;

        stopMediaPlayer();
        currentIndex = index;
        Song song    = songs.get(index);

        try {
            mediaPlayer = MediaPlayer.create(requireContext(), song.rawResId);
            if (mediaPlayer == null) {
                Log.e(TAG, "MediaPlayer.create returned null for: " + song.resName);
                return;
            }
            mediaPlayer.setOnCompletionListener(mp -> playNext());
            mediaPlayer.start();
            isPlaying = true;
        } catch (Exception e) {
            Log.e(TAG, "playSong error: " + e.getMessage());
            return;
        }

        updateNowPlaying(song);
        updateCardHighlight(index);
    }

    private void updateNowPlaying(Song song) {
        if (nowPlayingCard == null) return;

        // إظهار البطاقة مع انيميشن
        if (nowPlayingCard.getVisibility() != View.VISIBLE) {
            nowPlayingCard.setAlpha(0f);
            nowPlayingCard.setTranslationY(-40f);
            nowPlayingCard.setVisibility(View.VISIBLE);
            nowPlayingCard.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(400)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }

        tvNowPlayingName.setText(song.displayName);
        btnPlayPause.setText("⏸");

        // Progress indeterminate pulse
        musicProgress.setIndeterminate(true);
        musicProgress.setVisibility(View.VISIBLE);

        // Stroke glow animation on card
        nowPlayingCard.setStrokeColor(0x7700E5FF);
    }

    private void updateCardHighlight(int activeIndex) {
        for (int i = 0; i < cardViews.size(); i++) {
            MaterialCardView card = cardViews.get(i);
            if (i == activeIndex) {
                card.setCardBackgroundColor(0xFF0D1A22);
                card.setStrokeColor(0x7700E5FF);
                card.setStrokeWidth(dpToPx(2));
                // Pulse scale animation on active card
                card.animate()
                        .scaleX(1.02f).scaleY(1.02f)
                        .setDuration(200)
                        .withEndAction(() -> card.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(200)
                                .start())
                        .start();
            } else {
                card.setCardBackgroundColor(0xFF0D0D18);
                card.setStrokeColor(0x1AFFFFFF);
                card.setStrokeWidth(dpToPx(1));
            }
        }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) {
            // شغّل أول أغنية لو مافيش أغنية شغالة
            if (!songs.isEmpty()) playSong(0);
            return;
        }
        if (isPlaying) {
            mediaPlayer.pause();
            isPlaying = false;
            btnPlayPause.setText("▶");
            musicProgress.setIndeterminate(false);
            nowPlayingCard.setStrokeColor(0x3300E5FF);
        } else {
            mediaPlayer.start();
            isPlaying = true;
            btnPlayPause.setText("⏸");
            musicProgress.setIndeterminate(true);
            nowPlayingCard.setStrokeColor(0x7700E5FF);
        }
    }

    private void playNext() {
        if (songs.isEmpty()) return;
        int next = (currentIndex + 1) % songs.size();
        playSong(next);
    }

    private void playPrev() {
        if (songs.isEmpty()) return;
        int prev = (currentIndex - 1 + songs.size()) % songs.size();
        playSong(prev);
    }

    private void stopMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isPlaying = false;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onDestroyView() {
        stopMediaPlayer();
        handler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private int dpToPx(int dp) {
        return (int) (dp * requireContext().getResources().getDisplayMetrics().density);
    }
}
