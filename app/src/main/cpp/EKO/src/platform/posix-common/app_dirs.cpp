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

#include "platform/posix-common/app_dirs.hpp"

#include <cstdlib>

namespace eko::posix_common {

eko::core::Result<std::filesystem::path> app_cache_dir() noexcept {
#if defined(__APPLE__)
  const char* home = std::getenv("HOME");
  if (!home || !*home) return eko::core::fail("HOME is not set");
  return std::filesystem::path(home) / "Library" / "Caches" / "eko";
#else
  const char* xdg_cache_home = std::getenv("XDG_CACHE_HOME");
  if (xdg_cache_home && *xdg_cache_home) return std::filesystem::path(xdg_cache_home) / "eko";

  const char* home = std::getenv("HOME");
  if (!home || !*home) return eko::core::fail("HOME is not set");
  return std::filesystem::path(home) / ".cache" / "eko";
#endif
}

} // namespace eko::posix_common