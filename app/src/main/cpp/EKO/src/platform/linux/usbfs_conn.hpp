/*
 * Copyright (c) 2026 Gabriel2392
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * CHANGES vs original:
 *  - kPipelineDepth: 4 → 8
 *    مع max_urb_bytes_=2MB (no-limit): 1 URB لكل ODIN packet → أقل ioctl overhead
 *    مع max_urb_bytes_=256KB (limited): 8×256KB=2MB → كل الـ ODIN packet في الهواء دفعة واحدة
 *
 *  - zlp_needed_ محذوف بالكامل
 *    كانت دايمًا true ولم تُعاد إلى false أبدًا، مما يعني ZLP synchronous بعد
 *    كل send() بما فيها كل 2MB data chunk. الحل الصحيح: ZLPs مسؤولية layer البروتوكول
 *    وليس layer النقل (recv_zlp() موجود ويُستدعى explicitly من get_pit()).
 *
 *  - max_urb_bytes_ dynamic field (بدل kMaxUrbBytes الثابت)
 *    يُضبط في open() بناءً على USBFS_CAP_NO_PACKET_SIZE_LIM:
 *      no limit → 2 MB  (URB واحد = ODIN packet كامل، أقل عدد من ioctls)
 *      has limit → 256 KB (للتوافق مع SPRD/Exynos kernels)
 */

#pragma once

#include "core/byte_transport.hpp"
#include "core/status.hpp"
#include "platform/linux/usbfs_device.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

#include <linux/usbdevice_fs.h>

namespace eko::linux {

class UsbFsConnection : public eko::core::IByteTransport {
 public:
  Kind kind() const noexcept override { return Kind::UsbBulk; }

  explicit UsbFsConnection(UsbFsDevice& dev);

  eko::core::Status open() noexcept;
  void close() noexcept;

  bool connected() const noexcept override { return connected_; }

  int send(std::span<const std::uint8_t> data, unsigned retries = 8) override;
  int recv(std::span<std::uint8_t> data, unsigned retries = 8) override;
  int recv_zlp(unsigned retries = 0) override;

  void set_timeout_ms(int ms) noexcept override { timeout_ms_ = ms; }
  int  timeout_ms() const noexcept override { return timeout_ms_; }
  void set_packet_size_hint(std::size_t bytes) noexcept override;

  std::size_t max_packet_size() const noexcept { return max_pack_size_; }

 private:
  // ── Async URB pipeline ────────────────────────────────────────────────────
  //
  // kPipelineDepth = 8:
  //   no limit  → 1 URB per ODIN packet (2MB) → depth 8 ≫ URBs needed,
  //               pipeline trivially satisfied; ioctl count per packet: 2
  //   has limit → 8 × 256KB = 2MB → الـ ODIN packet كله في الهواء دفعة واحدة
  //               ioctl count per packet: 16 (بدل pipeline bubbles سابقًا)
  static constexpr int kPipelineDepth = 8;

  struct PendingUrb {
    usbdevfs_urb urb{};
    bool         in_flight = false;
  };

  void drain_out_urbs_() noexcept;

  std::array<PendingUrb, kPipelineDepth> out_urbs_{};

  UsbFsDevice& dev_;
  bool         connected_     = false;
  int          timeout_ms_    = 200;

  // max_urb_bytes_: حجم URB واحد في send() — يُحدَّد في open()
  //   no packet-size limit → 2 MB  (URB = ODIN packet كامل)
  //   has packet-size limit → 256 KB (SPRD/Exynos compat)
  std::size_t  max_urb_bytes_ = 256 * 1024;

  // max_pack_size_: حجم chunk واحد في recv() (للاستقبال فقط)
  std::size_t  max_pack_size_ = 16 * 1024;
};

} // namespace eko::linux
