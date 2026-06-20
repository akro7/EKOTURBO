/*
 * Copyright (c) 2026 Gabriel2392
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "signal_shield.hpp"

#include <pthread.h>
#include <signal.h>
#include <unistd.h>

#include <utility>

namespace eko::core {

namespace {

sigset_t make_set() {
  sigset_t set{};
  sigemptyset(&set);
  sigaddset(&set, SIGINT);
  sigaddset(&set, SIGTERM);
  sigaddset(&set, SIGHUP);
  sigaddset(&set, SIGQUIT);
  sigaddset(&set, SIGTSTP);
  return set;
}

const char* sig_desc(int signo) {
  switch (signo) {
    case SIGINT:  return "SIGINT";
    case SIGTERM: return "SIGTERM";
    case SIGHUP:  return "SIGHUP";
    case SIGQUIT: return "SIGQUIT";
    case SIGTSTP: return "SIGTSTP";
    default:      return "SIGNAL";
  }
}

} // namespace

SignalShield::SignalShield(Callback cb)
    : cb_(std::move(cb)),
      stop_flag_(std::make_shared<std::atomic<bool>>(false)) {}

SignalShield::~SignalShield() { stop_and_restore_(); }

SignalShield::SignalShield(SignalShield&& o) noexcept { *this = std::move(o); }

SignalShield& SignalShield::operator=(SignalShield&& o) noexcept {
  if (this == &o) return *this;

  stop_and_restore_();

  cb_            = std::move(o.cb_);
  watcher_       = std::move(o.watcher_);
  stop_flag_     = std::move(o.stop_flag_);
  active_        = o.active_;
  old_mask_      = o.old_mask_;
  have_old_mask_ = o.have_old_mask_;

  o.active_        = false;
  o.have_old_mask_ = false;
  return *this;
}

void SignalShield::stop_and_restore_() noexcept {
  if (active_) {
    // إشارة للـ thread إنه يوقف — بديل request_stop()
    if (stop_flag_) stop_flag_->store(true);

    // إرسال SIGTERM لإخراج sigwait من الانتظار
    ::kill(::getpid(), SIGTERM);

    if (watcher_.joinable()) watcher_.join();
    active_ = false;
  }

  if (have_old_mask_) {
    (void)::pthread_sigmask(SIG_SETMASK, &old_mask_, nullptr);
    have_old_mask_ = false;
  }
}

std::optional<SignalShield> SignalShield::enable(Callback cb) {
  ::signal(SIGPIPE, SIG_IGN);

  const sigset_t set = make_set();

  sigset_t old{};
  if (::pthread_sigmask(SIG_BLOCK, &set, &old) != 0) {
    return std::nullopt;
  }

  SignalShield sh(std::move(cb));
  sh.active_        = true;
  sh.old_mask_      = old;
  sh.have_old_mask_ = true;

  // بديل std::jthread + stop_token باستخدام std::thread + atomic flag
  auto flag = sh.stop_flag_;
  sh.watcher_ = std::thread([cb2 = sh.cb_, flag]() mutable {
    sigset_t waitset = make_set();
    int count = 0;

    for (;;) {
      int signo = 0;
      const int r = ::sigwait(&waitset, &signo);
      if (r != 0) continue;

      // بديل st.stop_requested()
      if (flag->load()) break;

      ++count;
      if (cb2) cb2(sig_desc(signo), count);
    }
  });

  return std::optional<SignalShield>{std::move(sh)};
}

} // namespace eko::core
