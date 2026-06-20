/*
 * Copyright (c) 2026 Gabriel2392
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * CHANGES vs original (TwoSlotPrefetcher):
 *
 *  [1] kNumSlots = 4 (كان 2)
 *      2 slots = double buffering: slot يُرسَل + slot يُملأ. كافٍ نظريًا
 *      لكن عند أي jitter في fill time (LZ4 decompression burst، I/O stall)
 *      يتوقف المُرسِل ويحدث bubble في USB pipeline.
 *      4 slots = tolerates 3 consecutive slow fills before stalling.
 *      Memory: 4 × buffer_bytes (max) ≈ 4 × 64MB = 256MB — مقبول.
 *
 *  [2] أولوية reader thread: setpriority(PRIO_PROCESS, 0, -8)
 *      reader_loop_ يقرأ من disk أو يعمل LZ4 decompression.
 *      بدون priority boost، الـ scheduler يمكن أن يُؤجّل الـ thread
 *      أثناء الإرسال وتحدث stall في الـ USB pipeline.
 *      نفس الأولوية التي تأخذها worker threads في group_flasher.cpp.
 *
 *  [3] Modulo indexing بدل XOR toggle
 *      XOR (^= 1) يعمل فقط مع 2 slots. Modulo (% kNumSlots) يعمل مع N.
 *
 * Interface خارجي: لم يتغير (اسم الكلاس، Lease، methods).
 */

#pragma once

#include "core/android_compat.hpp"
#include "core/status.hpp"

#include <condition_variable>
#include <exception>
#include <mutex>
#include <optional>
#include <utility>

#ifdef __ANDROID__
#include <sys/resource.h>
#endif

#include <spdlog/spdlog.h>

namespace eko::core {

template <class Slot>
class TwoSlotPrefetcher {
 public:
  // عدد slots المتاحة في نفس الوقت
  // 4 slots = يتحمّل 3 fills بطيئة متتالية قبل stall
  static constexpr int kNumSlots = 4;

  using InitFn = std::move_only_function<void(Slot&)>;
  using FillFn = std::move_only_function<Result<bool>(Slot&, std::stop_token)>;

  class Lease {
   public:
    Lease() = default;
    Lease(const Lease&) = delete;
    Lease& operator=(const Lease&) = delete;

    Lease(Lease&& o) noexcept : owner_(std::exchange(o.owner_, nullptr)), idx_(o.idx_) {}
    Lease& operator=(Lease&& o) noexcept {
      if (this == &o) return *this;
      release_();
      owner_ = std::exchange(o.owner_, nullptr);
      idx_   = o.idx_;
      return *this;
    }

    ~Lease() { release_(); }

    Slot& get()       const noexcept { return owner_->slots_[idx_]; }
    Slot* operator->() const noexcept { return &get(); }
    Slot& operator*()  const noexcept { return get(); }

   private:
    friend class TwoSlotPrefetcher;
    Lease(TwoSlotPrefetcher* owner, int idx) : owner_(owner), idx_(idx) {}

    void release_() noexcept {
      if (!owner_) return;
      owner_->release_(idx_);
      owner_ = nullptr;
    }

    TwoSlotPrefetcher* owner_ = nullptr;
    int                idx_   = 0;
  };

 public:
  explicit TwoSlotPrefetcher(FillFn fill, InitFn init = {})
      : init_(std::move(init)), fill_(std::move(fill)) {
    if (init_) {
      for (int i = 0; i < kNumSlots; ++i) init_(slots_[i]);
    }
    reader_ = std::jthread([this](std::stop_token st) { reader_loop_(st); });
  }

  ~TwoSlotPrefetcher() { request_stop(); }

  TwoSlotPrefetcher(const TwoSlotPrefetcher&)            = delete;
  TwoSlotPrefetcher& operator=(const TwoSlotPrefetcher&) = delete;

  void request_stop() noexcept {
    {
      std::lock_guard lk(m_);
      stopping_ = true;
    }
    cv_can_fill_.notify_all();
    cv_can_take_.notify_all();

    reader_.request_stop();
    if (reader_.joinable()) reader_.join();
  }

  // المستهلك يأخذ الـ slot التالي الجاهز (يحجب حتى يُملأ)
  std::optional<Lease> next() noexcept {
    std::unique_lock lk(m_);
    cv_can_take_.wait(lk, [&] {
      return stopping_
          || error_.has_value()
          || filled_[read_idx_]
          || (done_ && !filled_[read_idx_]);
    });

    if (stopping_ || error_.has_value() || !filled_[read_idx_]) return std::nullopt;

    const int idx = read_idx_;
    read_idx_ = (read_idx_ + 1) % kNumSlots;   // ← modulo بدل XOR
    return Lease{this, idx};
  }

  Status status() const noexcept {
    std::lock_guard lk(m_);
    return error_ ? Status{std::unexpect, *error_} : Status{};
  }

 private:
  void release_(int idx) noexcept {
    {
      std::lock_guard lk(m_);
      filled_[idx] = false;
    }
    cv_can_fill_.notify_all();
  }

  void reader_loop_(std::stop_token st) noexcept {
    // نفس أولوية worker threads في group_flasher.cpp — لتجنب stall
    // في الـ USB pipeline بسبب تأخير الـ scheduler أثناء I/O أو LZ4
#ifdef __ANDROID__
    setpriority(PRIO_PROCESS, 0, -8);
#endif

    try {
      for (;;) {
        // انتظر حتى يكون write_idx_ فارغًا
        {
          std::unique_lock lk(m_);
          cv_can_fill_.wait(lk, [&] { return stopping_ || !filled_[write_idx_]; });
          if (stopping_ || st.stop_requested()) {
            done_ = true;
            cv_can_take_.notify_all();
            return;
          }
        }

        auto r = fill_(slots_[write_idx_], st);

        {
          std::lock_guard lk(m_);
          if (stopping_ || st.stop_requested()) {
            done_ = true;
            cv_can_take_.notify_all();
            return;
          }
          if (!r) {
            error_ = std::move(r.error());
            done_  = true;
            cv_can_take_.notify_all();
            return;
          }
          if (!*r) {
            done_ = true;
            cv_can_take_.notify_all();
            return;
          }

          filled_[write_idx_] = true;
          write_idx_ = (write_idx_ + 1) % kNumSlots; // ← modulo بدل XOR
        }

        cv_can_take_.notify_all();
      }
    } catch (const std::exception& e) {
      spdlog::debug("TwoSlotPrefetcher reader threw: {}", e.what());
      {
        std::lock_guard lk(m_);
        error_ = e.what();
        done_  = true;
      }
      cv_can_take_.notify_all();
      cv_can_fill_.notify_all();
    } catch (...) {
      spdlog::debug("TwoSlotPrefetcher reader threw unknown exception");
      {
        std::lock_guard lk(m_);
        error_ = "Unknown exception in TwoSlotPrefetcher reader thread";
        done_  = true;
      }
      cv_can_take_.notify_all();
      cv_can_fill_.notify_all();
    }
  }

 private:
  Slot slots_[kNumSlots]{};

  mutable std::mutex      m_;
  std::condition_variable cv_can_fill_;
  std::condition_variable cv_can_take_;

  // filled_[i]: true إذا slot[i] جاهز للاستهلاك
  bool filled_[kNumSlots]{};
  bool done_     = false;
  bool stopping_ = false;

  int write_idx_ = 0; // الـ slot التالي الذي سيملأه reader
  int read_idx_  = 0; // الـ slot التالي الذي سيأخذه consumer

  std::optional<Error> error_{};

  std::jthread reader_{};

  InitFn init_{};
  FillFn fill_{};
};

} // namespace eko::core
