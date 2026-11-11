#!/usr/bin/env python3
"""Exercise the compiled vold eviction boundary with controlled Android services."""
from pathlib import Path
import tempfile,subprocess
src=Path(__file__).resolve().parents[6]/'system/vold'
# Relative repo root is seven levels above this file's directory.
if not src.is_dir():src=Path('/andr36oid/src/system/vold')
s=(src/'Utils.cpp').read_text();body=s[s.index('status_t ReleaseReadySubsystem('):s.index('\nstatus_t ForkExecvp(',s.index('status_t ReleaseReadySubsystem('))]
prefix=r'''
#include <map>
#include <string>
#include <vector>
#include <sstream>
#include <cerrno>
#include <cassert>
#include <iostream>
using status_t=int;constexpr int OK=0;
#define LOG(x) std::ostringstream()
static std::map<std::string,std::string> props;static std::vector<std::string> events;
static bool property_ok=true,child_ok=true,clear_mounts=true;
namespace android {namespace base {
std::string GetProperty(const std::string& k,const std::string& fallback) {auto i=props.find(k);return i==props.end()?fallback:i->second;}
bool SetProperty(const std::string& k,const std::string& v) {events.push_back("property "+v);if(!property_ok)return false;props[k]=v;return true;}
}}
status_t ForkExecvp(const std::vector<std::string>& args) {assert(args.size()==3&&args[0]=="/system/bin/sh"&&args[1]=="/system/etc/andr36oid-subsystem.sh");events.push_back("broker "+args[2]);if(clear_mounts)props["sys.andr36oid.subsystem.prestaged"]="0";return child_ok?OK:1;}
'''
main=r'''
int main() {
 for(int mode=0;mode<7;mode++) {
  props.clear();events.clear();property_ok=child_ok=clear_mounts=true;
  if(mode)props["persist.andr36oid.subsystem.prestage"]="1";
  props["sys.andr36oid.subsystem.prestaged"]="1";
  if(!mode)props["sys.andr36oid.subsystem.prestaged"]="0";
  if(mode==2||mode==3)props["sys.andr36oid.subsystem.session"]="running";
  if(mode==4)property_ok=false;if(mode==5)child_ok=false;if(mode==6)clear_mounts=false;
  int rc=ReleaseReadySubsystem(mode==3);
  if(mode==0){assert(rc==OK&&events.empty());}
  else if(mode==2){assert(rc==-EBUSY&&events.empty());}
  else if(mode>=4){assert(rc==-EBUSY);assert(events.size()==(mode==4?1:2));}
  else {assert(rc==OK&&events.size()==2);assert(events[0]==(mode==3?"property shutdown":"property vold"));assert(events[1]==(mode==3?"broker shutdown":"broker unstage"));}
 }
 std::cout<<"PASS: actual vold function: default-off, eviction, active refusal, shutdown, property/child/incomplete failures\n";
}
'''
with tempfile.TemporaryDirectory(prefix='a36-vold-ready-',dir='/ramdisk') as t:
 t=Path(t);(t/'test.cpp').write_text(prefix+body+main)
 subprocess.run(['c++','-std=c++17','-Wall','-Wextra','-Werror','-Wno-misleading-indentation',str(t/'test.cpp'),'-o',str(t/'test')],check=True)
 subprocess.run([str(t/'test')],check=True)
for file,fn,after in [('model/PublicVolume.cpp','ReleaseReadySubsystem()','KillProcessesUsingPath(getPath())'),('VolumeManager.cpp','ReleaseReadySubsystem(true)','vol->destroy()')]:
 text=(src/file).read_text();start=text.index('status_t PublicVolume::doUnmount()' if file.startswith('model') else 'int VolumeManager::shutdown()')
 assert text.index(fn,start)<text.index(after,start)
print('PASS: public eject and internal-only shutdown release resident binds before volume destruction')
