"""Observe an actual continuous recording; never synthesize or backfill timestamps."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import time

from power_benchmark import Device, PKG, write_json


def utc():
    return datetime.now(timezone.utc).isoformat()


def memory(device, pid):
    text=device.root(f"cat /proc/{pid}/smaps_rollup /proc/{pid}/io /proc/{pid}/stat")
    def field(name):
        m=re.search(r"^"+re.escape(name)+r":\s*(\d+)",text,re.M)
        return int(m[1]) if m else None
    return {"pid":pid,"pss_kib":field("Pss"),"rss_kib":field("Rss"),
            "write_bytes":field("write_bytes"),"wchar":field("wchar")}


def observe(device, session_id, fallback_collector=0):
    state=device.state()
    events=" ".join(state.get("events",[]))
    pids=re.findall(r"kind=collector, message=uid=0 pid=(\d+)",events)
    collector=int(pids[-1]) if pids else fallback_collector
    app=int(device.adb("shell","pidof",PKG).split()[0])
    app_mem=memory(device,app)
    native_mem=memory(device,collector) if collector else {}
    return {"observed_utc":utc(),"host_monotonic":time.monotonic(),"state":state,
            "app":app_mem,"collector":native_mem,
            "combined_pss_kib":app_mem["pss_kib"]+native_mem["pss_kib"] if native_mem.get("pss_kib") is not None else None,
            "session_valid":state.get("sessionId")==session_id and state.get("recording")=="recording" and state.get("collector") is True}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb",default=os.environ.get("ADB") or shutil.which("adb"))
    p.add_argument("--serial",required=True)
    p.add_argument("--game",required=True)
    p.add_argument("--game-name",default="测试应用")
    p.add_argument("--seconds",type=int,default=7200)
    p.add_argument("--period",type=int,default=60)
    p.add_argument("--warmup",type=int,default=120)
    p.add_argument("--interval",type=int,default=500,choices=(100,200,500,1000))
    p.add_argument("--scenario",required=True)
    p.add_argument("--output",required=True)
    a=p.parse_args()
    out=Path(a.output);out.mkdir(parents=True,exist_ok=False)
    d=Device(a.adb,a.serial)
    if d.state().get("recording","idle")!="idle":
        raise RuntimeError("An unfinished recording exists; do not replace it")
    d.setup("recording",a.game,a.game_name,a.interval)
    state={}
    for _ in range(25):
        state=d.state()
        if state.get("recording")=="recording" and state.get("currentPackage")==a.game:break
        time.sleep(1)
    if state.get("recording")!="recording":raise RuntimeError("Recording did not start")
    session=state["sessionId"]
    started=time.monotonic()
    report={"format":1,"created_utc":utc(),"serial":a.serial,"game":a.game,
            "scenario":a.scenario,"interval_ms":a.interval,"session_id":session,
            "requested_seconds":a.seconds,"period_seconds":a.period,"warmup_seconds":a.warmup,
            "observations":[],"completed":False,"memory_scope":"App and persistent native collector PSS; graphics/system tracing are separate",
            "memory_collection":"/proc/<pid>/smaps_rollup once per observation; this is not CPU power measurement"}
    path=out/"report.json"
    collector=0
    try:
        while True:
            observation=observe(d,session,collector)
            collector=observation["collector"].get("pid",collector)
            observation["elapsed_seconds"]=time.monotonic()-started
            report["observations"].append(observation)
            write_json(path,report)
            with (out/"observations.jsonl").open("a",encoding="utf-8") as stream:
                stream.write(json.dumps(observation,ensure_ascii=False)+"\n")
            print(f"{observation['observed_utc']} elapsed={observation['elapsed_seconds']:.1f}s "
                  f"pss={observation['combined_pss_kib']} KiB valid={observation['session_valid']}",flush=True)
            if not observation["session_valid"]:raise RuntimeError("Session stopped, paused or changed; no automatic restart")
            if observation["elapsed_seconds"]>=a.seconds:break
            deadline=min(started+a.seconds,time.monotonic()+a.period)
            while time.monotonic()<deadline:time.sleep(min(5,deadline-time.monotonic()))
        observations=report["observations"]
        stable=[x for x in observations if x["elapsed_seconds"]>=a.warmup and x["combined_pss_kib"] is not None]
        n=min(5,len(stable)//2)
        if n:
            initial=statistics.median(x["combined_pss_kib"] for x in stable[:n])
            final=statistics.median(x["combined_pss_kib"] for x in stable[-n:])
            report["memory"]={"baseline_median_kib":initial,"final_median_kib":final,
                              "growth_mib":(final-initial)/1024,"window_samples":n,
                              "growth_within_20_mib":final-initial<=20*1024}
        final_state=observations[-1]["state"]
        report["effective_seconds"]=final_state.get("effectiveNs",0)/1e9
        report["completed"]=True
        report["two_hours_effective_verified"]=report["effective_seconds"]>=7200 and a.seconds>=7200
    except BaseException as error:
        report["error"]=str(error)
        raise
    finally:
        d.exit()
        report["finished_utc"]=utc()
        report["service_final"]=d.state()
        try:
            report["saved_record"]=json.loads(d.root(f"cat /data/user/0/{PKG}/files/records/{session}.json"))
        except Exception as error:report["save_check_error"]=str(error)
        report["qpt_final"]=d.root("cat /sys/class/powercap/qpt/enabled").strip()
        write_json(path,report)
    print(json.dumps({k:v for k,v in report.items() if k!="observations"},ensure_ascii=False,indent=2),flush=True)


if __name__=="__main__":main()
