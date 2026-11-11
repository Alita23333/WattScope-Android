#pragma once

#include <cmath>
#include <sstream>
#include <string>

struct GpuLoad {
  double percent = NAN;
  std::string reason;
};

// KGSL publishes a driver measurement window, not a monotonic counter pair.
// 0/0 has no measured duration; the percentage alias cannot repair that window.
inline GpuLoad parseGpuBusy(const std::string &raw) {
  std::istringstream input(raw);
  double busy = NAN, total = NAN;
  std::string extra;
  if (!(input >> busy >> total) || (input >> extra) || !std::isfinite(busy) ||
      !std::isfinite(total) || busy < 0 || total < 0 || busy > total)
    return {NAN, "invalid gpubusy counter pair"};
  if (total == 0)
    return {NAN, "gpubusy has no valid measurement window (total=0)"};
  return {100.0 * busy / total, {}};
}

inline GpuLoad parseGpuPercentage(const std::string &raw) {
  std::istringstream input(raw);
  double value = NAN;
  std::string suffix, extra;
  if (!(input >> value) || !std::isfinite(value) || value < 0 || value > 100)
    return {NAN, "invalid GPU percentage"};
  if ((input >> suffix) && (suffix != "%" || (input >> extra)))
    return {NAN, "invalid GPU percentage suffix"};
  return {value, {}};
}
