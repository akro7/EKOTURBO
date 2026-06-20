/*
 * Copyright (c) 2026 Gabriel2392
 * GPL-3.0 — see <https://www.gnu.org/licenses/>
 */

#pragma once

#include "core/status.hpp"

#include <atomic>
#include <condition_variable>
#include <exception>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <thread>
#include <utility>

#include <spdlog/spdlog.h>

namespace eko::core {

// بديل std::stop_token لأن Android libc++ لا يدعمه
struct StopToken {
  explicit StopToken(std::shared_ptr<std::atomic<bool>> flag)
      : flag_(std::move(flag)) {}

  bool stop_requested() const noexcept {
    return flag_ && flag_->load(std::memory_order_relaxed);
  }

 private:
  std::shared_ptr<std::atomic<bool>> flag_;
};

template <class Slot>
class TwoSlotPrefetcher {
 public:
  // بديل std::move_only_function بـ std::function
  using InitFn = std::function<void(Slot&)>;
  using FillFn = std::function<Result<bool>(Slot&, StopToken)>;

  class Lease {
   public:
    Lease() = default;

    Lease(const Lease&) = delete;
    Lease& operator=(const Lease&) = delete;

    Lease(Lease&& o) noexcept
        : owner_(std::exchange(o.owner_, nullptr)), idx_(o.idx_) {}

    Lease& operator=(Lease&& o) noexcept {
      if (this == &o) return *this;
      release_();
      owner_ = std::exchange(o.owner_, nullptr);
      idx_ = o.idx_;
      return *this;
    }

    ~Lease() { release_(); }

    Slot& get() const noexcept { return owner_->slots_[idx_]; }
    Slot* operator->() const noexcept { return &get(); }
    Slot& operator*() const noexcept { return get(); }

   private:
    friend class TwoSlotPrefetcher;

    Lease(TwoSlotPrefetcher* owner, int idx) : owner_(owner), idx_(idx) {}

    void release_() noexcept {
      if (!owner_) return;
      owner_->release_(idx_);
      owner_ = nullptr;
    }

    TwoSlotPrefetcher* owner_ = nullptr;
    int idx_ = 0;
  };

 public:
  explicit TwoSlotPrefetcher(FillFn fill, InitFn init = {})
      : init_(std::move(init)),
        fill_(std::move(fill)),
        stop_flag_(std::make_shared<std::atomic<bool>>(false)) {
    if (init_) {
      init_(slots_[0]);
      init_(slots_[1]);
    }
    reader_ = std::thread([this] { reader_loop_(); });
  }

  ~TwoSlotPrefetcher() { request_stop(); }

  TwoSlotPrefetcher(const TwoSlotPrefetcher&) = delete;
  TwoSlotPrefetcher& operator=(const TwoSlotPrefetcher&) = delete;

  void request_stop() noexcept {
    {
      std::lock_guard lk(m_);
      stopping_ = true;
    }
    if (stop_flag_) stop_flag_->store(true, std::memory_order_relaxed);
    cv_can_fill_.notify_all();
    cv_can_take_.notify_all();

    if (reader_.joinable()) reader_.join();
  }

  std::optional<Lease> next() noexcept {
    std::unique_lock lk(m_);
    cv_can_take_.wait(lk, [&] {
      return stopping_ || error_.has_value() || filled_[read_idx_] ||
             (done_ && !filled_[read_idx_]);
    });

    if (stopping_ || error_.has_value() || !filled_[read_idx_])
      return std::nullopt;

    const int idx = read_idx_;
    read_idx_ ^= 1;
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

  void reader_loop_() noexcept {
    StopToken st(stop_flag_);
    try {
      for (;;) {
        {
          std::unique_lock lk(m_);
          cv_can_fill_.wait(lk, [&] {
            return stopping_ || !filled_[write_idx_];
          });
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
            done_ = true;
            cv_can_take_.notify_all();
            return;
          }
          if (!*r) {
            done_ = true;
            cv_can_take_.notify_all();
            return;
          }

          filled_[write_idx_] = true;
          write_idx_ ^= 1;
        }

        cv_can_take_.notify_all();
      }
    } catch (const std::exception& e) {
      spdlog::debug("TwoSlotPrefetcher reader threw: {}", e.what());
      {
        std::lock_guard lk(m_);
        error_ = e.what();
        done_ = true;
      }
      cv_can_take_.notify_all();
      cv_can_fill_.notify_all();
    } catch (...) {
      spdlog::debug("TwoSlotPrefetcher reader threw unknown exception");
      {
        std::lock_guard lk(m_);
        error_ = "Unknown exception in TwoSlotPrefetcher reader thread";
        done_ = true;
      }
      cv_can_take_.notify_all();
      cv_can_fill_.notify_all();
    }
  }

 private:
  Slot slots_[2]{};

  mutable std::mutex m_;
  std::condition_variable cv_can_fill_;
  std::condition_variable cv_can_take_;

  bool filled_[2]{false, false};
  bool done_     = false;
  bool stopping_ = false;

  int write_idx_ = 0;
  int read_idx_  = 0;

  std::optional<Error> error_{};

  std::thread reader_{};
  std::shared_ptr<std::atomic<bool>> stop_flag_;

  InitFn init_{};
  FillFn fill_{};
};

} // namespace eko::core
