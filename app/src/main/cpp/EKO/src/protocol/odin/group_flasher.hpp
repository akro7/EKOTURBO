/*
 * Copyright (c) 2026 Gabriel2392
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#pragma once

#include "core/byte_transport.hpp"
#include "core/status.hpp"
#include "platform/platform_all.hpp"
#include "protocol/odin/flash.hpp"
#include "protocol/odin/odin_cmd.hpp"
#include "protocol/odin/pit.hpp"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace eko::odin {

struct UsbTarget {
  std::string devnode;
  eko::platform::UsbFsDevice     dev;
  eko::platform::UsbFsConnection conn;

  InitTargetInfo init{};
  ProtocolVersion proto = ProtocolVersion::PROTOCOL_NONE;

  std::vector<std::byte> pit_bytes{};
  pit::PitTable          pit_table{};

  explicit UsbTarget(std::string devnode_path)
      : devnode(std::move(devnode_path)), dev(devnode), conn(dev) {}
};

struct Target {
  std::string id;
  eko::core::IByteTransport* link = nullptr;

  InitTargetInfo  init{};
  ProtocolVersion proto = ProtocolVersion::PROTOCOL_NONE;

  std::vector<std::byte> pit_bytes{};
  pit::PitTable          pit_table{};
};

struct PlanItem {
  enum class Kind { Pit, Part };
  Kind kind = Kind::Part;

  std::int32_t part_id  = -1;
  std::int32_t dev_type =  0;

  std::string part_name, pit_file_name, source_base;
  std::uint64_t size = 0;
};

struct Cfg {
  // ── Android OTG — مُحسَّن للسرعة ───────────────────────────────────────
  //
  // buffer_bytes: حجم نافذة الـ prefetch.
  //   64MB = 64 LZ4 block كحد أقصى (kMaxNonFinalLz4Blocks=31 → ~31MB فعلياً).
  //   يُتيح للـ TwoSlotPrefetcher قراءة الـ block القادم أثناء إرسال الحالي.
  std::size_t buffer_bytes = 64ull * 1024 * 1024;

  // pkt_all_v2plus: حجم الـ packet المُفاوَض مع الجهاز (PROTOCOL_VER2+).
  //   2MB = الحد الأعلى الذي تقبله أجهزة Samsung الحديثة.
  //   مع async URB pipeline الجديد، هذا الحجم يُملأ دائماً بلا توقف.
  std::size_t pkt_all_v2plus = 2ull * 1024 * 1024;

  // pkt_any_old: للأجهزة القديمة (PROTOCOL_VER1).
  std::size_t pkt_any_old = 128ull * 1024;

  // preflash_timeout_ms: وقت انتظار كل ioctl قبل الـ flash الفعلي.
  int      preflash_timeout_ms = 5000;
  unsigned preflash_retries    = 3;

  // flash_timeout_ms: وقت انتظار كل URB/ioctl أثناء الـ flash.
  //   120s لتغطية حالات erase الطويلة على بعض الـ partitions.
  int flash_timeout_ms = 120'000;

  bool reboot_after = true;
  bool nand_erase   = false;
};

struct Ui {
  std::function<void(std::size_t, const std::vector<std::string>&)> on_devices;
  std::function<void(const std::string&)> on_model;
  std::function<void(const std::string&)> on_stage;

  std::function<void(const std::vector<PlanItem>&, std::uint64_t)> on_plan;
  std::function<void(std::size_t)> on_item_active;
  std::function<void(std::size_t)> on_item_done;

  std::function<void(std::uint64_t, std::uint64_t, std::uint64_t, std::uint64_t)> on_progress;

  std::function<void(const std::string&)> on_error;
  std::function<void()> on_done;
};

eko::core::Status flash(std::vector<Target*>& devs,
                        const std::vector<ImageSpec>& sources,
                        std::shared_ptr<const std::vector<std::byte>> pit_to_upload,
                        const Cfg& cfg, Ui ui) noexcept;

} // namespace eko::odin
