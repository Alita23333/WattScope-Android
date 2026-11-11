// Independent observer for matched QPT power trials. No /proc/stat, no
// utilization model. Run the same observer in baseline and enabled trials. It
// never writes to sysfs.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <string>
#include <sys/system_properties.h>
#include <thread>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace fs = std::filesystem;
static volatile sig_atomic_t running = 1;
static int64_t bootNs() {
  timespec t{};
  clock_gettime(CLOCK_BOOTTIME, &t);
  return t.tv_sec * 1000000000LL + t.tv_nsec;
}
static std::string readText(const std::string &path) {
  std::ifstream f(path);
  std::string value((std::istreambuf_iterator<char>(f)), {});
  while (!value.empty() && isspace(static_cast<unsigned char>(value.back())))
    value.pop_back();
  return value;
}
static std::string json(const std::string &s) {
  std::string out = "\"";
  for (unsigned char c : s) {
    if (c == '"' || c == '\\') {
      out += '\\';
      out += c;
    } else if (c == '\n')
      out += "\\n";
    else if (c == '\r')
      out += "\\r";
    else if (c == '\t')
      out += "\\t";
    else if (c >= 32)
      out += c;
  }
  return out + '"';
}
struct Node {
  std::string path;
  int fd = -1;
  explicit Node(std::string p) : path(std::move(p)) {}
  Node(const Node &) = delete;
  Node &operator=(const Node &) = delete;
  ~Node() {
    if (fd >= 0)
      close(fd);
  }
  double read() {
    if (fd < 0)
      fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0)
      return NAN;
    char b[128];
    ssize_t n = pread(fd, b, sizeof(b) - 1, 0);
    if (n <= 0) {
      close(fd);
      fd = -1;
      return NAN;
    }
    b[n] = 0;
    char *end;
    double v = strtod(b, &end);
    return end == b || !std::isfinite(v) ? NAN : v;
  }
};
static void number(double v) {
  if (std::isfinite(v))
    std::cout << v;
  else
    std::cout << "null";
}
struct Zone {
  std::string path, name, parent;
};
static void scan(const std::string &path, const std::string &parent,
                 std::vector<Zone> &zones) {
  if (!fs::exists(path + "/energy_uj"))
    return;
  zones.push_back({path, readText(path + "/name"), parent});
  std::error_code ec;
  std::vector<std::string> children;
  for (auto &d : fs::directory_iterator(path, ec))
    if (d.path().filename().string().rfind("qpt:", 0) == 0)
      children.push_back(d.path().string());
  std::sort(children.begin(), children.end());
  for (auto &child : children)
    scan(child, path, zones);
}
int main(int argc, char **argv) {
  int interval = 500, duration = 120;
  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    if (a == "--interval" && i + 1 < argc)
      interval = std::clamp(atoi(argv[++i]), 100, 1000);
    else if (a == "--duration" && i + 1 < argc)
      duration = std::clamp(atoi(argv[++i]), 1, 86400);
    else {
      std::cerr
          << "Usage: cpu-power-probe [--duration seconds] [--interval ms]\n";
      return 2;
    }
  }
  signal(SIGTERM, [](int) { running = 0; });
  signal(SIGINT, [](int) { running = 0; });
  std::cout << std::setprecision(16);
  std::string root = "/sys/class/powercap/qpt";
  std::vector<Zone> zones;
  scan(root + "/qpt:0", "", zones);
  if (zones.empty() || readText(root + "/enabled") != "1") {
    std::cerr << "QPT unavailable or disabled; observer does not enable or "
                 "guess power.\n";
    return 3;
  }
  char model[PROP_VALUE_MAX] = {}, build[PROP_VALUE_MAX] = {};
  __system_property_get("ro.product.marketname", model);
  __system_property_get("ro.build.fingerprint", build);
  std::cout << "{\"type\":\"metadata\",\"format\":1,\"clock\":\"CLOCK_"
               "BOOTTIME\",\"interval_ms\":"
            << interval << ",\"model\":" << json(model)
            << ",\"fingerprint\":" << json(build)
            << ",\"cpu_selection\":\"raw name cpu or cpu-* only; sibling rails "
               "required\",\"zones\":[";
  for (size_t i = 0; i < zones.size(); ++i) {
    if (i)
      std::cout << ',';
    const auto &z = zones[i];
    std::cout << "{\"path\":" << json(z.path) << ",\"name\":" << json(z.name)
              << ",\"parent\":" << json(z.parent) << '}';
  }
  std::cout << "]}\n" << std::flush;

  // Cache all counter descriptors. No shell processes are started per sample.
  std::vector<Node *> energy, power;
  for (auto &z : zones) {
    energy.push_back(new Node(z.path + "/energy_uj"));
    power.push_back(new Node(z.path + "/power_uw"));
  }
  Node enabled(root + "/enabled"),
      batteryTemp("/sys/class/power_supply/battery/temp");
  std::vector<Node *> temps;
  std::error_code ec;
  for (auto &d : fs::directory_iterator("/sys/class/thermal", ec)) {
    auto type = readText(d.path().string() + "/type");
    if (type.find("cpu") != std::string::npos &&
        type.find("trip") == std::string::npos)
      temps.push_back(new Node(d.path().string() + "/temp"));
  }
  auto start = std::chrono::steady_clock::now();
  auto next = start;
  uint64_t seq = 0;
  while (running) {
    const int64_t readStart = bootNs();
    bool active = enabled.read() == 1;
    std::vector<double> e, p;
    for (size_t i = 0; i < zones.size(); ++i) {
      double ev = energy[i]->read(), pv = power[i]->read();
      e.push_back(active && ev >= 0 ? ev : NAN);
      p.push_back(active && pv >= 0 ? pv : NAN);
    }
    double temp = NAN;
    for (auto n : temps) {
      double t = n->read();
      if (std::abs(t) > 1000)
        t /= 1000;
      if (t >= -30 && t < 125 && (!std::isfinite(temp) || t > temp))
        temp = t;
    }
    const int64_t readEnd = bootNs();
    std::cout << "{\"type\":\"sample\",\"seq\":" << seq++
              << ",\"boot_ns\":" << (readStart + readEnd) / 2
              << ",\"read_span_ns\":" << readEnd - readStart
              << ",\"enabled\":" << (active ? "true" : "false")
              << ",\"cpu_temp_c\":";
    number(temp);
    std::cout << ",\"battery_temp_c\":";
    number(batteryTemp.read() / 10.0);
    std::cout << ",\"energy_uj\":[";
    for (size_t i = 0; i < e.size(); ++i) {
      if (i)
        std::cout << ',';
      number(e[i]);
    }
    std::cout << "],\"power_uw\":[";
    for (size_t i = 0; i < p.size(); ++i) {
      if (i)
        std::cout << ',';
      number(p[i]);
    }
    std::cout << "]}\n";
    // Flush once a second. The host only receives a stream; it does not poll
    // sysfs via ADB.
    if (seq % std::max(1, 1000 / interval) == 0)
      std::cout << std::flush;
    if (std::chrono::steady_clock::now() - start >=
        std::chrono::seconds(duration))
      break;
    next += std::chrono::milliseconds(interval);
    std::this_thread::sleep_until(next);
  }
  std::cout << std::flush;
  for (auto n : energy)
    delete n;
  for (auto n : power)
    delete n;
  for (auto n : temps)
    delete n;
  return 0;
}
