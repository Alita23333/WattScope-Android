import collections,json,pathlib,struct
b=pathlib.Path('evidence/wsn-method.trace').read_bytes();p=b.index(b'*end\n')+5
header=b[:p].decode('utf-8','replace');methods={};threads={};section=''
for line in header.splitlines():
 if line.startswith('*'):section=line;continue
 cols=line.split('\t')
 try:
  if section=='*threads':threads[int(cols[0])]=' '.join(cols[1:])
  if section=='*methods':methods[int(cols[0],16)]=' '.join(cols[1:4])
 except:pass
magic,version,offset=struct.unpack_from('<4sHH',b,p);size=struct.unpack_from('<H',b,p+16)[0]
assert magic==b'SLOW' and version==3 and size==14,(magic,version,size)
stacks=collections.defaultdict(list);last={};cost=collections.defaultdict(collections.Counter)
for i in range(p+offset,len(b)-size+1,size):
 tid,key,cpu,wall=struct.unpack_from('<HIII',b,i);mid=key&~3;action=key&3;stack=stacks[tid]
 if tid in last and stack and cpu>=last[tid]:cost[tid][stack[-1]]+=cpu-last[tid]
 last[tid]=cpu
 if action==0:stack.append(mid)
 elif stack:
  if mid in stack:
   while stack and stack[-1]!=mid:stack.pop()
   if stack:stack.pop()
r={threads.get(tid,str(tid)):{'cpu_seconds':sum(c.values())/1e6,'top':[(methods.get(k,hex(k)),v/1e6)for k,v in c.most_common(20)]}for tid,c in cost.items()}
pathlib.Path('evidence/method-hotspots.json').write_text(json.dumps(r,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(r.get('main'),ensure_ascii=False,indent=2))
