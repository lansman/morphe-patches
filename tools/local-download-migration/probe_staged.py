#!/usr/bin/env python3
"""Inspect decrypted staging locally; all personal identifiers stay in private JSON."""
from pathlib import Path
import argparse,json,subprocess,hashlib,concurrent.futures,collections
parser=argparse.ArgumentParser();parser.add_argument('stage',type=Path);args=parser.parse_args();stage=args.stage.resolve()
manifest=json.loads((stage/'manifest-private.json').read_text());assert manifest.get('stageComplete') is True
checksum_verified=manifest.get('indexChecksumVerified',manifest.get('checksumVerified',False));assert checksum_verified is True
groups=[g for g in manifest['groups'] if g.get('status')=='staged']
def inspect(g):
 path=(stage/g['file']).resolve();assert path.parent==stage and path.is_file()
 with path.open('rb') as source:digest=hashlib.file_digest(source,'sha256').hexdigest()
 assert digest==g['sha256'],'staged digest mismatch'
 assert path.stat().st_size==g['stagedBytes'],'staged size mismatch'
 proc=subprocess.run(['ffprobe','-v','error','-protocol_whitelist','file','-count_packets','-show_entries','format=format_name,duration,size,start_time:stream=index,codec_type,codec_name,width,height,duration,sample_rate,channels,start_time,time_base,nb_read_packets','-of','json',str(path)],capture_output=True,text=True,timeout=90)
 (stage/(str(g['id'])+'-probe-private.err')).write_text(proc.stderr)
 try:probe=json.loads(proc.stdout)
 except ValueError:probe={}
 return dict(g,probe=probe,probeExit=proc.returncode,probeHadErrors=bool(proc.stderr.strip()))
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:rows=list(pool.map(inspect,groups))
result={'indexVersion':manifest['indexVersion'],'checksumVerified':checksum_verified,'tracks':rows}
p=stage/'probe-tracks-private.json';p.write_text(json.dumps(result,ensure_ascii=False,indent=2));p.chmod(0o600)
counts=collections.Counter(s.get('codec_type','unknown')+':'+s.get('codec_name','unknown') for row in rows for s in row['probe'].get('streams',[]))
print(json.dumps({'staged_groups':len(rows),'recognized':sum(r['probeExit']==0 for r in rows),'groups_with_probe_errors':sum(r['probeHadErrors'] for r in rows),'stream_types':dict(counts),'total_bytes':sum(r['stagedBytes'] for r in rows)}))
