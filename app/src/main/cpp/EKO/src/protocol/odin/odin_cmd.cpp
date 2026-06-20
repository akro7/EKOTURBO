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

#include "protocol/odin/odin_cmd.hpp"
#include "protocol/odin/odin_wire.hpp"

#include "core/bytes.hpp"

#include <array>
#include <cstring>
#include <limits>
#include <string>
#include <thread>
#include <chrono>

#include <spdlog/spdlog.h>
#include <fmt/ranges.h>

namespace eko::odin {

namespace {

constexpr std::int32_t BOOTLOADER_FAIL = static_cast<std::int32_t>(0xffffffff);

// ── تأخير الاستقرار قبل طلبات PIT (بالمللي ثانية) ──────────────────────────
// FIX: إزالة PIT_STABILIZE_DELAY_MS — Brokkr يعمل بدون أي sleep قبل get_pit_size
// التأخير هو السبب الرئيسي في تجاوز watchdog timeout على الأجهزة البطيئة
// الجهاز يكون جاهزاً بعد setup_transfer_options مباشرة
constexpr int PIT_STABILIZE_DELAY_MS  = 0;   // Brokkr يعمل بدون أي sleep
constexpr int PIT_RETRY_BASE_DELAY_MS = 50;   // FIX: تقليل من 100ms لـ 50ms
constexpr int PIT_MAX_RETRIES         = 3;    // FIX: تقليل من 7 لـ 3 — الجهاز يرد في المحاولة الأولى عادةً

inline eko::core::Status require_connected(eko::core::IByteTransport& c) noexcept {
  return c.connected() ? eko::core::Status{} : eko::core::fail("transport not connected");
}

inline eko::core::Status check_resp(std::int32_t expected_id, const ResponseBox& r,
                                       std::int32_t* out_ack) noexcept {
  if (r.id == BOOTLOADER_FAIL)
    return eko::core::fail("Bootloader returned FAIL");
  if (r.id == std::numeric_limits<std::int32_t>::min())
    return eko::core::fail("Invalid response id (INT_MIN)");
  if (r.id != expected_id)
    return eko::core::fail("Unexpected response id");
  if (out_ack)
    *out_ack = r.ack;
  else if (r.ack < 0)
    return eko::core::failf("Operation failed ({})", r.ack);
  return {};
}

static std::int32_t lo32(std::uint64_t v) {
  return static_cast<std::int32_t>(static_cast<std::uint32_t>(v & 0xFFFFFFFFull));
}
static std::int32_t hi32(std::uint64_t v) {
  return static_cast<std::int32_t>(static_cast<std::uint32_t>((v >> 32) & 0xFFFFFFFFull));
}

static eko::core::Result<std::int32_t> require_i32_total(std::uint64_t v) noexcept {
  constexpr std::uint64_t max =
      static_cast<std::uint64_t>(std::numeric_limits<std::int32_t>::max());
  if (v > max)
    return eko::core::fail("TOTALSIZE exceeds ODIN int32 limit on protocol v0/v1");
  return static_cast<std::int32_t>(v);
}

} // namespace

// ═══════════════════════════════════════════════════════════════════════════
// Raw I/O
// ═══════════════════════════════════════════════════════════════════════════

eko::core::Status OdinCommands::send_raw(std::span<const std::byte> data,
                                            unsigned retries) noexcept {
  auto st = require_connected(conn_);
  if (!st) return st;

  std::size_t off = 0;
  while (off < data.size()) {
    const int sent = conn_.send(eko::core::u8(data.subspan(off)), retries);
    if (sent <= 0) return eko::core::fail("send failed");
    off += static_cast<std::size_t>(sent);
  }
  return {};
}

eko::core::Status OdinCommands::recv_raw(std::span<std::byte> data,
                                            unsigned retries) noexcept {
  auto st = require_connected(conn_);
  if (!st) return st;

  std::size_t off = 0;
  while (off < data.size()) {
    const int got = conn_.recv(eko::core::u8(data.subspan(off)), retries);
    if (got <= 0) return eko::core::fail("receive failed");
    off += static_cast<std::size_t>(got);
  }
  return {};
}

eko::core::Status OdinCommands::send_request(const RequestBox& rq,
                                                unsigned retries) noexcept {
  return send_raw(std::as_bytes(std::span{&rq, 1}), retries);
}

eko::core::Result<ResponseBox>
OdinCommands::recv_checked_response(std::int32_t expected_id, std::int32_t* out_ack,
                                    unsigned retries) noexcept {
  ResponseBox r{};
  auto st = recv_raw(std::as_writable_bytes(std::span{&r, 1}), retries);
  if (!st) return eko::core::fail(std::move(st.error()));

  response_from_le(r);

  st = check_resp(expected_id, r, out_ack);
  if (!st) return eko::core::fail(std::move(st.error()));

  return r;
}

eko::core::Result<ResponseBox>
OdinCommands::rpc_(RqtCommandType type, RqtCommandParam param,
                   std::span<const std::int32_t> ints,
                   std::span<const std::int8_t> chars,
                   std::int32_t* out_ack, unsigned retries) noexcept {
  auto st = send_request(make_request(type, param, ints, chars), retries);
  if (!st) return eko::core::fail(std::move(st.error()));
  return recv_checked_response(static_cast<std::int32_t>(type), out_ack, retries);
}

// ═══════════════════════════════════════════════════════════════════════════
// Handshake
// ═══════════════════════════════════════════════════════════════════════════

eko::core::Status OdinCommands::handshake(unsigned retries) noexcept {
  auto st = require_connected(conn_);
  if (!st) return st;

  // FIX: الـ handshake ping يجب أن يكون 1024 bytes على USB
  // Brokkr يرسل "ODIN\0" + 1019 bytes أصفار = 1024 bytes total
  // الجهاز Samsung يتوقع USB bulk packet بحجم 1024 bytes — 5 bytes فقط يُهمَل
  if (conn_.kind() == eko::core::IByteTransport::Kind::UsbBulk) {
    // USB: "ODIN\0" padded to 1024 bytes (مطابق لـ Brokkr و Thor)
    std::array<std::byte, 1024> ping{};
    ping[0] = std::byte{'O'};
    ping[1] = std::byte{'D'};
    ping[2] = std::byte{'I'};
    ping[3] = std::byte{'N'};
    ping[4] = std::byte{0};
    // باقي الـ 1019 bytes = 0 (default من std::array{})
    st = send_raw(ping, retries);
  } else {
    // TCP: 4 bytes "ODIN" فقط (كافية عبر TCP)
    static constexpr std::array<std::byte, 4> ping{
        std::byte{'O'}, std::byte{'D'}, std::byte{'I'}, std::byte{'N'}};
    st = send_raw(ping, retries);
  }
  if (!st) return st;

  constexpr std::string_view expected = "LOKE";
  std::array<std::byte, 64> resp{};
  std::size_t have = 0;

  while (have < expected.size()) {
    const int got = conn_.recv(
        eko::core::u8(std::span<std::byte>(resp.data() + have, resp.size() - have)),
        retries);
    if (got <= 0) return eko::core::fail("Handshake receive failed");
    have += static_cast<std::size_t>(got);
  }

  if (std::memcmp(resp.data(), expected.data(), expected.size()) != 0) {
    spdlog::error("Dump of handshake response ({} bytes):", have);
    spdlog::error("{}", fmt::join(resp.begin(), resp.begin() + have, " "));
#ifndef NDEBUG
    std::array<char, 65> as_str{};
    for (std::size_t i = 0; i < have && i < as_str.size() - 1; ++i) {
      const std::byte b = resp[i];
      as_str[i] = (b >= std::byte{32} && b <= std::byte{126})
                      ? static_cast<char>(b)
                      : '.';
    }
    spdlog::error("Trying it as a string: {}", as_str.data());
#endif
    return eko::core::fail("Handshake failed (expected LOKE)");
  }

  spdlog::debug("ODIN handshake OK");
  return {};
}

// ═══════════════════════════════════════════════════════════════════════════
// Init / Setup
// ═══════════════════════════════════════════════════════════════════════════

eko::core::Result<InitTargetInfo>
OdinCommands::get_version(unsigned retries) noexcept {
  const std::int32_t ints[] = {static_cast<std::int32_t>(ProtocolVersion::PROTOCOL_VER5)};

  std::int32_t ack_i32 = 0;
  auto r = rpc_(RqtCommandType::RQT_INIT, RqtCommandParam::RQT_INIT_TARGET,
                ints, {}, &ack_i32, retries);
  if (!r) return eko::core::fail(std::move(r.error()));

  InitTargetInfo out;
  out.ack_word = static_cast<std::uint32_t>(ack_i32);
  spdlog::debug("ODIN target ack word: 0x{:08X} (protocol v{}, compressed download {})",
                out.ack_word, static_cast<int>(out.protocol()),
                out.supports_compressed_download());
  return out;
}

eko::core::Status OdinCommands::setup_transfer_options(std::int32_t packet_size,
                                                          unsigned retries) noexcept {
  const std::int32_t ints[] = {packet_size};
  auto r = rpc_(RqtCommandType::RQT_INIT, RqtCommandParam::RQT_INIT_PACKETSIZE,
                ints, {}, nullptr, retries);
  if (!r) return eko::core::fail(std::move(r.error()));

  if (packet_size > 0)
    conn_.set_packet_size_hint(
        static_cast<std::size_t>(static_cast<std::uint32_t>(packet_size)));

  return {};
}

eko::core::Status OdinCommands::send_total_size(std::uint64_t total_size,
                                                   ProtocolVersion proto,
                                                   unsigned retries) noexcept {
  if (proto <= ProtocolVersion::PROTOCOL_VER1) {
    auto v = require_i32_total(total_size);
    if (!v) return eko::core::fail(std::move(v.error()));
    const std::int32_t ints[] = {*v};
    auto r = rpc_(RqtCommandType::RQT_INIT, RqtCommandParam::RQT_INIT_TOTALSIZE,
                  ints, {}, nullptr, retries);
    return r ? eko::core::Status{} : eko::core::fail(std::move(r.error()));
  }

  const std::int32_t ints[] = {lo32(total_size), hi32(total_size)};
  auto r = rpc_(RqtCommandType::RQT_INIT, RqtCommandParam::RQT_INIT_TOTALSIZE,
                ints, {}, nullptr, retries);
  return r ? eko::core::Status{} : eko::core::fail(std::move(r.error()));
}

// ═══════════════════════════════════════════════════════════════════════════
// PIT — get size
// FIX: إضافة تأخير استقرار + exponential backoff + تشخيص أدق
// ═══════════════════════════════════════════════════════════════════════════

eko::core::Result<std::int32_t>
OdinCommands::get_pit_size(unsigned retries) noexcept {

  // FIX: إزالة sleep — PIT_STABILIZE_DELAY_MS = 0 الآن
  // Brokkr يثبت أن الجهاز جاهز مباشرة بعد setup_transfer_options بدون أي تأخير
  if (PIT_STABILIZE_DELAY_MS > 0) {
    spdlog::debug("pit: stabilizing device before PIT GET ({} ms)...",
                  PIT_STABILIZE_DELAY_MS);
    std::this_thread::sleep_for(std::chrono::milliseconds(PIT_STABILIZE_DELAY_MS));
  }

  std::int32_t pitSize = 0;

  for (int attempt = 1; attempt <= PIT_MAX_RETRIES; ++attempt) {
    spdlog::debug("pit: GET attempt {}/{}", attempt, PIT_MAX_RETRIES);

    auto r = rpc_(RqtCommandType::RQT_PIT, RqtCommandParam::RQT_PIT_GET,
                  {}, {}, &pitSize, retries);

    if (r) {
      if (pitSize <= 0) {
        spdlog::warn("pit: GET returned non-positive size ({}) on attempt {}",
                     pitSize, attempt);
        // لا نعيد error فوراً — قد يكون encoding مختلف
        // نعتبره نجاحاً ونترك المستوى الأعلى يتحقق
      }
      spdlog::debug("pit: GET OK — size = {} bytes", pitSize);
      return pitSize;
    }

    spdlog::warn("pit: GET attempt {} failed: {}", attempt, r.error());

    if (attempt < PIT_MAX_RETRIES) {
      // Exponential backoff: 200ms, 400ms, 800ms, ...
      const int delay = PIT_RETRY_BASE_DELAY_MS * (1 << (attempt - 1));
      spdlog::debug("pit: waiting {} ms before retry...", delay);
      std::this_thread::sleep_for(std::chrono::milliseconds(delay));
    }
  }

  return eko::core::fail(
      "PIT GET failed after all retries — device not ready or PIT unsupported");
}

// ═══════════════════════════════════════════════════════════════════════════
// PIT — download chunks
// FIX: إضافة تأخير صغير بين الـ chunks + تحسين التشخيص
// ═══════════════════════════════════════════════════════════════════════════

eko::core::Status OdinCommands::get_pit(std::span<std::byte> out,
                                           unsigned retries) noexcept {
  constexpr std::size_t PIT_TRANSMIT_UNIT  = 500;
  // FIX: إزالة INTER_CHUNK_DELAY — Brokkr يرسل الـ chunks مباشرة بدون sleep
  // الجهاز يتعامل مع الـ chunks متتاليةً بدون أي تأخير

  if (out.empty()) return eko::core::fail("PIT output buffer empty");

  const std::size_t pitSize = out.size();
  const std::size_t parts   = ((pitSize - 1) / PIT_TRANSMIT_UNIT) + 1;

  spdlog::debug("pit: downloading {} bytes in {} chunk(s)", pitSize, parts);

  for (std::size_t idx = 0; idx < parts; ++idx) {
    const std::int32_t pitIndex = static_cast<std::int32_t>(idx);

    auto st = send_request(
        make_request(RqtCommandType::RQT_PIT, RqtCommandParam::RQT_PIT_START,
                     std::span{&pitIndex, 1}),
        retries);
    if (!st) {
      spdlog::error("pit: failed to request chunk {}/{}: {}", idx + 1, parts,
                    st.error());
      return st;
    }

    const std::size_t sizeToDownload =
        std::min<std::size_t>(PIT_TRANSMIT_UNIT, pitSize - (PIT_TRANSMIT_UNIT * idx));
    const std::size_t off = idx * PIT_TRANSMIT_UNIT;

    st = recv_raw(out.subspan(off, sizeToDownload), retries);
    if (!st) {
      spdlog::error("pit: failed to receive chunk {}/{}: {}", idx + 1, parts,
                    st.error());
      return st;
    }

    spdlog::debug("pit: chunk {}/{} received ({} bytes)", idx + 1, parts,
                  sizeToDownload);
  }

  (void)conn_.recv_zlp();

  auto r = rpc_(RqtCommandType::RQT_PIT, RqtCommandParam::RQT_PIT_COMPLETE,
                {}, {}, nullptr, retries);
  if (!r) {
    spdlog::error("pit: COMPLETE command failed: {}", r.error());
    return eko::core::fail(std::move(r.error()));
  }

  spdlog::debug("pit: download complete");
  return {};
}

// ═══════════════════════════════════════════════════════════════════════════
// PIT — upload (set)
// ═══════════════════════════════════════════════════════════════════════════

eko::core::Status OdinCommands::set_pit(std::span<const std::byte> pit,
                                           unsigned retries) noexcept {
  if (pit.empty()) return eko::core::fail("PIT buffer empty");
  if (pit.size() > static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max()))
    return eko::core::fail("PIT too large for ODIN int32");

  auto r1 = rpc_(RqtCommandType::RQT_PIT, RqtCommandParam::RQT_PIT_SET,
                 {}, {}, nullptr, retries);
  if (!r1) return eko::core::fail(std::move(r1.error()));

  const auto pitSize32 = static_cast<std::int32_t>(pit.size());
  auto r2 = rpc_(RqtCommandType::RQT_PIT, RqtCommandParam::RQT_PIT_START,
                 std::span{&pitSize32, 1}, {}, nullptr, retries);
  if (!r2) return eko::core::fail(std::move(r2.error()));

  auto st = send_raw(pit, retries);
  if (!st) return st;

  ResponseBox ack{};
  st = recv_raw(std::as_writable_bytes(std::span{&ack, 1}), retries);
  if (!st) return st;

  response_from_le(ack);

  auto r3 = rpc_(RqtCommandType::RQT_PIT, RqtCommandParam::RQT_PIT_COMPLETE,
                 std::span{&pitSize32, 1}, {}, nullptr, retries);
  return r3 ? eko::core::Status{} : eko::core::fail(std::move(r3.error()));
}

// ═══════════════════════════════════════════════════════════════════════════
// Transfer — begin / end
// ═══════════════════════════════════════════════════════════════════════════

eko::core::Status OdinCommands::begin_download(std::int32_t rounded_total_size,
                                                  unsigned retries) noexcept {
  auto r1 = rpc_(RqtCommandType::RQT_XMIT, RqtCommandParam::RQT_XMIT_DOWNLOAD,
                 {}, {}, nullptr, retries);
  if (!r1) return eko::core::fail(std::move(r1.error()));

  auto r2 = rpc_(RqtCommandType::RQT_XMIT, RqtCommandParam::RQT_XMIT_START,
                 std::span{&rounded_total_size, 1}, {}, nullptr, retries);
  return r2 ? eko::core::Status{} : eko::core::fail(std::move(r2.error()));
}

eko::core::Status
OdinCommands::begin_download_compressed(std::int32_t comp_size,
                                        unsigned retries) noexcept {
  auto r1 = rpc_(RqtCommandType::RQT_XMIT,
                 RqtCommandParam::RQT_XMIT_COMPRESSED_DOWNLOAD,
                 {}, {}, nullptr, retries);
  if (!r1) return eko::core::fail(std::move(r1.error()));

  auto r2 = rpc_(RqtCommandType::RQT_XMIT,
                 RqtCommandParam::RQT_XMIT_COMPRESSED_START,
                 std::span{&comp_size, 1}, {}, nullptr, retries);
  return r2 ? eko::core::Status{} : eko::core::fail(std::move(r2.error()));
}

eko::core::Status OdinCommands::end_download_impl_(
    RqtCommandParam complete_param, std::int32_t size_to_flash,
    std::int32_t part_id, std::int32_t dev_type, bool is_last,
    std::int32_t bin_type, bool efs_clear, bool boot_update,
    unsigned retries) noexcept {

  std::int32_t data[8]{};
  // FIX: مطابق لـ odin4 odin_end_sequence_flash:
  // bin_type==1 (bootloader): data[0]=1, data[4]=0 (part_id forced to 0)
  // bin_type!=1 (normal):     data[0]=0, data[4]=part_id
  data[0] = (bin_type == 1) ? 1 : 0;
  data[1] = size_to_flash;
  data[2] = bin_type;
  data[3] = dev_type;
  data[4] = (bin_type == 1) ? 0 : part_id;
  data[5] = is_last    ? 1 : 0;
  data[6] = efs_clear  ? 1 : 0;
  data[7] = boot_update ? 1 : 0;

  auto r = rpc_(RqtCommandType::RQT_XMIT, complete_param, data, {}, nullptr, retries);
  return r ? eko::core::Status{} : eko::core::fail(std::move(r.error()));
}

eko::core::Status OdinCommands::end_download(
    std::int32_t size_to_flash, std::int32_t part_id, std::int32_t dev_type,
    bool is_last, std::int32_t bin_type, bool efs_clear, bool boot_update,
    unsigned retries) noexcept {
  return end_download_impl_(RqtCommandParam::RQT_XMIT_COMPLETE, size_to_flash,
                            part_id, dev_type, is_last, bin_type, efs_clear,
                            boot_update, retries);
}

eko::core::Status OdinCommands::end_download_compressed(
    std::int32_t decomp_size_to_flash, std::int32_t part_id,
    std::int32_t dev_type, bool is_last, std::int32_t bin_type,
    bool efs_clear, bool boot_update, unsigned retries) noexcept {
  return end_download_impl_(RqtCommandParam::RQT_XMIT_COMPRESSED_COMPLETE,
                            decomp_size_to_flash, part_id, dev_type, is_last,
                            bin_type, efs_clear, boot_update, retries);
}

// ═══════════════════════════════════════════════════════════════════════════
// Shutdown
// ═══════════════════════════════════════════════════════════════════════════

eko::core::Status OdinCommands::shutdown(ShutdownMode mode,
                                            unsigned retries) noexcept {
  auto st = require_connected(conn_);
  if (!st) return st;

  auto _close_cmd = [&](RqtCommandParam p, const char* name) -> eko::core::Status {
    auto r = rpc_(RqtCommandType::RQT_CLOSE, p, {}, {}, nullptr, retries);
    if (!r) {
      if (p == RqtCommandParam::RQT_CLOSE_REBOOT)
        spdlog::debug("Failed to send shutdown command {}: {}", name, r.error());
      else
        spdlog::error("Failed to send shutdown command {}: {}", name, r.error());
    } else {
      spdlog::debug("Sent shutdown command {}", name);
    }
    return r ? eko::core::Status{} : eko::core::fail(std::move(r.error()));
  };
#define close_cmd(param) _close_cmd(RqtCommandParam::param, #param)

  if (mode == ShutdownMode::NoReboot)
    return close_cmd(RQT_CLOSE_END);

  if (mode == ShutdownMode::Reboot) {
    st = close_cmd(RQT_CLOSE_END);
    if (!st) return st;
    auto reboot_st = close_cmd(RQT_CLOSE_REBOOT);
    if (!reboot_st)
      spdlog::debug("Reboot command failed (device likely already rebooting): {}",
                    reboot_st.error());
    return {};
  }

  return eko::core::fail("Invalid shutdown mode");
}

} // namespace eko::odin
