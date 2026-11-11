"""Independent matched CPU-domain power trials on the selected rooted Android device.

Default order is A1/B1/B2/A2 for each mode. Raw QPT energy is read by one
persistent observer in every trial. CPU utilization is never read or converted.
The test starts/stops only WattScope, and launches the selected game.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time

from power_stats import compare_abba, read_trial

PKG = "io.github.alita23333.wattscope"
SERVICE = PKG + "/.MonitorService"
REMOTE = "/data/local/tmp/wattscope-next-validation/cpu-power-probe"
MODES = ("recording", "small", "osd-gauge", "osd-text", "both")


def utc():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, data):
    path = Path(path)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


class Device:
    def __init__(self, adb, serial):
        self.command = [adb, "-s", serial]

    def adb(self, *args, timeout=25):
        return subprocess.check_output(self.command + list(args), timeout=timeout).decode("utf-8", "replace")

    def root(self, command):
        return self.adb("shell", "su -c " + shlex.quote(command))

    def state(self):
        text = self.adb("shell", "dumpsys", "activity", "service", SERVICE)
        if "Failure while dumping" in text:
            raise RuntimeError("App state query failed (possibly a frozen process): " + text.strip())
        begin = text.find("{")
        return json.loads(text[begin:]) if begin >= 0 else {}

    def exit(self):
        if self.state():
            self.root("am start-foreground-service -n " + SERVICE + " -a exit")
            for _ in range(30):
                if not self.state():
                    return
                time.sleep(.3)
            raise RuntimeError("WattScope did not finish saving/stopping")

    def environment(self, game):
        values = {"game_package":game}
        for namespace, key in (("system", "screen_brightness"), ("system", "screen_brightness_mode"),
                               ("system", "peak_refresh_rate"), ("system", "min_refresh_rate")):
            values[key] = self.adb("shell", "settings", "get", namespace, key).strip()
        values["battery_status"] = self.root("cat /sys/class/power_supply/battery/status").strip()
        values["supply_class"] = "external" if values["battery_status"] in ("Charging","Full","Not charging") else values["battery_status"]
        values["battery_capacity"] = self.root("cat /sys/class/power_supply/battery/capacity").strip()
        values["qpt_enabled"] = self.root("cat /sys/class/powercap/qpt/enabled").strip()
        pids = self.adb("shell", "pidof", game).strip().split()
        values["game_pid"] = int(pids[0]) if pids else None
        values["game_cpuset"] = self.root("cat /proc/" + pids[0] + "/cpuset").strip() if pids else None
        # Keep read-only environment evidence; never fix clocks or thermal policy for a benchmark.
        return values

    def setup(self, mode, game, name, interval):
        self.adb("shell","cmd","statusbar","collapse")
        # Use the real user path. Some device freezer policies leave shell-cold-started
        # service processes inside a frozen UID cgroup even after startForegroundService.
        self.adb("shell","am","start","-f","0x20000000","-n",PKG+"/.MainActivity","--es","testAction","plain")
        time.sleep(1)
        self.exit()
        if mode!="baseline":
            self.root(f"am start-foreground-service -n {SERVICE} -a benchmark --es mode {shlex.quote(mode)} --ei interval {interval}")
            time.sleep(2)
        self.adb("shell", "monkey", "-p", game, "-c", "android.intent.category.LAUNCHER", "1")


def state_valid(mode, start, end, env0, env1, seconds):
    foreground = all("/top-app" in (e.get("game_cpuset") or "") for e in (env0, env1))
    stable = all(env0.get(k) == env1.get(k) for k in (
        "screen_brightness", "screen_brightness_mode", "peak_refresh_rate", "min_refresh_rate", "supply_class"))
    if mode == "baseline":
        return foreground and stable and not start.get("collector") and not end.get("collector")
    small, osd = mode in ("small", "both"), mode in ("osd-gauge", "osd-text", "both")
    running = all(s.get("collector") and s.get("recording") == "recording" and
                  s.get("small") == small and s.get("osd") == osd and
                  s.get("currentPackage")==env0["game_package"] for s in (start, end))
    continuous = (start.get("sessionId") == end.get("sessionId") and
                  end.get("effectiveNs", 0) - start.get("effectiveNs", 0) >= (seconds - 2) * 1e9)
    return foreground and stable and running and continuous


def run_trial(device, output, label, mode, args):
    print(f"{utc()} preparing {label}: {mode}", flush=True)
    device.setup(mode, args.game, args.game_name, args.app_interval)
    for _ in range(args.warmup):
        time.sleep(1)
    env0 = device.environment(args.game)
    state0 = device.state()
    if mode!="baseline" and (not state0.get("collector") or state0.get("recording")!="recording"):
        raise RuntimeError("Requested recording is not active; refusing an idle power measurement: "+json.dumps(state0,ensure_ascii=False))
    path = output / (label + ".jsonl")
    started = utc()
    print(f"{started} measuring {label} for {args.seconds}s", flush=True)
    with path.open("wb") as out, path.with_suffix(".stderr.txt").open("wb") as err:
        proc = subprocess.Popen(device.command + ["exec-out", "su -c " + shlex.quote(
            f"exec {REMOTE} --duration {args.seconds} --interval {args.probe_interval}")], stdout=out, stderr=err)
        try:
            # Bounded waits allow the outer client to send progress updates or interrupt.
            while proc.poll() is None:
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    pass
        except BaseException:
            proc.terminate()
            proc.wait(timeout=10)
            raise
        if proc.returncode:
            raise RuntimeError(f"Observer failed ({proc.returncode}); see {path.with_suffix('.stderr.txt')}")
    ended = utc()
    state1 = device.state()
    env1 = device.environment(args.game)
    power = read_trial(path)
    result = {"label": label, "mode": mode, "started_utc": started, "ended_utc": ended,
              "power": power, "state_start": state0, "state_end": state1,
              "environment_start": env0, "environment_end": env1,
              "state_valid": state_valid(mode, state0, state1, env0, env1, args.seconds),
              "raw_file": path.name, "raw_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    write_json(output / (label + ".json"), result)
    print(f"{ended} {label}: {power['cpu_total']['mean_power_mw']:.3f} mW, "
          f"coverage={power['cpu_total']['valid_coverage']:.3%}, state_valid={result['state_valid']}", flush=True)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default=os.environ.get("ADB") or shutil.which("adb"))
    p.add_argument("--serial", required=True)
    p.add_argument("--game", required=True)
    p.add_argument("--game-name", default="测试游戏")
    p.add_argument("--modes", nargs="+", choices=MODES, default=list(MODES))
    p.add_argument("--seconds", type=int, default=120)
    p.add_argument("--warmup", type=int, default=30)
    p.add_argument("--app-interval", type=int, choices=(100, 200, 500, 1000), default=500)
    p.add_argument("--probe-interval", type=int, choices=(100, 200, 500, 1000), default=500)
    p.add_argument("--repeats", type=int, default=1)
    p.add_argument("--scenario", required=True, help="Literal observed scenario, e.g. title screen; never claim gameplay without observing it")
    p.add_argument("--output", required=True)
    args = p.parse_args()
    if not args.adb or args.seconds < 5 or args.repeats < 1:
        p.error("ADB, seconds >= 5 and repeats >= 1 are required")
    if not re.fullmatch(r"[A-Za-z0-9_.]+", args.game):
        p.error("Invalid game package")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    device = Device(args.adb, args.serial)
    if device.state().get("recording", "idle") != "idle":
        raise RuntimeError("A recording is active; finish it explicitly before a power trial")
    binary = Path(__file__).parent / "build" / "cpu-power-probe"
    if not binary.exists():
        raise RuntimeError("Build tools/build-power-probe.ps1 first")
    device.adb("shell", "mkdir", "-p", str(Path(REMOTE).parent).replace("\\", "/"))
    device.adb("push", str(binary), REMOTE)
    device.adb("shell", "chmod", "755", REMOTE)
    original_qpt = device.root("cat /sys/class/powercap/qpt/enabled").strip()
    if original_qpt not in ("0", "1"):
        raise RuntimeError("Cannot determine QPT initial state")
    report = {"format": 1, "kind": "qpt_cpu_rail_energy_abba", "created_utc": utc(),
              "serial": args.serial, "game": args.game, "scenario": args.scenario,
              "app_interval_ms": args.app_interval, "probe_interval_ms": args.probe_interval,
              "seconds_per_trial": args.seconds, "warmup_seconds_per_trial": args.warmup,
              "qpt_original": original_qpt, "observer_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
              "app_package": device.adb("shell", "dumpsys", "package", PKG),
              "cpu_utilization_used": False, "trials": [], "blocks": [], "complete": False,
              "limitations": ["CPU rails include all tasks, not per-process power",
                              "Independent observer and QPT remain enabled in both baseline and test",
                              "Game, external overlays and background tasks may vary; repeat matched scenes",
                              "No power pass/fail threshold has been agreed; no automatic pass"]}
    report_path = output / "report.json"
    write_json(report_path, report)
    try:
        if original_qpt == "0":
            device.root("printf 1 > /sys/class/powercap/qpt/enabled")
        if device.root("cat /sys/class/powercap/qpt/enabled").strip() != "1":
            raise RuntimeError("QPT failed to enable")
        for mode in args.modes:
            for repeat in range(args.repeats):
                block = []
                for n, trial_mode in enumerate(("baseline", mode, mode, "baseline")):
                    label = f"{mode}-r{repeat + 1}-{n + 1}"
                    trial = run_trial(device, output, label, trial_mode, args)
                    block.append(trial)
                    report["trials"].append(trial)
                    write_json(report_path, report)
                report["blocks"].append(compare_abba(block))
                write_json(report_path, report)
        report["complete"] = True
    finally:
        device.exit()
        current = device.root("cat /sys/class/powercap/qpt/enabled").strip()
        if original_qpt == "0" and current == "1":
            device.root("printf 0 > /sys/class/powercap/qpt/enabled")
        report["qpt_final"] = device.root("cat /sys/class/powercap/qpt/enabled").strip()
        report["finished_utc"] = utc()
        write_json(report_path, report)
    print(json.dumps(report["blocks"], ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
