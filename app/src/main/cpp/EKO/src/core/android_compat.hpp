#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <thread>
#include <type_traits>

#ifdef __ANDROID__
namespace std {

// Simplified stop_token for Android NDK
class stop_token {
 public:
  stop_token() noexcept = default;
  bool stop_requested() const noexcept {
    return flag_ && flag_->load(std::memory_order_acquire);
  }
  friend class stop_source;
 private:
  explicit stop_token(const std::shared_ptr<std::atomic<bool>>& f) noexcept : flag_(f) {}
  std::shared_ptr<std::atomic<bool>> flag_;
};

class stop_source {
 public:
  stop_source() : flag_(std::make_shared<std::atomic<bool>>(false)) {}
  stop_token get_token() const noexcept {
    return stop_token(flag_);
  }
  void request_stop() noexcept {
    if (flag_) flag_->store(true, std::memory_order_release);
  }
  bool stop_requested() const noexcept {
    return flag_ && flag_->load(std::memory_order_acquire);
  }
 private:
  std::shared_ptr<std::atomic<bool>> flag_;
};

class jthread {
 public:
  jthread() noexcept = default;

  template <typename F, typename... Args>
  explicit jthread(F&& f, Args&&... args) {
    auto token = src_.get_token();
    if constexpr (std::is_invocable_v<std::decay_t<F>, stop_token, Args...>) {
      t_ = std::thread([func = std::forward<F>(f), token, ...args = std::forward<Args>(args)]() mutable {
        func(token, std::forward<Args>(args)...);
      });
    } else if constexpr (std::is_invocable_v<std::decay_t<F>, Args...>) {
      t_ = std::thread(std::forward<F>(f), std::forward<Args>(args)...);
    } else {
      static_assert(sizeof(F) == 0, "jthread: callable not invocable with stop_token or given arguments");
    }
  }

  ~jthread() {
    if (joinable()) {
      request_stop();
      join();
    }
  }

  jthread(const jthread&) = delete;
  jthread& operator=(const jthread&) = delete;
  jthread(jthread&& other) noexcept
      : src_(std::move(other.src_)), t_(std::move(other.t_)) {}
  jthread& operator=(jthread&& other) noexcept {
    if (this != &other) {
      if (joinable()) {
        request_stop();
        join();
      }
      src_ = std::move(other.src_);
      t_ = std::move(other.t_);
    }
    return *this;
  }

  void request_stop() noexcept { src_.request_stop(); }
  bool joinable() const noexcept { return t_.joinable(); }
  void join() { t_.join(); }

 private:
  stop_source src_;
  std::thread t_;
};

template <typename Sig>
using move_only_function = std::function<Sig>;

} // namespace std

#else
#include <stop_token>
#endif
