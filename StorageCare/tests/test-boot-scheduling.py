#!/usr/bin/env python3
"""Real scheduling/receiver gate against host Android doubles; no Android writes."""
from pathlib import Path
import subprocess,tempfile
app=Path(__file__).resolve().parents[1]
def function(s,name):
 a=s.index(name);i=s.index('{',a)+1;depth=1
 while depth:depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[a:i]
s=(app/'src/org/andr36oid/storagecare/Checks.java').read_text();code='\n'.join(function(s,n) for n in ['static void schedule(', 'static void soon('])
files={
'android/content/ComponentName.java':'''package android.content; public class ComponentName {public ComponentName(Context c,Class<?> cls){} }''',
'android/content/Context.java':'''package android.content; public class Context {public final android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public final android.app.job.JobScheduler jobs=new android.app.job.JobScheduler();public final android.app.NotificationManager nm=new android.app.NotificationManager();public android.content.pm.PackageManager getPackageManager(){return pm;} public <T>T getSystemService(Class<T> c){return c.cast(c==android.app.job.JobScheduler.class?jobs:nm);} }''',
'android/content/pm/PackageManager.java':'''package android.content.pm; import android.content.ComponentName; public class PackageManager {public static final int COMPONENT_ENABLED_STATE_DEFAULT=0,COMPONENT_ENABLED_STATE_DISABLED=2,DONT_KILL_APP=1;public int state=0,changes;public int getComponentEnabledSetting(ComponentName c){return state;} public void setComponentEnabledSetting(ComponentName c,int s,int flags){if(flags!=DONT_KILL_APP)throw new AssertionError();state=s;changes++;}}''',
'android/os/SystemProperties.java':'''package android.os; public class SystemProperties {public static String gate="1"; public static String get(String k,String fallback){if(!k.equals("persist.andr36oid.ram.boot_storagecare"))throw new AssertionError();return gate;}}''',
'android/app/NotificationManager.java':'''package android.app; public class NotificationManager {public int cancels;public void cancelAll(){cancels++;}}''',
'android/app/job/JobInfo.java':'''package android.app.job; import android.content.ComponentName; public class JobInfo {public int id; public boolean persisted;public long period,latency,deadline; public static class Builder {JobInfo j=new JobInfo(); public Builder(int id,ComponentName c){j.id=id;}public Builder setPeriodic(long n){j.period=n;return this;}public Builder setPersisted(boolean p){j.persisted=p;return this;}public Builder setMinimumLatency(long n){j.latency=n;return this;}public Builder setOverrideDeadline(long n){j.deadline=n;return this;} public JobInfo build(){return j;}}}''',
'android/app/job/JobScheduler.java':'''package android.app.job; public class JobScheduler {public java.util.Map<Integer,JobInfo> jobs=new java.util.HashMap<>();public int schedules; public JobInfo getPendingJob(int id){return jobs.get(id);}public void schedule(JobInfo j){jobs.put(j.id,j);schedules++;}public void cancel(int id){jobs.remove(id);}}''',
'org/andr36oid/storagecare/Checks.java':'''package org.andr36oid.storagecare;import android.content.*;import android.app.*;import android.app.job.*;class Checks {static final int JOB_PERIODIC=3741,JOB_SOON=3742;static final long PERIOD_MS=21600000;static ComponentName component(Context c){return new ComponentName(c,Checks.class);}'''+code+'}',
'org/andr36oid/storagecare/Prefs.java':'''package org.andr36oid.storagecare;import android.content.Context;class Prefs {static boolean on=true;static boolean warn(Context c){return on;}}''',
'org/andr36oid/storagecare/BootReceiver.java':'''package org.andr36oid.storagecare;public class BootReceiver {}''',
'org/andr36oid/storagecare/BootSchedulingTest.java':'''package org.andr36oid.storagecare;import android.content.Context; public class BootSchedulingTest {static void check(boolean x){if(!x)throw new AssertionError();}public static void main(String[]a){Context c=new Context();Checks.schedule(c);check(c.pm.state==0 && c.jobs.jobs.size()==2);check(c.jobs.getPendingJob(3741).persisted && c.jobs.getPendingJob(3741).period==21600000);check(c.jobs.getPendingJob(3742).latency==60000 && c.jobs.getPendingJob(3742).deadline==300000);int n=c.jobs.schedules;Checks.schedule(c);check(c.jobs.schedules==n+1);Prefs.on=false;Checks.schedule(c);check(c.pm.state==2 && c.jobs.jobs.isEmpty() && c.nm.cancels==1);n=c.jobs.schedules;Checks.soon(c);check(c.jobs.schedules==n);android.os.SystemProperties.gate="0";Checks.schedule(c);check(c.pm.state==0 && c.jobs.jobs.isEmpty());Prefs.on=true;android.os.SystemProperties.gate="1";Checks.schedule(c);check(c.pm.state==0 && c.jobs.jobs.size()==2);System.out.println("PASS real StorageCare schedule/soon + BootStart: existing default warning jobs preserved, disabled cancels jobs/notifications, no card wake while off, opt-out restores Java receiver, re-enable resumes");}}'''
}
with tempfile.TemporaryDirectory(prefix='storagecare-boot-') as t:
 root=Path(t);sources=[]
 for name,data in files.items():p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data);sources.append(str(p))
 jdk=Path('/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin')
 subprocess.run([str(jdk/'javac'),'-d',str(root/'classes'),*sources,str(app/'src/org/andr36oid/storagecare/BootStart.java')],check=True)
 subprocess.run([str(jdk/'java'),'-cp',str(root/'classes'),'org.andr36oid.storagecare.BootSchedulingTest'],check=True)
