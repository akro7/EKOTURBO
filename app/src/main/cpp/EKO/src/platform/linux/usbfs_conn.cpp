/*
 * Copyright (c) 2026 Gabriel2392
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * CONFIRMED via libbrokkr.so reverse engineering (VA=0x52734, ARM64):
 *
 * Brokkr's LibusbUsbTransport:
 *   Constructor: strh w9, [x0, #0x78]  ; w9=0x101 → zlp_needed_=1, connected_=1
 *   send() end:  if (zlp_needed_==1) libusb_bulk_transfer(handle, ep_out, NULL, 0, &x, 100)
 *   ZLP fail:    strb wzr, [x19, #0x78] ; clear zlp_needed_
 *
 * → Brokkr دايمًا يبعت ZLP بعد كل send(). الـ ZLP مطلوب فعلًا من ODIN protocol.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * الفرق الحقيقي بين Brokkr وEKOTURBO (من analysis الـ ASM):
 *
 * Brokkr send(2MB):
 *   libusb_bulk_transfer(handle, ep_out, buf, 2MB, &x, deadline)  → 1 call
 *   libusb_bulk_transfer(handle, ep_out, NULL, 0,  &x, 100ms)     → ZLP sync
 *   total: ~2 libusb calls per 2MB ODIN packet
 *
 * EKOTURBO original send(2MB) [kMaxUrbBytes=256KB, kPipelineDepth=4]:
 *   8 × USBDEVFS_SUBMITURB   (256KB chunks)
 *   8 × USBDEVFS_REAPURB
 *   1 × USBDEVFS_BULK (len=0) ← sync ZLP
 *   total: 17 ioctls per 2MB packet ← الـ bottleneck الحقيقي
 *
 * FIX:
 *   [1] max_urb_bytes_: dynamic — 2MB (no limit) أو 256KB (limited kernel)
 *       no limit: 1 URB per 2MB packet = 2 ioctls total
 *       limited:  8 URBs but kPipelineDepth=8 → all fired at once
 *
 *   [2] ZLP via USBDEVFS_URB_FLAG_ZERO_PACKET على آخر URB فقط (async):
 *       - الـ kernel يضيف ZLP تلقائيًا إذا len % MaxPacketSize == 0
 *       - 8-byte command (8 % 512 ≠ 0)  → لا ZLP (short packet يكفي) ✓
 *       - 1024-byte handshake (1024 % 512 == 0) → ZLP async              ✓
 *       - 2MB data (2MB % 512 == 0) → ZLP async على الـ URB الأخير       ✓
 *       - لا ioctl إضافي — async داخل الـ URB نفسه
 *       بدل sync USBDEVFS_BULK(len=0) بعد كل send() (كان يضيف ~1-3ms)
 *
 *   [3] kPipelineDepth: 4 → 8
 */

#include "platform/linux/usbfs_conn.hpp"

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <spdlog/spdlog.h>

namespace eko::linux {

namespace {
// حد recv() chunk الواحد — ODIN responses هي 8 bytes، القيمة دي للأمان بس
constexpr std::size_t kRecvChunkMax = 128 * 1024;
} // namespace

UsbFsConnection::UsbFsConnection(UsbFsDevice& dev) : dev_(dev) {}

void UsbFsConnection::set_packet_size_hint(std::size_t bytes) noexcept {
  // recv() only — send() URB size مستقل ومضبوط في open()
  if (bytes == 0) return;
  max_pack_size_ = bytes;
  if (max_pack_size_ == 0) max_pack_size_ = 1;
}

eko::core::Status UsbFsConnection::open() noexcept {
  if (connected_) return {};
  if (!dev_.is_open()) return eko::core::fail("UsbFsConnection::open: device not open");

  const bool no_limit = !dev_.has_packet_size_limit();

  // send() URB size:
  //   no limit → 2MB: URB واحد يطابق ODIN packet (pkt_all_v2plus=2MB)
  //                    ioctls per 2MB: 1 submit + 1 reap = 2
  //   has limit → 256KB: SPRD/Exynos compat
  //                    ioctls per 2MB: 8 submit + 8 reap = 16 (all in-flight with depth=8)
  max_urb_bytes_ = no_limit
      ? static_cast<std::size_t>(2ull * 1024 * 1024)
      : static_cast<std::size_t>(256 * 1024);

  max_pack_size_ = no_limit
      ? kRecvChunkMax
      : static_cast<std::size_t>(256 * 1024);

  connected_ = true;
  return {};
}

void UsbFsConnection::close() noexcept {
  if (connected_) drain_out_urbs_();
  connected_ = false;
}

void UsbFsConnection::drain_out_urbs_() noexcept {
  for (auto& pu : out_urbs_) {
    if (!pu.in_flight) continue;
    ::ioctl(dev_.fd(), USBDEVFS_DISCARDURB, &pu.urb);
    usbdevfs_urb* reaped = nullptr;
    while (::ioctl(dev_.fd(), USBDEVFS_REAPURB, &reaped) != 0) {
      if (errno == EINTR) continue;
      break;
    }
    pu.in_flight = false;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// send() — ASYNC URB PIPELINE
//
// ZLP عبر USBDEVFS_URB_FLAG_ZERO_PACKET على آخر URB فقط:
//   - الـ kernel يضيف ZLP async إذا len % wMaxPacketSize(512) == 0
//   - صح لـ handshake (1024 bytes → ZLP) وdata (2MB → ZLP)
//   - صح لـ commands (8 bytes → لا ZLP، short packet يكفي للـ device)
//   - أسرع من sync USBDEVFS_BULK(len=0): لا ioctl إضافي
//   - آخر URB فقط يأخذ الـ flag (ليس كل URB) لتجنب ZLP في منتصف الـ transfer
//
// Slot safety:
//   in_flight < kPipelineDepth يضمن إن slot % kPipelineDepth حر دايمًا.
//   USB bulk يكمّل بالترتيب (FIFO)، REAPURB يرجع oldest أولًا.
// ─────────────────────────────────────────────────────────────────────────────
int UsbFsConnection::send(std::span<const std::uint8_t> data, unsigned /*retries*/) {
  if (!connected_) return -1;

  const auto eps = dev_.endpoints();
  if (eps.bulk_out == 0) return -1;
  if (data.empty()) return 0;

  const std::uint8_t* p         = data.data();
  const std::uint8_t* const end = p + data.size();
  std::size_t total_sent        = 0;
  int         in_flight         = 0;
  int         slot              = 0;

  // ── submit one async URB ──────────────────────────────────────────────────
  // is_last: true → يضع ZERO_PACKET flag على هذا الـ URB
  //          false → URB في المنتصف، بلا flag (ZLP في المنتصف يكسر الـ transfer)
  auto submit_one = [&](const std::uint8_t* ptr, std::size_t len, int s, bool is_last) -> bool {
    auto& pu = out_urbs_[s];
    pu.urb                   = {};
    pu.urb.type              = USBDEVFS_URB_TYPE_BULK;
    pu.urb.endpoint          = eps.bulk_out;
    // ZERO_PACKET على آخر URB: الـ kernel يضيف ZLP إذا len % MaxPacketSize == 0
    // مثل Brokkr تمامًا لكن async (بدل sync libusb_bulk_transfer(len=0))
    pu.urb.flags             = is_last ? USBDEVFS_URB_FLAG_ZERO_PACKET : 0u;
    pu.urb.usercontext       = reinterpret_cast<void*>(static_cast<uintptr_t>(s));
    pu.urb.buffer            = const_cast<std::uint8_t*>(ptr);
    pu.urb.buffer_length     = static_cast<int>(len);
    pu.urb.number_of_packets = 0;

    if (::ioctl(dev_.fd(), USBDEVFS_SUBMITURB, &pu.urb) != 0) {
      const int e = errno;
      if (e == ENODEV || e == ESHUTDOWN || e == ENOENT) {
        spdlog::warn("USB disconnected during SUBMITURB (errno={})", e);
        connected_ = false;
      } else {
        spdlog::error("USBDEVFS_SUBMITURB failed: {} ({})", std::strerror(e), e);
      }
      return false;
    }
    pu.in_flight = true;
    return true;
  };

  // ── reap one completed URB (blocking) ────────────────────────────────────
  auto reap_one = [&]() -> int {
    usbdevfs_urb* reaped = nullptr;
    for (;;) {
      const int rc = ::ioctl(dev_.fd(), USBDEVFS_REAPURB, &reaped);
      if (rc == 0) break;
      const int e = errno;
      if (e == EINTR) continue;
      if (e == ENODEV || e == ESHUTDOWN || e == ENOENT) {
        spdlog::warn("USB disconnected during REAPURB (errno={})", e);
        connected_ = false;
      } else {
        spdlog::error("USBDEVFS_REAPURB failed: {} ({})", std::strerror(e), e);
      }
      return -1;
    }
    if (!reaped) return -1;

    const int s = static_cast<int>(reinterpret_cast<uintptr_t>(reaped->usercontext));
    out_urbs_[s].in_flight = false;

    if (reaped->status != 0) {
      spdlog::error("URB completed with error status={}", reaped->status);
      return -1;
    }
    return reaped->actual_length;
  };

  // ── main pipeline loop ────────────────────────────────────────────────────
  while (p < end || in_flight > 0) {
    while (p < end && in_flight < kPipelineDepth) {
      const std::size_t chunk   = std::min<std::size_t>(
          static_cast<std::size_t>(end - p), max_urb_bytes_);
      // is_last: هل p+chunk ستصل إلى نهاية الـ buffer بعد الـ submit ده؟
      const bool        is_last = (p + chunk >= end);

      if (!submit_one(p, chunk, slot % kPipelineDepth, is_last)) {
        goto fail_drain;
      }
      p += chunk;
      ++in_flight;
      ++slot;
    }

    if (in_flight > 0) {
      const int got = reap_one();
      if (got < 0) goto fail_drain;
      total_sent += static_cast<std::size_t>(got);
      --in_flight;
    }
  }

  return static_cast<int>(total_sent);

fail_drain:
  drain_out_urbs_();
  connected_ = false;
  return -1;
}

// ─────────────────────────────────────────────────────────────────────────────
// recv_zlp — sync IN ZLP (يُستدعى explicitly من odin_cmd.cpp حيث يلزم)
// ─────────────────────────────────────────────────────────────────────────────
int UsbFsConnection::recv_zlp(unsigned /*retries*/) {
  if (!connected_) return -1;
  const auto eps = dev_.endpoints();
  if (eps.bulk_in == 0) return -1;

  usbdevfs_bulktransfer zlp{};
  zlp.ep      = eps.bulk_in;
  zlp.timeout = 10;
  zlp.len     = 0;
  zlp.data    = nullptr;
  (void)::ioctl(dev_.fd(), USBDEVFS_BULK, &zlp);
  return 0;
}

// ─────────────────────────────────────────────────────────────────────────────
// recv() — sync USBDEVFS_BULK (ODIN responses: 8 bytes each)
// ─────────────────────────────────────────────────────────────────────────────
int UsbFsConnection::recv(std::span<std::uint8_t> data, unsigned retries) {
  if (!connected_) return -1;
  const auto eps = dev_.endpoints();
  if (eps.bulk_in == 0) return -1;
  if (data.size() == 0) return recv_zlp();

  std::uint8_t* p   = data.data();
  std::uint8_t* end = p + data.size();

  usbdevfs_bulktransfer bulk{};
  while (p < end) {
    const auto xfer = static_cast<int>(
        std::min<std::size_t>(std::size_t(end - p), max_pack_size_));
    bulk.ep      = eps.bulk_in;
    bulk.len     = xfer;
    bulk.data    = p;
    bulk.timeout = timeout_ms_;

    unsigned attempt = 0;
    int      retBytes = 0;
    for (;;) {
      retBytes = ::ioctl(dev_.fd(), USBDEVFS_BULK, &bulk);
      if (retBytes >= 0) break;
      const int e = errno;
      if (e == ENODEV || e == ESHUTDOWN || e == ENOENT) {
        spdlog::warn("Device disconnected during recv (errno={})", e);
        connected_ = false;
        return -1;
      }
      if (++attempt > retries) {
        spdlog::error("bulk IN failed: {} ({}), retries exhausted", std::strerror(e), e);
        return -1;
      }
      spdlog::warn("bulk IN failed: {} ({}), retrying ({}/{})",
                   std::strerror(e), e, attempt, retries);
      ::usleep(10'000);
    }
    p += retBytes;
    if (retBytes < xfer) break; // short read = device done
  }
  return static_cast<int>(p - data.data());
}

} // namespace eko::linux
