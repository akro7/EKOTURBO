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

#include "io/source.hpp"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <utility>

namespace eko::io {

// ─── RawFileSource ────────────────────────────────────────────────────────────
class RawFileSource final : public ByteSource {
 public:
  explicit RawFileSource(std::filesystem::path p, std::uint64_t size)
      : path_(std::move(p)), in_(path_, std::ios::binary), size_(size) {}

  bool opened() const noexcept { return in_.is_open(); }

  std::string display_name() const override { return path_.string(); }
  std::uint64_t size() const override { return size_; }

  std::size_t read(std::span<std::byte> out) override {
    if (out.empty()) return 0;
    in_.read(reinterpret_cast<char*>(out.data()), static_cast<std::streamsize>(out.size()));
    const auto n = in_.gcount();
    return (n <= 0) ? 0u : static_cast<std::size_t>(n);
  }

 private:
  std::filesystem::path path_;
  std::ifstream in_;
  std::uint64_t size_ = 0;
};

// ─── TarEntrySource ───────────────────────────────────────────────────────────
class TarEntrySource final : public ByteSource {
 public:
  TarEntrySource(std::filesystem::path tar, TarEntry e)
      : tar_path_(std::move(tar)), entry_(std::move(e)), in_(tar_path_, std::ios::binary), remaining_(entry_.size) {}

  bool opened() const noexcept { return in_.is_open(); }

  bool seek_to_data() noexcept {
    if (!in_.is_open()) return false;
    if (entry_.data_offset > static_cast<std::uint64_t>(std::numeric_limits<std::streamoff>::max())) return false;
    in_.seekg(static_cast<std::streamoff>(entry_.data_offset), std::ios::beg);
    return in_.good();
  }

  std::string display_name() const override { return tar_path_.string() + ":" + entry_.name; }
  std::uint64_t size() const override { return entry_.size; }

  std::size_t read(std::span<std::byte> out) override {
    if (remaining_ == 0 || out.empty()) return 0;
    const auto want = static_cast<std::size_t>(std::min<std::uint64_t>(remaining_, out.size()));
    in_.read(reinterpret_cast<char*>(out.data()), static_cast<std::streamsize>(want));
    const auto n = in_.gcount();
    if (n <= 0) return 0;
    remaining_ -= static_cast<std::uint64_t>(n);
    return static_cast<std::size_t>(n);
  }

 private:
  std::filesystem::path tar_path_;
  TarEntry entry_;
  std::ifstream in_;
  std::uint64_t remaining_ = 0;
};

// ─── FdFileSource ─────────────────────────────────────────────────────────────
// يستخدم fd مفتوح مباشرة (من Java عبر ParcelFileDescriptor)
// يستخدم pread() بدل lseek+read حتى يكون thread-safe
// ويستخدم fstat() لمعرفة الحجم الحقيقي بدلاً من file_size() الذي يرجع 0 على /proc/self/fd/N
class FdFileSource final : public ByteSource {
 public:
  explicit FdFileSource(int fd, std::string name, std::uint64_t size)
      : fd_(::dup(fd))          // dup لتجنب إغلاق fd الأصلي عند destroy
      , name_(std::move(name))
      , size_(size)
      , offset_(0) {}

  ~FdFileSource() override {
    if (fd_ >= 0) ::close(fd_);
  }

  bool valid() const noexcept { return fd_ >= 0; }

  std::string display_name() const override { return name_; }
  std::uint64_t size() const override { return size_; }

  std::size_t read(std::span<std::byte> out) override {
    if (out.empty() || fd_ < 0) return 0;
    const std::size_t want = out.size();
    const ::ssize_t n = ::pread(fd_,
                                reinterpret_cast<void*>(out.data()),
                                want,
                                static_cast<::off_t>(offset_));
    if (n <= 0) return 0;
    offset_ += static_cast<std::uint64_t>(n);
    return static_cast<std::size_t>(n);
  }

 private:
  int fd_ = -1;
  std::string name_;
  std::uint64_t size_   = 0;
  std::uint64_t offset_ = 0;
};

// ─── Factory functions ────────────────────────────────────────────────────────

eko::core::Result<std::unique_ptr<ByteSource>> open_raw_file(const std::filesystem::path& path) noexcept {
  std::error_code ec;
  const auto sz = std::filesystem::file_size(path, ec);
  if (ec) return eko::core::failf("open_raw_file: stat failed: {}", path.string());

  auto ptr = std::make_unique<RawFileSource>(path, static_cast<std::uint64_t>(sz));
  if (!ptr->opened()) return eko::core::failf("open_raw_file: cannot open: {}", path.string());

  return std::move(ptr);
}

eko::core::Result<std::unique_ptr<ByteSource>> open_tar_entry(const std::filesystem::path& tar_path,
                                                                 const TarEntry& entry) noexcept {
  auto ptr = std::make_unique<TarEntrySource>(tar_path, entry);

  if (!ptr->opened()) return eko::core::failf("open_tar_entry: cannot open tar: {}", tar_path.string());
  if (!ptr->seek_to_data()) return eko::core::failf("open_tar_entry: seek failed: {}", tar_path.string());

  return std::move(ptr);
}

eko::core::Result<std::unique_ptr<ByteSource>> open_fd_file(int fd,
                                                               std::string display,
                                                               std::uint64_t known_size) noexcept {
  if (fd < 0)
    return eko::core::fail("open_fd_file: invalid fd");

  // إذا الـ caller ما عرفش الحجم، نجيبه بـ fstat
  std::uint64_t size = known_size;
  if (size == 0) {
    struct ::stat st{};
    if (::fstat(fd, &st) != 0)
      return eko::core::failf("open_fd_file: fstat failed: {}", std::strerror(errno));
    size = static_cast<std::uint64_t>(st.st_size);
  }

  auto ptr = std::make_unique<FdFileSource>(fd, std::move(display), size);
  if (!ptr->valid())
    return eko::core::fail("open_fd_file: dup() failed");

  return std::move(ptr);
}

} // namespace eko::io
