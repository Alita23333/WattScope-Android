"""Power acceptance calculations from measured QPT counters, never CPU utilization.

This is independent of the app, so disabled-app baselines use the same observer.
Raw counters, original zone names, read spans and validity are retained in JSONL.
"""
import json
import math
from pathlib import Path


def valid(value):
    return isinstance(value, (int, float)) and math.isfinite(value) and value >= 0


def cpu_zones(zones):
    """Select explicitly CPU-named domains; never infer debug rails from utilization."""
    candidates = [i for i, z in enumerate(zones)
                  if z["name"] == "cpu" or z["name"].startswith("cpu-")]
    if len({z["path"] for z in zones}) != len(zones):
        raise ValueError("Duplicate zone paths")
    paths = {zones[i]["path"] for i in candidates}
    # If a CPU parent and its child are both named CPU, use the parent once.
    selected = [i for i in candidates if not any(
        zones[i]["path"].startswith(parent + "/") for parent in paths
        if parent != zones[i]["path"])]
    if not selected:
        raise ValueError("No explicitly named CPU QPT domains; power cannot be inferred")
    if len(selected) > 1 and len({zones[i]["parent"] for i in selected}) != 1:
        raise ValueError("CPU domains are not siblings; refuse an unverified aggregate")
    return selected


def integrate(samples, indices, interval_ms):
    energy_j = seconds = 0.0
    resets = gaps = missing = 0
    for a, b in zip(samples, samples[1:]):
        dt = (b["boot_ns"] - a["boot_ns"]) / 1e9
        if dt <= 0 or dt > interval_ms / 1000 * 2.5:
            gaps += 1
            continue
        pairs = [(a["energy_uj"][i], b["energy_uj"][i]) for i in indices]
        if not a.get("enabled") or not b.get("enabled") or not all(
                valid(x) and valid(y) for x, y in pairs):
            missing += 1
            continue
        if any(y < x for x, y in pairs):
            resets += 1
            continue
        # Zero is a legitimate observation. Do not hold or interpolate a failed value.
        energy_j += sum(y - x for x, y in pairs) / 1e6
        seconds += dt
    elapsed = (samples[-1]["boot_ns"] - samples[0]["boot_ns"]) / 1e9 if len(samples) > 1 else 0
    return {"energy_j": energy_j, "valid_seconds": seconds, "elapsed_seconds": elapsed,
            "mean_power_mw": energy_j / seconds * 1000 if seconds else None,
            "valid_coverage": seconds / elapsed if elapsed > 0 else 0,
            "counter_resets": resets, "gaps": gaps, "missing_intervals": missing}


def summarize(metadata, samples):
    selected = cpu_zones(metadata["zones"])
    total = integrate(samples, selected, metadata["interval_ms"])
    rails = {}
    for i, z in enumerate(metadata["zones"]):
        rails[z["path"]] = {"name": z["name"], **integrate(samples, [i], metadata["interval_ms"])}
    def extent(key):
        values = [s.get(key) for s in samples if isinstance(s.get(key), (int, float)) and math.isfinite(s[key])]
        return {"min": min(values), "max": max(values), "first": values[0], "last": values[-1]} if values else None
    return {"measurement_kind": "qpt_cpu_rail_energy", "algorithm_version": 1,
            "cpu_names": [metadata["zones"][i]["name"] for i in selected],
            "cpu_paths": [metadata["zones"][i]["path"] for i in selected],
            "cpu_total": total, "rails": rails, "samples": len(samples),
            "cpu_temp_c": extent("cpu_temp_c"), "battery_temp_c": extent("battery_temp_c"),
            "max_read_span_us": max((s.get("read_span_ns", 0) for s in samples), default=0) / 1000,
            "scope": "CPU domains across all tasks, not per-process or per-physical-core power",
            "power_source": "delta(energy_uj) / valid monotonic duration; no utilization conversion"}


def read_trial(path):
    records = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    metadata = next(r for r in records if r.get("type") == "metadata")
    return summarize(metadata, [r for r in records if r.get("type") == "sample"])


def compare_abba(trials):
    """One block A1/B1/B2/A2. Report a measured difference, not a causal certification."""
    if len(trials) != 4 or [t["mode"] == "baseline" for t in trials] != [True, False, False, True]:
        raise ValueError("Expected baseline / enabled / enabled / baseline")
    if trials[1]["mode"] != trials[2]["mode"]:
        raise ValueError("Enabled modes must match")
    totals = [t["power"]["cpu_total"] for t in trials]
    means = [v["mean_power_mw"] for v in totals]
    adequate = all(m is not None for m in means) and all(v["valid_coverage"] >= .95 for v in totals)
    adequate = adequate and all(t.get("state_valid", False) for t in trials)
    baseline = (means[0] + means[3]) / 2 if all(m is not None for m in means) else None
    enabled = (means[1] + means[2]) / 2 if all(m is not None for m in means) else None
    delta = enabled - baseline if baseline is not None else None
    return {"mode": trials[1]["mode"], "baseline_cpu_mw": baseline, "enabled_cpu_mw": enabled,
            "observed_delta_cpu_mw": delta,
            "observed_delta_percent_of_baseline": 100 * delta / baseline if baseline and delta is not None else None,
            "outer_baseline_drift_mw": means[3] - means[0] if means[0] is not None and means[3] is not None else None,
            "enabled_repeat_difference_mw": means[2] - means[1] if means[1] is not None and means[2] is not None else None,
            "counter_and_state_valid": adequate,
            "power_acceptance_pass": None,
            "interpretation": "Observed ABBA difference; repeat matched workloads and thermal conditions. No watt threshold was agreed."}
