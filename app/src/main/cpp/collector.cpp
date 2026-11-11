#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <mutex>
#include <poll.h>
#include <set>
#include <sstream>
#include <string>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>
#include "gpu_load.h"
namespace fs = std::filesystem;
static std::atomic<bool> running{true};
static std::mutex outputMutex;
static int64_t ns(clockid_t c = CLOCK_BOOTTIME) {
  timespec t{};
  clock_gettime(c, &t);
  return t.tv_sec * 1000000000LL + t.tv_nsec;
}
static std::string quote(const std::string &s) {
  std::string r = "\"";
  for (char c : s) {
    if (c == '"' || c == '\\')
      r += '\\';
    if (c == '\n' || c == '\r')
      r += ' ';
    else if ((unsigned char)c >= 32)
      r += c;
  }
  return r + '"';
}
static void emit(const std::string &s) {
  std::lock_guard<std::mutex> l(outputMutex);
  std::cout << s << '\n';
  std::cout.flush();
}
static std::string readFile(const std::string &p, int *lastError = nullptr) {
  if (lastError)
    *lastError = 0;
  int fd = open(p.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    if (lastError)
      *lastError = errno;
    return {};
  }
  char b[32768];
  ssize_t n = read(fd, b, sizeof(b) - 1);
  const int error = n < 0 ? errno : ENODATA;
  close(fd);
  if (n <= 0) {
    if (lastError)
      *lastError = error;
    return {};
  }
  b[n] = 0;
  std::string s(b);
  while (!s.empty() && isspace(s.back()))
    s.pop_back();
  return s;
}
static bool writeFile(const std::string &p, const std::string &s) {
  int fd = open(p.c_str(), O_WRONLY | O_CLOEXEC);
  if (fd < 0)
    return false;
  bool ok = write(fd, s.data(), s.size()) == (ssize_t)s.size();
  close(fd);
  return ok;
}
struct Node {
  std::string path;
  int fd = -1;
  int lastError = 0;
  Node(std::string p) : path(std::move(p)) {}
  double read() {
    if (fd < 0)
      fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
      lastError = errno;
      return NAN;
    }
    char b[128];
    ssize_t n = pread(fd, b, sizeof(b) - 1, 0);
    if (n <= 0) {
      lastError = n < 0 ? errno : ENODATA;
      close(fd);
      fd = -1;
      return NAN;
    }
    b[n] = 0;
    char *e = nullptr;
    double v = strtod(b, &e);
    lastError = e == b || !std::isfinite(v) ? EINVAL : 0;
    return e == b ? NAN : v;
  }
  ~Node() {
    if (fd >= 0)
      close(fd);
  }
};
static std::map<std::string, Node *> nodes;
static std::map<std::string, std::string> metricPaths;
static double number(const std::string &p) {
  auto &n = nodes[p];
  if (!n)
    n = new Node(p);
  return n->read();
}
static void descriptor(const std::string &id, const std::string &label,
                       const std::string &unit, const std::string &path,
                       const std::string &parent = "") {
  metricPaths[id] = path;
  emit("D|" + id + "|" + label + "|" + unit + "|" + path + "|" + parent);
}
struct Zone {
  std::string path, id, name, parent;
  double previous = NAN;
  int64_t last = 0;
  int64_t validAfter = 0;
};
static std::vector<Zone> zones;
static void scanZone(const std::string &p, const std::string &parent) {
  if (!fs::exists(p + "/energy_uj"))
    return;
  std::string name = readFile(p + "/name"), id = "qpt." + name;
  for (auto &z : zones)
    if (z.id == id)
      id += "_" + fs::path(p).filename().string();
  zones.push_back({p, id, name, parent});
  descriptor(id, name == "soc" ? "SoC" : name, "W", p + "/power_uw", parent);
  descriptor(id + ".energy", name + " energy", "J", p + "/energy_uj", id);
  std::error_code ec;
  for (auto &d : fs::directory_iterator(p, ec))
    if (d.path().filename().string().rfind("qpt:", 0) == 0)
      scanZone(d.path().string(), id);
}
static bool varint(const std::vector<uint8_t> &b, size_t &i, uint64_t &n,
                   size_t end) {
  n = 0;
  int sh = 0;
  while (i < end && sh < 70) {
    uint8_t c = b[i++];
    n |= uint64_t(c & 127) << sh;
    if (c < 128)
      return true;
    sh += 7;
  }
  return false;
}
struct Field {
  int id;
  uint64_t n = 0;
  size_t start = 0, end = 0;
};
static std::vector<Field> fields(const std::vector<uint8_t> &b, size_t start,
                                 size_t end) {
  std::vector<Field> out;
  size_t i = start;
  while (i < end) {
    uint64_t tag = 0, v = 0;
    if (!varint(b, i, tag, end))
      break;
    Field f{int(tag >> 3)};
    switch (tag & 7) {
    case 0:
      if (!varint(b, i, v, end))
        return out;
      f.n = v;
      break;
    case 2:
      if (!varint(b, i, v, end) || v > end - i)
        return out;
      f.start = i;
      f.end = i + v;
      i += v;
      break;
    case 1:
      i += 8;
      break;
    case 5:
      i += 4;
      break;
    default:
      return out;
    }
    if (i > end)
      return out;
    out.push_back(f);
  }
  return out;
}
static std::string fieldString(const std::vector<uint8_t> &b, const Field &f) {
  return std::string((const char *)b.data() + f.start, f.end - f.start);
}
static std::string packageName;
static std::mutex packageMutex;
static bool autoPackage = false;
static std::set<std::string> eligiblePackages;
static std::string trackingPackage() {
  std::lock_guard<std::mutex> l(packageMutex);
  return packageName;
}
static std::string selectedLayer, selectedPackage;
static int64_t lastSelected = 0;
static std::set<std::pair<std::string, uint64_t>> seen;
static std::vector<std::pair<std::string, uint64_t>> seenOrder;
static void tracePacket(const std::vector<uint8_t> &b, size_t start,
                        size_t end) {
  const std::string target = trackingPackage();
  if (target != selectedPackage) {
    selectedPackage = target;
    selectedLayer.clear();
    seen.clear();
    seenOrder.clear();
    lastSelected = 0;
  }
  uint64_t ts = 0, clock = 6;
  Field graphics{0}, timeline{0};
  for (auto &f : fields(b, start, end)) {
    if (f.id == 8)
      ts = f.n;
    if (f.id == 58)
      clock = f.n;
    if (f.id == 57)
      graphics = f;
    if (f.id == 76)
      timeline = f;
  }
  int64_t timestamp =
      (int64_t)ts + (clock == 3 ? ns() - ns(CLOCK_MONOTONIC) : 0);
  if (graphics.id) {
    for (auto &gf : fields(b, graphics.start, graphics.end)) {
      if (gf.id != 1)
        continue;
      uint64_t frame = 0, type = 0, dur = 0;
      std::string layer;
      for (auto &e : fields(b, gf.start, gf.end)) {
        if (e.id == 1)
          frame = e.n;
        if (e.id == 2)
          type = e.n;
        if (e.id == 3)
          layer = fieldString(b, e);
        if (e.id == 4)
          dur = e.n;
      }
      if (type != 8 || target.empty() ||
          layer.find(target + "/") == std::string::npos ||
          layer.find("SurfaceView") == std::string::npos)
        continue;
      if (selectedLayer != layer) {
        if (!selectedLayer.empty() && ns() - lastSelected < 2000000000LL)
          continue;
        selectedLayer = layer;
        emit("E|" + std::to_string(ns()) + "|layer|" + layer);
        seen.clear();
        seenOrder.clear();
      }
      lastSelected = ns();
      auto key = std::make_pair(layer, frame);
      if (!seen.insert(key).second)
        continue;
      seenOrder.push_back(key);
      if (seenOrder.size() > 2048) {
        for (size_t i = 0; i < 1024; i++)
          seen.erase(seenOrder[i]);
        seenOrder.erase(seenOrder.begin(), seenOrder.begin() + 1024);
      }
      for (char &c : layer)
        if (c == '|' || c == '\n')
          c = ' ';
      emit("F|" + std::to_string(timestamp + (int64_t)dur) + "|" +
           std::to_string(frame) + "|" + layer);
    }
  }
  if (timeline.id) {
    for (auto &f : fields(b, timeline.start, timeline.end))
      if (f.id == 4) {
        std::string layer;
        uint64_t jank = 0;
        for (auto &e : fields(b, f.start, f.end)) {
          if (e.id == 5)
            layer = fieldString(b, e);
          if (e.id == 9)
            jank = e.n;
        }
        auto start = layer.find("SurfaceView");
        auto selectedStart = selectedLayer.find("SurfaceView");
        if (!target.empty() && layer.find(target + "/") != std::string::npos &&
            start != std::string::npos && selectedStart != std::string::npos &&
            layer.substr(start) == selectedLayer.substr(selectedStart))
          emit("J|" + std::to_string(timestamp) + "|" + std::to_string(jank) +
               "|" + layer);
      }
  }
}
static void traceSession() {
  int in[2], out[2];
  if (pipe(in)) {
    emit("E|" + std::to_string(ns()) + "|frame_error|pipe failed");
    return;
  }
  if (pipe(out)) {
    close(in[0]);
    close(in[1]);
    emit("E|" + std::to_string(ns()) + "|frame_error|pipe failed");
    return;
  }
  const pid_t tracePid = fork();
  if (tracePid < 0) {
    close(in[0]);
    close(in[1]);
    close(out[0]);
    close(out[1]);
    emit("E|" + std::to_string(ns()) + "|frame_error|fork failed");
    return;
  }
  if (tracePid == 0) {
    prctl(PR_SET_PDEATHSIG, SIGTERM);
    if (getppid() == 1)
      _exit(127);
    dup2(in[0], 0);
    dup2(out[1], 1);
    int null = open("/dev/null", O_WRONLY);
    dup2(null, 2);
    close(in[1]);
    close(out[0]);
    close(in[0]);
    close(out[1]);
    execl("/system/bin/perfetto", "perfetto", "--txt", "-c", "-", "-o", "-",
          nullptr);
    _exit(127);
  }
  close(in[0]);
  close(out[1]);
  const int traceFd = out[0];
  selectedLayer.clear();
  selectedPackage.clear();
  seen.clear();
  seenOrder.clear();
  lastSelected = 0;
  std::string failure = "Perfetto stream ended";
  std::string cfg =
      "buffers { size_kb: 8192 fill_policy: RING_BUFFER }\nwrite_into_file: "
      "true\nfile_write_period_ms: 250\nflush_period_ms: 1000\ndata_sources { "
      "config { name: \"android.surfaceflinger.frame\" } }\ndata_sources { "
      "config { name: \"android.surfaceflinger.frametimeline\" } }\n";
  write(in[1], cfg.data(), cfg.size());
  close(in[1]);
  std::vector<uint8_t> buffer;
  buffer.reserve(1024 * 1024);
  uint8_t temp[65536];
  while (running) {
    pollfd f{traceFd, POLLIN, 0};
    int pr = poll(&f, 1, 500);
    if (pr < 0 && errno != EINTR) {
      failure = "Perfetto pipe poll failed";
      break;
    }
    if (pr <= 0)
      continue;
    ssize_t n = read(traceFd, temp, sizeof(temp));
    if (n < 0 && errno == EINTR)
      continue;
    if (n <= 0)
      break;
    buffer.insert(buffer.end(), temp, temp + n);
    size_t at = 0;
    while (at < buffer.size()) {
      size_t i = at;
      uint64_t tag = 0, len = 0;
      if (!varint(buffer, i, tag, buffer.size()) ||
          !varint(buffer, i, len, buffer.size()) || len > buffer.size() - i)
        break;
      if (tag == 10)
        tracePacket(buffer, i, i + len);
      else {
        failure = "invalid trace packet";
        break;
      }
      at = i + len;
    }
    if (failure == "invalid trace packet")
      break;
    if (at)
      buffer.erase(buffer.begin(), buffer.begin() + at);
    if (buffer.size() > 16 * 1024 * 1024) {
      failure = "trace buffer exceeded";
      break;
    }
  }
  close(traceFd);
  // Only this worker owns/reaps this exact child; no global kill or
  // cross-session PID reuse.
  kill(tracePid, SIGINT);
  int childStatus = 0;
  bool reaped = false;
  for (int i = 0; i < 20; ++i) {
    const auto result = waitpid(tracePid, &childStatus, WNOHANG);
    if (result == tracePid || (result < 0 && errno == ECHILD)) {
      reaped = true;
      break;
    }
    usleep(100000);
  }
  if (!reaped) {
    kill(tracePid, SIGKILL);
    while (waitpid(tracePid, &childStatus, 0) < 0 && errno == EINTR) {
    }
  }
  if (running)
    emit("E|" + std::to_string(ns()) + "|frame_error|" + failure);
}
static void traceLoop() {
  int failures = 0;
  while (running) {
    const auto began = ns();
    traceSession();
    if (!running)
      break;
    failures = ns() - began > 5000000000LL ? 1 : std::min(failures + 1, 5);
    const int delayMs = std::min(8000, 500 << (failures - 1));
    for (int waited = 0; running && waited < delayMs; waited += 100)
      usleep(100000);
    if (running)
      emit("E|" + std::to_string(ns()) + "|frame_reconnect|retry " +
           std::to_string(failures));
  }
}
static pid_t gamePid = 0;
static bool foregroundKnown = false;
static std::string foregroundCandidate() {
  // cgroup.procs contains process IDs, not every thread. No process-wide /proc
  // scan or dumpsys polling.
  std::string procs = readFile("/dev/cpuset/top-app/cgroup.procs");
  if (procs.empty())
    procs = readFile("/sys/fs/cgroup/top-app/cgroup.procs");
  std::istringstream input(procs);
  pid_t pid;
  std::string candidate;
  int best = 100;
  while (input >> pid) {
    std::string base = "/proc/" + std::to_string(pid);
    std::string cmd = readFile(base + "/cmdline");
    std::string pkg(cmd.c_str());
    if (!eligiblePackages.count(pkg))
      continue;
    std::string adjText = readFile(base + "/oom_score_adj");
    if (adjText.empty())
      continue;
    int adj = atoi(adjText.c_str());
    if (adj < best) {
      candidate = pkg;
      best = adj;
    } else if (adj == best && pkg == trackingPackage())
      candidate = pkg;
  }
  return candidate;
}
static void updateForeground() {
  static std::string pending;
  static int stable = 0;
  const std::string candidate = foregroundCandidate();
  // System panels/keyguard do not identify another application. Retain the
  // last app identity and pause capture until it is visible again.
  if (candidate.empty()) {
    foregroundKnown = false;
    pending.clear();
    stable = 0;
    return;
  }
  if (candidate == pending)
    stable++;
  else {
    pending = candidate;
    stable = 1;
  }
  foregroundKnown = candidate == trackingPackage() || stable >= 2;
  if (stable < 2 || candidate == trackingPackage())
    return;
  {
    std::lock_guard<std::mutex> l(packageMutex);
    packageName = candidate;
    gamePid = 0;
  }
  emit("A|" + std::to_string(ns()) + "|" + candidate);
}
static bool foreground() {
  if (autoPackage)
    return foregroundKnown && !trackingPackage().empty();
  const std::string target = trackingPackage();
  if (target.empty())
    return false;
  if (!gamePid || !fs::exists("/proc/" + std::to_string(gamePid))) {
    gamePid = 0;
    DIR *d = opendir("/proc");
    if (d) {
      dirent *e;
      while ((e = readdir(d))) {
        if (!isdigit(e->d_name[0]))
          continue;
        std::string p = "/proc/" + std::string(e->d_name);
        std::string cmd = readFile(p + "/cmdline");
        if (std::string(cmd.c_str()) == target) {
          gamePid = atoi(e->d_name);
          break;
        }
      }
      closedir(d);
    }
  }
  std::string cgroup = readFile("/proc/" + std::to_string(gamePid) + "/cpuset");
  return cgroup == "/top-app" || cgroup.find("/top-app/") != std::string::npos;
}
static std::map<int, std::vector<uint64_t>> previousCpu;
static std::map<int, std::vector<int>> policies;
static std::vector<std::string> cpuTemps, gpuTemps;
static void scanHardware() {
  std::error_code ec;
  for (auto &d :
       fs::directory_iterator("/sys/devices/system/cpu/cpufreq", ec)) {
    if (d.path().filename().string().rfind("policy", 0) != 0)
      continue;
    int index = atoi(d.path().filename().string().c_str() + 6);
    std::istringstream ss(readFile(d.path().string() + "/related_cpus"));
    int c;
    while (ss >> c)
      policies[index].push_back(c);
    descriptor("cpu.cluster" + std::to_string(index) + ".freq",
               "CPU " + readFile(d.path().string() + "/related_cpus"), "MHz",
               d.path().string() + "/scaling_cur_freq");
    descriptor("cpu.cluster" + std::to_string(index) + ".usage", "CPU cluster usage",
               "%", "/proc/stat");
    for (int core : policies[index]) {
      const std::string id = "cpu." + std::to_string(core);
      descriptor(id + ".freq", "CPU " + std::to_string(core) + " freq",
                 "MHz", d.path().string() + "/scaling_cur_freq");
      descriptor(id + ".usage", "CPU " + std::to_string(core) + " usage", "%",
                 "/proc/stat");
      descriptor(id + ".online", "CPU " + std::to_string(core) + " online", "",
                 "/sys/devices/system/cpu/cpu" + std::to_string(core) +
                     "/online");
    }
  }
  descriptor("cpu.usage", "CPU usage", "%", "/proc/stat");
  descriptor("gpu.freq", "GPU freq", "MHz", "/sys/class/kgsl/kgsl-3d0/gpuclk");
  descriptor("gpu.usage", "GPU usage", "%", "/sys/class/kgsl/kgsl-3d0/gpubusy");
  for (auto &d : fs::directory_iterator("/sys/class/thermal", ec)) {
    std::string p = d.path().string(), type = readFile(p + "/type");
    if (type.find("trip") != std::string::npos)
      continue;
    if (type.find("cpu") != std::string::npos)
      cpuTemps.push_back(p);
    if (type.find("gpu") != std::string::npos)
      gpuTemps.push_back(p);
  }
  auto sensorSources = [](const std::vector<std::string> &sensors) {
    std::string result = "max(";
    for (const auto &p : sensors)
      result += readFile(p + "/type") + " @ " + p + "/temp; ";
    return result + ")";
  };
  for (std::string k : {"cpu", "gpu", "soc", "battery"})
    descriptor("temp." + k, k + " temp", "°C",
               k == "battery" ? "/sys/class/power_supply/battery/temp"
               : k == "cpu"   ? sensorSources(cpuTemps)
               : k == "gpu"
                   ? sensorSources(gpuTemps)
                   : sensorSources(cpuTemps) + "; " + sensorSources(gpuTemps));
  descriptor("battery.power", "Battery power", "W",
             "battery/power_now → current_now × voltage_now");
  descriptor("battery.current", "Battery current", "A", "battery/current_now");
  descriptor("battery.voltage", "Battery voltage", "V", "battery/voltage_now");
  descriptor("battery.capacity", "Battery level", "%", "battery/capacity");
  descriptor("ram.used", "RAM used", "GiB",
             "/proc/meminfo: MemTotal−MemAvailable");
  descriptor("ram.percent", "RAM usage", "%", "/proc/meminfo");
  descriptor("ram.total", "RAM total", "GiB", "/proc/meminfo");
  descriptor("ddr.freq", "DDR freq", "MHz",
             "仅确认实际 DDR 时钟的节点；未找到则不可用");
}
static double temperature(const std::vector<std::string> &paths) {
  double v = NAN;
  for (auto &p : paths) {
    double t = number(p + "/temp");
    if (std::abs(t) > 1000)
      t /= 1000.;
    if (t >= -30 && t < 125 && (std::isnan(v) || t > v))
      v = t;
  }
  return v;
}
int main(int argc, char **argv) {
  signal(SIGTERM, [](int) { running = false; });
  signal(SIGINT, [](int) { running = false; });
  signal(SIGPIPE, SIG_IGN);
  prctl(PR_SET_NAME, "watt-collector", 0, 0, 0);
  int interval = 500, duration = 0;
  bool standalone = false, frames = true;
  std::string stateDir = "/data/local/tmp";
  for (int i = 1; i < argc; i++) {
    std::string a = argv[i];
    if (a == "--package" && i + 1 < argc)
      packageName = argv[++i];
    else if (a == "--auto-packages" && i + 1 < argc) {
      autoPackage = true;
      std::istringstream input(readFile(argv[++i]));
      std::string pkg;
      while (input >> pkg)
        eligiblePackages.insert(pkg);
    } else if (a == "--interval" && i + 1 < argc)
      interval = std::clamp(atoi(argv[++i]), 100, 1000);
    else if (a == "--duration" && i + 1 < argc)
      duration = atoi(argv[++i]);
    else if (a == "--state" && i + 1 < argc)
      stateDir = argv[++i];
    else if (a == "--standalone")
      standalone = true;
    else if (a == "--no-frames")
      frames = false;
  }
  emit("E|" + std::to_string(ns()) + "|collector|uid=" +
       std::to_string(getuid()) + " pid=" + std::to_string(getpid()));
  std::string enabled = "/sys/class/powercap/qpt/enabled",
              journal = stateDir + "/qpt.previous";
  bool changed = false;
  if (fs::exists(journal)) {
    std::string prev = readFile(journal);
    bool recovered =
        prev == "0" && (readFile(enabled) == "0" ||
                        (writeFile(enabled, "0") && readFile(enabled) == "0"));
    if (recovered)
      unlink(journal.c_str());
    emit("E|" + std::to_string(ns()) +
         (recovered
              ? "|qpt_recovery|restored previous interrupted acquisition"
              : "|qpt_recovery_error|could not restore interrupted QPT state"));
  }
  if (readFile(enabled) == "0") {
    std::ofstream f(journal);
    f << "0";
    f.close();
    changed = writeFile(enabled, "1") && readFile(enabled) == "1";
    if (!changed)
      emit("E|" + std::to_string(ns()) + "|qpt_error|cannot enable QPT");
  }
  scanZone("/sys/class/powercap/qpt/qpt:0", "");
  scanHardware();
  std::thread trace;
  if (frames && (!packageName.empty() || autoPackage))
    trace = std::thread(traceLoop);
  int64_t began = ns(), next = began;
  std::string ddrPath;
  // Whitelist clocks whose name denotes DDR itself; bandwidth/memlat/LLCC votes
  // are excluded.
  std::error_code ec;
  for (auto &d : fs::directory_iterator("/sys/class/devfreq", ec)) {
    std::string name = d.path().filename().string();
    if (name.find("ddr") != std::string::npos &&
        name.find("bw") == std::string::npos &&
        name.find("memlat") == std::string::npos &&
        name.find("llcc") == std::string::npos &&
        fs::exists(d.path() / "cur_freq")) {
      ddrPath = d.path().string() + "/cur_freq";
      break;
    }
  }
  bool qptWasOn = false;
  int64_t qptValidAfter = 0;
  while (running && (!duration || ns() - began < duration * 1000000000LL)) {
    if (autoPackage)
      updateForeground();
    int64_t now = ns();
    std::map<std::string, double> v;
    // An unread metric must be present as null, never default-inserted as a
    // valid zero.
    for (const auto &[id, path] : metricPaths)
      v[id] = NAN;
    std::map<std::string, std::string> provenance;
    std::map<std::string, std::string> missingReasons;
    int cpuReadError = 0, memoryReadError = 0, gpuReadError = 0;
    bool qptOn = readFile(enabled) == "1";
    if (qptOn && !qptWasOn) {
      qptValidAfter = now + 1500000000LL;
      emit("E|" + std::to_string(now) +
           "|qpt_baseline|QPT enabled; establishing counter baseline for 1500 "
           "ms");
    }
    qptWasOn = qptOn;
    if (changed && !qptOn) {
      changed = false;
      unlink(journal.c_str());
      emit("E|" + std::to_string(now) +
           "|qpt_external_change|QPT disabled externally; ownership released");
    }
    for (auto &z : zones) {
      double e = number(z.path + "/energy_uj"),
             p = number(z.path + "/power_uw");
      if (e < 0)
        e = NAN;
      if (p < 0)
        p = NAN;
      if (qptOn && std::isfinite(e) && std::isfinite(z.previous) &&
          e < z.previous) {
        z.validAfter = now + 1500000000LL;
        emit("E|" + std::to_string(now) + "|counter_reset|" + z.name);
      }
      const bool warming = qptOn && now < std::max(qptValidAfter, z.validAfter);
      if (!qptOn) {
        p = NAN;
        e = NAN;
      }
      if (!warming && std::isnan(p) && std::isfinite(e) &&
          std::isfinite(z.previous) && e >= z.previous && now > z.last) {
        p = (e - z.previous) * 1e9 / (now - z.last);
        provenance[z.id] = "energy_delta";
      } else
        provenance[z.id] = "power_uw";
      v[z.id] = warming ? NAN : p / 1e6;
      v[z.id + ".energy"] = warming ? NAN : e / 1e6;
      if (warming) {
        provenance[z.id] =
            "baseline pending; raw_power_uw=" + std::to_string(p);
        provenance[z.id + ".energy"] =
            "baseline pending; raw_energy_uj=" + std::to_string(e);
        missingReasons[z.id] = missingReasons[z.id + ".energy"] =
            "QPT 启动或计数器重置，等待连续能量基线";
      }
      z.previous = e;
      z.last = now;
    }
    std::string battery = "/sys/class/power_supply/battery/";
    std::string status = readFile(battery + "status");
    v["battery.current"] = number(battery + "current_now") / 1e6;
    v["battery.voltage"] = number(battery + "voltage_now") / 1e6;
    double bp = number(battery + "power_now");
    if (!std::isfinite(bp)) {
      bp = v["battery.current"] * v["battery.voltage"] * 1e6;
      provenance["battery.power"] = "current_x_voltage";
    } else
      provenance["battery.power"] = "power_now";
    v["battery.power"] = bp / 1e6;
    v["battery.capacity"] = number(battery + "capacity");
    v["temp.battery"] = number(battery + "temp") / 10.;
    v["temp.cpu"] = temperature(cpuTemps);
    v["temp.gpu"] = temperature(gpuTemps);
    v["temp.soc"] =
        std::isfinite(v["temp.cpu"]) ? v["temp.cpu"] : v["temp.gpu"];
    if (std::isfinite(v["temp.gpu"]))
      v["temp.soc"] = std::isfinite(v["temp.soc"])
                          ? std::max(v["temp.soc"], v["temp.gpu"])
                          : v["temp.gpu"];
    for (auto &[index, cores] : policies) {
      double f = number("/sys/devices/system/cpu/cpufreq/policy" +
                        std::to_string(index) + "/scaling_cur_freq") /
                 1000.;
      v["cpu.cluster" + std::to_string(index) + ".freq"] = f;
      for (int c : cores)
        v["cpu." + std::to_string(c) + ".freq"] = f;
    }
    std::istringstream stat(readFile("/proc/stat", &cpuReadError));
    std::string line;
    while (std::getline(stat, line)) {
      if (line.rfind("cpu", 0) != 0)
        break;
      std::istringstream ss(line);
      std::string label;
      ss >> label;
      int core = label == "cpu" ? -1 : atoi(label.c_str() + 3);
      std::vector<uint64_t> a;
      uint64_t x;
      while (ss >> x)
        a.push_back(x);
      if (a.size() < 4)
        continue;
      uint64_t total = 0;
      for (size_t i = 0; i < std::min<size_t>(8, a.size()); i++)
        total += a[i];
      uint64_t idle = a[3] + (a.size() > 4 ? a[4] : 0);
      auto prev = previousCpu[core];
      double usage = NAN;
      if (prev.size() == 2 && total > prev[0] && idle >= prev[1])
        usage = 100. * (double(total - prev[0]) - double(idle - prev[1])) /
                (total - prev[0]);
      previousCpu[core] = {total, idle};
      v[core < 0 ? "cpu.usage" : "cpu." + std::to_string(core) + ".usage"] =
          usage;
    }
    for (auto &[index, cores] : policies) {
      double sum = 0;
      int count = 0;
      for (int c : cores) {
        double online = c == 0 ? 1
                               : number("/sys/devices/system/cpu/cpu" +
                                        std::to_string(c) + "/online");
        v["cpu." + std::to_string(c) + ".online"] = online;
        if (online == 0)
          v["cpu." + std::to_string(c) + ".freq"] =
              v["cpu." + std::to_string(c) + ".usage"] = NAN;
        double u = v["cpu." + std::to_string(c) + ".usage"];
        if (std::isfinite(u)) {
          sum += u;
          count++;
        }
      }
      v["cpu.cluster" + std::to_string(index) + ".usage"] =
          count ? sum / count : NAN;
    }
    v["gpu.freq"] = number("/sys/class/kgsl/kgsl-3d0/gpuclk") / 1e6;
    const auto gpuRaw =
        readFile("/sys/class/kgsl/kgsl-3d0/gpubusy", &gpuReadError);
    auto gpuLoad = parseGpuBusy(gpuRaw);
    provenance["gpu.usage"] = "gpubusy busy/total; raw=" + gpuRaw;
    // Both KGSL attributes expose the same window and may reset it on read
    // while powered down. Never turn an empty/invalid window into a zero by
    // reading its percentage alias. Use that alias only if gpubusy is absent.
    if (gpuReadError == ENOENT || gpuReadError == ENODEV) {
      int percentageError = 0;
      const auto raw = readFile(
          "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", &percentageError);
      gpuLoad = parseGpuPercentage(raw);
      gpuReadError = percentageError;
      provenance["gpu.usage"] = "gpu_busy_percentage; raw=" + raw;
    }
    v["gpu.usage"] = gpuLoad.percent;
    if (!std::isfinite(gpuLoad.percent) && !gpuReadError)
      missingReasons["gpu.usage"] = gpuLoad.reason;
    std::istringstream mem(readFile("/proc/meminfo", &memoryReadError));
    double mt = NAN, ma = NAN;
    while (std::getline(mem, line)) {
      std::istringstream ss(line);
      std::string k;
      double n;
      ss >> k >> n;
      if (k == "MemTotal:")
        mt = n;
      if (k == "MemAvailable:")
        ma = n;
    }
    v["ram.total"] = mt / 1048576.;
    v["ram.used"] = (mt - ma) / 1048576.;
    v["ram.percent"] = mt > 0 ? (mt - ma) * 100. / mt : NAN;
    v["ddr.freq"] = ddrPath.empty() ? NAN : number(ddrPath) / 1e6;
    if (!ddrPath.empty())
      metricPaths["ddr.freq"] = ddrPath;
    std::ostringstream o;
    o.precision(12);
    o << "H|" << now << '|' << (foreground() ? 1 : 0) << '|' << status << "|{";
    bool first = true;
    for (auto &[k, n] : v) {
      if (!first)
        o << ',';
      first = false;
      o << quote(k) << ':';
      if (std::isfinite(n))
        o << n;
      else
        o << "null";
    }
    o << "}|{";
    first = true;
    for (auto &[k, p] : provenance) {
      if (!first)
        o << ',';
      first = false;
      o << quote(k) << ':' << quote(p);
    }
    o << "}|{";
    first = true;
    for (const auto &[id, value] : v) {
      if (std::isfinite(value))
        continue;
      std::string state = "missing", reason = "本周期没有有效值",
                  path = metricPaths[id];
      auto node = nodes.find(path);
      if (node != nodes.end() && node->second->lastError) {
        state = "read_failed";
        reason = std::string(strerror(node->second->lastError)) + " @ " + path;
      }
      int fileError = 0;
      if (id.rfind("cpu.", 0) == 0 && id.find(".usage") != std::string::npos)
        fileError = cpuReadError;
      if (id.rfind("ram.", 0) == 0) {
        fileError = memoryReadError;
        path = "/proc/meminfo";
      }
      if (id == "gpu.usage")
        fileError = gpuReadError;
      if (fileError) {
        state = "read_failed";
        reason = std::string(strerror(fileError)) + " @ " + path;
      }
      if (missingReasons.count(id)) {
        state = "missing";
        reason = missingReasons[id];
      }
      if (id == "ddr.freq" && ddrPath.empty()) {
        state = "unsupported";
        reason = "未发现已确认的 DDR 实际时钟源";
      } else if (id.rfind("qpt.", 0) == 0 && !qptOn) {
        state = "missing";
        reason = "QPT 当前关闭";
      } else if ((id == "temp.cpu" && cpuTemps.empty()) ||
                 (id == "temp.gpu" && gpuTemps.empty()) ||
                 (id == "temp.soc" && cpuTemps.empty() && gpuTemps.empty())) {
        state = "unsupported";
        reason = "未发现已确认的相关温度传感器";
      } else if (id.rfind("cpu.", 0) == 0 &&
                 (id.find(".freq") != std::string::npos ||
                  id.find(".usage") != std::string::npos)) {
        std::string core = id.substr(0, id.rfind('.'));
        auto online = v.find(core + ".online");
        if (online != v.end() && online->second == 0) {
          state = "missing";
          reason = "核心离线";
        }
      }
      if (!first)
        o << ',';
      first = false;
      o << quote(id) << ":{\"state\":" << quote(state)
        << ",\"reason\":" << quote(reason) << '}';
    }
    o << '}';
    emit(o.str());
    next += interval * 1000000LL;
    int ms = std::max<int64_t>(0, (next - ns()) / 1000000);
    if (standalone)
      std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    else {
      pollfd f{0, POLLIN | POLLHUP, 0};
      int r = poll(&f, 1, ms);
      if (r > 0) {
        char cmd[128];
        ssize_t n = read(0, cmd, sizeof(cmd));
        if (n <= 0 || std::string(cmd, n).find("stop") != std::string::npos)
          running = false;
      }
    }
    if (ns() - next > interval * 1000000LL)
      next = ns();
  }
  running = false;
  if (trace.joinable())
    trace.join();
  if (changed) {
    bool ok = readFile(enabled) == "1" && writeFile(enabled, "0") &&
              readFile(enabled) == "0";
    emit("E|" + std::to_string(ns()) +
         (ok ? "|qpt_restore|restored 0"
             : "|qpt_restore_error|state changed or restore failed"));
    if (ok)
      unlink(journal.c_str());
  }
  for (auto &[p, n] : nodes)
    delete n;
  emit("E|" + std::to_string(ns()) + "|stopped|collector exited");
  return 0;
}
