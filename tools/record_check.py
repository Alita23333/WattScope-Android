"""Independent reader for committed WattScope blocks; checks raw bytes and gaps."""
import argparse
import bisect
import collections
import hashlib
import json
import math
from pathlib import Path
import struct
import statistics
import zlib


def lines(path):
    with Path(path).open("rb") as stream:
        while True:
            header=stream.read(12)
            if not header:return
            if len(header)!=12:raise ValueError("Incomplete block header")
            magic,size,checksum=struct.unpack(">III",header)
            if magic!=0x57534e31 or not 1<=size<=16777216:raise ValueError("Invalid block header")
            data=stream.read(size)
            if len(data)!=size or zlib.crc32(data)!=checksum:raise ValueError("Incomplete block or CRC mismatch")
            yield from data.decode("utf-8").splitlines()


def check(path,metadata,algorithm_version=None):
    algorithm_version = algorithm_version or metadata.get("algorithmVersion", 3)
    counts=collections.Counter();segments=[];frames=[];issues=collections.Counter();events=[]
    digest=hashlib.sha256()
    with Path(path).open("rb") as stream:
        for data in iter(lambda:stream.read(65536),b""):digest.update(data)
    generation=0;last_committed=0
    for line in lines(path):
        p=line.split("|",6);kind=p[0];counts[kind]+=1
        if kind!='D' and len(p)>1:last_committed=max(last_committed,int(p[1]))
        if kind=="B":segments.append([int(p[1]),None])
        elif kind=="P":
            if segments and segments[-1][1] is None:segments[-1][1]=int(p[1])
        elif kind=="E":
            events.append({"ns":int(p[1]),"kind":p[2],"message":p[3] if len(p)>3 else ""})
            if p[2] in ("collector","layer","frame_error"):generation+=1
        elif kind=="F":frames.append((int(p[1]),p[3],generation))
    last=max(int(metadata["endNs"]),last_committed) if metadata.get('status')=='interrupted' or any(s[1] is None for s in segments) else int(metadata['endNs'])
    for segment in segments:
        if segment[1] is None:segment[1]=last
    starts=[s[0] for s in segments]
    def segment_for(ns):
        i=bisect.bisect_right(starts,ns)-1
        return i if i>=0 and ns<=segments[i][1] else -1
    hardware=0;previous=None;max_gap=0;power={}
    for line in lines(path):
        p=line.split("|",6)
        if p[0]!="H":continue
        ns=int(p[1]);values=json.loads(p[4]);segment=segment_for(ns);hardware+=1
        if algorithm_version >= 5 and 'battery.power' in values:
            raw = values['battery.power']
            values['battery.power'] = abs(raw) if p[3]=='Discharging' and raw is not None else 0.0 if p[3] in ('Charging','Full','Not charging') else None
        if len(p)>6:
            for key,issue in json.loads(p[6]).items():issues[key+":"+issue["state"]]+=1
        if previous:
            dt=(ns-previous[0])/1e9;max_gap=max(max_gap,dt)
            if segment>=0 and previous[3]==segment and 0<dt<=metadata["intervalMs"]/1000*2.5:
                old=previous[1]
                for key in values:
                    if not (key=="battery.power" or key.startswith("qpt.") and not key.endswith(".energy")):continue
                    if key=="battery.power" and (p[3]!="Discharging" or previous[2]!="Discharging"):continue
                    ea,eb=old.get(key+".energy"),values.get(key+".energy")
                    if key.startswith("qpt.") and ea is not None and eb is not None:
                        if ea<0 or eb<ea:continue
                        energy=eb-ea
                    else:
                        a,b=old.get(key),values.get(key)
                        if a is None or b is None or a<0 or b<0:continue
                        energy=(a+b)*.5*dt
                    row=power.setdefault(key,{"energyJ":0.,"coverageSeconds":0.})
                    row["energyJ"]+=energy;row["coverageSeconds"]+=dt
        previous=(ns,values,p[3],segment)
    grouped=collections.defaultdict(list)
    for ns,layer,generation in frames:
        segment=segment_for(ns)
        if segment>=0:grouped[(segment,layer,generation)].append(ns)
    deltas=[];frame_count=0
    for group in grouped.values():
        ts=sorted(set(group));frame_count+=len(ts)
        deltas.extend((b-a)/1e6 for a,b in zip(ts,ts[1:]) if b>a)
    deltas.sort()
    def low(proportion):
        n=max(1,math.ceil(len(deltas)*proportion))
        return 1000*n/sum(deltas[-n:]) if deltas else None
    algorithm_version = algorithm_version or metadata.get("algorithmVersion", 3)
    calibration = []; observed_ns = 0; previous_frame = None
    if algorithm_version >= 4:
        for ns,layer,generation in frames:
            segment = segment_for(ns)
            if segment < 0: continue
            key = (segment,layer,generation)
            if previous_frame and previous_frame[1] == key:
                dt = ns - previous_frame[0]
                if dt <= 0: continue
                calibration.append(dt / 1e6); observed_ns += dt
            previous_frame = (ns,key)
            if observed_ns >= 10_000_000_000 and len(calibration) >= 60: break
        budget = statistics.median(calibration) if observed_ns >= 10_000_000_000 and len(calibration) >= 60 else None
    else:
        budget=1000/metadata["targetFps"]
    summary={"seconds":sum(b-a for a,b in segments)/1e9,"frameCount":frame_count,
             "avgFps":1000*len(deltas)/sum(deltas) if deltas else None,"low1":low(.01),"low5":low(.05),
             "p95Ms":deltas[math.ceil(len(deltas)*.95)-1] if deltas else None,
             "p99Ms":deltas[math.ceil(len(deltas)*.99)-1] if deltas else None,
             "slowFrames":sum(d>budget*1.5 for d in deltas) if budget else None,"severeFrames":sum(d>budget*3 for d in deltas) if budget else None,
             "power":{key:{**p,"averageW":p["energyJ"]/p["coverageSeconds"]} for key,p in power.items()}}
    if algorithm_version >= 4:
        summary['frameBaseline'] = {'method':'median-first-10s-v1', 'status':'ready' if budget else 'insufficient',
            'windowSeconds':10.0, 'normalFps':1000/budget if budget else None, 'budgetMs':budget,
            'observedSeconds':observed_ns/1e9, 'intervalCount':len(calibration)}
    return {"format":1,"rawSha256":digest.hexdigest(),"crc_valid":True,"row_counts":dict(counts),
            "segments":segments,"max_hardware_gap_seconds":max_gap,"hardware_samples":hardware,
            "metric_issues":dict(issues),"events":events,"summary":summary}


def compare_summary(independent, app):
    """Compare independently recomputed fields; empty discharge coverage stays missing."""
    differences=[]
    def same(key,a,b):
        if a is None or b is None:
            okay=a is None and b is None
        else:
            okay=math.isclose(a,b,rel_tol=1e-9,abs_tol=1e-7)
        if not okay:differences.append({"field":key,"independent":a,"app":b})
    for key in ('seconds','frameCount','avgFps','low1','low5','p95Ms','p99Ms','slowFrames','severeFrames'):
        same(key,independent.get(key),app.get(key))
    if 'frameBaseline' in independent or 'frameBaseline' in app:
        a=independent.get('frameBaseline',{});b=app.get('frameBaseline',{})
        for key in ('normalFps','budgetMs','observedSeconds','intervalCount'):
            same('frameBaseline.'+key,a.get(key),b.get(key))
        for key in ('method','status'):
            if a.get(key)!=b.get(key):differences.append({'field':'frameBaseline.'+key,'independent':a.get(key),'app':b.get(key)})
    for key in set(independent['power'])|set(app['power']):
        a=independent['power'].get(key,{"coverageSeconds":0})
        b=app['power'].get(key,{"coverageSeconds":0})
        for field in ('energyJ','coverageSeconds','averageW'):
            same(key+'.'+field,a.get(field),b.get(field))
    return {"matches":not differences,"differences":differences}


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--raw",required=True);p.add_argument("--metadata",required=True);p.add_argument("--output",required=True);p.add_argument("--app-validation");p.add_argument("--algorithm-version",type=int,choices=(1,2,3,4));a=p.parse_args()
    result=check(a.raw,json.loads(Path(a.metadata).read_text(encoding="utf-8")),a.algorithm_version)
    if a.app_validation:
        validation=json.loads(Path(a.app_validation).read_text(encoding="utf-8"))
        result['app_comparison']=compare_summary(result['summary'],validation['fullSummary'])
    Path(a.output).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in result.items() if k not in ("events","summary")},ensure_ascii=False,indent=2))
