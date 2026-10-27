package app.morphe.extension.youtube.downloads;
import android.content.Context; import android.content.SharedPreferences; import java.util.HashMap;
public class ProgressFixtures {
 static int checks; static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 static class Memory extends Context implements SharedPreferences,SharedPreferences.Editor {
  final HashMap<String,String> data=new HashMap<>(); public SharedPreferences getSharedPreferences(String n,int m){return this;}
  public String getString(String k,String d){return data.getOrDefault(k,d);} public Editor edit(){return this;}
  public Editor putString(String k,String v){data.put(k,v);return this;} public Editor remove(String k){data.remove(k);return this;} public void apply(){}
 }
 static LocalVideoWatchProgress.Tracker fresh(){return new LocalVideoWatchProgress.Tracker(LocalVideoWatchProgress.Snapshot.empty());}
 static void play(LocalVideoWatchProgress.Tracker t,long from,long through,long duration,long elapsed){t.sample(from,duration,true,elapsed); for(long p=from+1000;p<=through;p+=1000)t.sample(p,duration,true,elapsed+p-from);}
 public static void main(String[]args){
  Memory context=new Memory(); var t=fresh();t.sample(0,20000,true,0);for(int i=1;i<=20;i++)t.sample(i*1000L,20000,i<20,i*1000L);
  ok(t.snapshot().watched,"complete short clip qualifies");ok(t.snapshot().coveredMs==20000,"completion credits final interval");
  var seek=fresh();seek.sample(0,20000,true,0);seek.sample(19000,20000,true,1000);seek.sample(20000,20000,false,2000);
  ok(!seek.snapshot().watched,"seek to end does not qualify");ok(seek.snapshot().coveredMs==1000,"only post-seek media counts");ok(seek.snapshot().progressPercent==100,"position can reach end without viewed flag");
  var back=fresh();play(back,0,10000,20000,0);back.sample(0,20000,true,11000);for(int i=1;i<=10;i++)back.sample(i*1000L,20000,true,11000+i*1000L);
  ok(back.snapshot().coveredMs==10000,"rewatching does not double-count");ok(!back.snapshot().watched,"rewatching first half not full viewing");
  LocalVideoWatchProgress.save(context,"clip",back.snapshot());var restored=LocalVideoWatchProgress.read(context,"clip");ok(restored.positionMs==10000&&restored.coveredMs==10000,"position and intervals persist");
  var resumed=new LocalVideoWatchProgress.Tracker(restored);play(resumed,10000,20000,20000,50000);ok(resumed.snapshot().watched,"coverage resumes across reopen");
  LocalVideoWatchProgress.save(context,"clip",resumed.snapshot());ok(LocalVideoWatchProgress.read(context,"clip").watched,"watched flag derived after roundtrip");
  var invalid=new LocalVideoWatchProgress.Tracker(restored);invalid.sample(0,-1,false,0);ok(invalid.snapshot().positionMs==10000&&invalid.snapshot().coveredMs==10000,"unprepared or failed VideoView cannot erase saved progress");
  var changed=new LocalVideoWatchProgress.Tracker(resumed.snapshot());changed.sample(0,40000,true,0);ok(!changed.snapshot().watched&&changed.snapshot().coveredMs==0,"replacement duration resets coverage");
  var slight=new LocalVideoWatchProgress.Tracker(resumed.snapshot());slight.sample(0,19000,true,0);ok(!slight.snapshot().watched,"one-second shorter replacement does not inherit viewed flag");
  var pause=fresh();pause.sample(0,20000,false,0);pause.sample(2000,20000,false,1000);ok(pause.snapshot().coveredMs==0,"paused seeking never counts");
  var delayed=fresh();delayed.sample(0,20000,true,0);delayed.sample(10000,20000,true,10000);ok(delayed.snapshot().coveredMs==0,"suspended sampling does not infer coverage");
  var threshold=fresh();play(threshold,0,18000,20000,0);ok(!threshold.snapshot().watched,"90percent not full viewing");threshold.sample(19000,20000,true,19000);ok(threshold.snapshot().watched,"95percent qualifies");
  context.data.put("bad","1|20000|20000|0:999999999,");ok(!LocalVideoWatchProgress.read(context,"bad").watched,"corrupt ranges fail closed");
  context.data.put("bad","1|20000|20000|0:10000,0:10000,");ok(LocalVideoWatchProgress.read(context,"bad").coveredMs==10000,"overlapping serialized coverage normalized");
  LocalVideoWatchProgress.clear(context,"clip");ok(LocalVideoWatchProgress.read(context,"clip").positionMs==0&&!LocalVideoWatchProgress.read(context,"clip").watched,"deletion clears progress");
  LocalVideoWatchProgress.save(context,"../unsafe",t.snapshot());ok(!context.data.containsKey("../unsafe"),"invalid identifiers not persisted");
  var regression=fresh();regression.sample(0,20000,true,1000);regression.sample(1000,20000,true,500);ok(regression.snapshot().coveredMs==0,"backward monotonic clock cannot add viewing");
  System.out.println("Progress fixtures passed: "+checks+" assertions");
 }
}
