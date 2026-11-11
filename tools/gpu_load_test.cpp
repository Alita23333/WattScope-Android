#include "../app/src/main/cpp/gpu_load.h"
#include <iostream>

int main() {
  int checks = 0;
  auto check = [&](bool ok, const char *name) {
    if (!ok) {
      std::cerr << "FAIL: " << name << '\n';
      std::exit(1);
    }
    ++checks;
  };
  check(std::isnan(parseGpuBusy("0 0").percent), "empty window is missing");
  check(!parseGpuBusy("0 0").reason.empty(), "empty window has reason");
  check(parseGpuBusy("0 200").percent == 0, "measured zero is retained");
  check(parseGpuBusy("50 200").percent == 25, "valid busy fraction");
  check(parseGpuBusy("200 200").percent == 100, "full load");
  for (const auto *raw : {"", "0", "junk", "-1 100", "1 -1", "101 100",
                          "nan 100", "1 inf", "1 100 junk"})
    check(std::isnan(parseGpuBusy(raw).percent), "invalid counter pair");
  check(parseGpuPercentage("0 %").percent == 0, "percentage real zero");
  check(parseGpuPercentage("25.5 %").percent == 25.5, "percentage precision");
  check(parseGpuPercentage("100").percent == 100, "percentage no suffix");
  for (const auto *raw : {"", "nan", "-1", "101", "0 0", "1 x", "1 % junk"})
    check(std::isnan(parseGpuPercentage(raw).percent), "invalid percentage");
  std::cout << "PASS: " << checks << " GPU load checks\n";
}
