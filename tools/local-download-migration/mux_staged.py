#!/usr/bin/env python3
"""Build verified local-library copies. Does not connect to or modify the phone."""
from pathlib import Path
import json,subprocess,hashlib,re,collections,time,concurrent.futures,argparse
p=argparse.ArgumentParser();p.add_argument('stage',type=Path);args=p.parse_args();stage=args.stage.resolve()
data=json.loads((stage/'probe-tracks-private.json').read_text());assert data['checksumVerified'] is True
videos=json.loads((stage/'videos-private.json').read_text()).get('rows',[])
stream_meta=json.loads((stage/'streams-private.json').read_text()).get('rows',[])
video_map={}
for video in videos:
 vid=video.get('videoId','')
 if not re.fullmatch(r'[A-Za-z0-9_-]{11}',vid):continue
 score=lambda x:(bool(x.get('title')),bool(x.get('channel')),not bool(x.get('deleted')),int(x.get('savedTimestamp',0)))
 if vid not in video_map or score(video)>score(video_map[vid]):video_map[vid]=video
tracks=collections.defaultdict(list);unmapped=[];rejected=[]
for track in data['tracks']:
 if track['probeExit'] or track['probeHadErrors']:
  rejected.append({'group':track['id'],'reason':'probe_error'});continue
 key=track['cacheKey'];matches=[vid for vid in video_map if vid in key]
 if len(matches)!=1:
  candidates=re.findall(r'(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])',key)
  if len(set(candidates))==1:matches=list(set(candidates))
 if len(matches)!=1:unmapped.append(track['id']);continue
 vid=matches[0];items=track['probe'].get('streams',[])
 if len(items)!=1 or items[0].get('codec_type') not in ['video','audio']:
  rejected.append({'group':track['id'],'reason':'unexpected_stream_layout'});continue
 technical=items[0];length=track.get('declaredContentLength',-1);confidence='index_length'
 if length<=0:
  candidates=[m for m in stream_meta if m.get('videoId')==vid and re.search(r'(?<!\d)'+re.escape(str(m.get('itag')))+r'(?!\d)',key) and bool(m.get('audioOnly'))==(technical['codec_type']=='audio')]
  totals={m.get('bytesTotal') for m in candidates if m.get('bytesTotal',0)>0 and m.get('bytesTransferred',0)>=m.get('bytesTotal',0)}
  if len(totals)==1:length=totals.pop();confidence='native_sql_length'
 if length!=track['stagedBytes']:
  rejected.append({'group':track['id'],'reason':'incomplete_or_unverified_length'});continue
 fmt=track['probe'].get('format',{});duration=float(fmt.get('duration',technical.get('duration',0)) or 0)
 if duration<=0 or int(technical.get('nb_read_packets',0) or 0)<=0:
  rejected.append({'group':track['id'],'reason':'duration_or_packet_count_missing'});continue
 tracks[vid].append(dict(track,technical=technical,duration=duration,completeness=confidence))
pairs=[]
for vid,items in tracks.items():
 video=[t for t in items if t['technical']['codec_type']=='video'];audio=[t for t in items if t['technical']['codec_type']=='audio']
 if not video or not audio:
  rejected.append({'videoId':vid,'reason':'missing_audio_or_video'});continue
 v=max(video,key=lambda t:(t['technical'].get('height',0),t['stagedBytes']));a=max(audio,key=lambda t:t['stagedBytes'])
 if abs(v['duration']-a['duration'])>max(1.0,.005*max(v['duration'],a['duration'])):
  rejected.append({'videoId':vid,'reason':'track_duration_mismatch'});continue
 pairs.append((vid,v,a))
out=stage/'library-ready';out.mkdir(exist_ok=True);now=int(time.time()*1000)
def run(cmd,label,vid,timeout=60):
 # Restrict all media inputs to local files; probing never contacts a server.
 if cmd[0].endswith('ffprobe'):cmd=cmd[:1]+['-protocol_whitelist','file']+cmd[1:]
 else:
  bounded=[]
  for part in cmd:
   if part=='-i':bounded.extend(['-protocol_whitelist','file'])
   bounded.append(part)
  cmd=bounded
 r=subprocess.run(cmd,capture_output=True,text=True,timeout=timeout)
 (out/(vid+'-'+label+'-private.err')).write_text(r.stderr)
 if r.returncode or r.stderr.strip():raise RuntimeError(label+'_failed')
 return r

def build(job):
 vid,v,a=job;target=out/(vid+'.mp4');temp=out/(vid+'.tmp.mp4')
 try:
  run(['ffmpeg','-hide_banner','-loglevel','error','-nostdin','-i',str(stage/v['file']),'-i',str(stage/a['file']),'-map','0:v:0','-map','1:a:0','-map_metadata','-1','-c','copy','-movflags','+faststart','-y',str(temp)],'mux',vid)
  result=run(['ffprobe','-v','error','-count_packets','-show_entries','format=duration,size:stream=codec_type,codec_name,width,height,nb_read_packets','-of','json',str(temp)],'mux-probe',vid)
  probe=json.loads(result.stdout);ss=probe.get('streams',[]);assert len(ss)==2 and {s['codec_type'] for s in ss}=={'audio','video'}
  duration=float(probe['format']['duration']);assert abs(duration-max(v['duration'],a['duration']))<=max(1,.005*duration)
  for index,seek in enumerate([0,duration/2,max(0,duration-2)]):
   run(['ffmpeg','-hide_banner','-loglevel','error','-nostdin','-threads','2','-ss',str(seek),'-i',str(temp),'-t','2','-map','0:v:0','-map','0:a:0','-f','null','-'],'decode-'+str(index),vid)
  target_existed=target.exists();temp.replace(target)
  run(['ffmpeg','-hide_banner','-loglevel','error','-nostdin','-ss',str(min(30,duration/10)),'-i',str(target),'-frames:v','1','-vf','scale=480:-2','-q:v','3','-y',str(out/(vid+'.jpg'))],'thumbnail',vid)
  meta=video_map.get(vid,{})
  saved=meta.get('savedTimestamp',0)
  if 1e9<saved<1e12:saved*=1000
  if not 1e12<saved<1e14:saved=now
  native_duration=meta.get('durationSeconds',0) or 0
  display_duration=int(native_duration) if abs(float(native_duration)-duration)<=max(2,.01*duration) else int(round(duration))
  metadata={'videoId':vid,'title':meta.get('title') or 'Восстановленное видео','channel':meta.get('channel') or '', 'durationSeconds':display_duration,'height':v['technical'].get('height',0),'downloadedAt':int(saved)}
  mp=out/(vid+'.json');mp.write_text(json.dumps(metadata,ensure_ascii=False,indent=2));mp.chmod(0o600)
  with target.open('rb') as source:digest=hashlib.file_digest(source,'sha256').hexdigest()
  return {'videoId':vid,'status':'verified','videoGroup':v['id'],'audioGroup':a['id'],'bytes':target.stat().st_size,'sha256':digest,'durationSeconds':duration,'height':metadata['height'],'videoCodec':v['technical'].get('codec_name'),'audioCodec':a['technical'].get('codec_name'),'sampleDecodePositions':3,'hasTitle':bool(meta.get('title')),'hasChannel':bool(meta.get('channel')),'replacedLocalResult':target_existed}
 except Exception as e:
  return {'videoId':vid,'status':'failed','reason':str(e) if isinstance(e,RuntimeError) else type(e).__name__}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(build,pairs))
summary={'createdAt':now,'results':results,'unmappedGroups':unmapped,'rejected':rejected}
p=stage/'migration-results-private.json';p.write_text(json.dumps(summary,ensure_ascii=False,indent=2));p.chmod(0o600)
print(json.dumps({'planned_videos':len(pairs),'verified_videos':sum(x['status']=='verified' for x in results),'failed_videos':sum(x['status']=='failed' for x in results),'unmapped_groups':len(unmapped),'rejected':len(rejected),'verified_bytes':sum(x.get('bytes',0) for x in results if x['status']=='verified')}))
