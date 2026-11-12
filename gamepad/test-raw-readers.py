#!/usr/bin/env python3
"""Execute production raw-reader paths with legacy/native capability+event fixtures.
No device access: ioctl/read fixtures, compiled unchanged extracted functions.
"""
from pathlib import Path
import os, subprocess, tempfile
common=Path(__file__).resolve().parents[1];root=common.parents[2]
def function(text, signature):
 start=text.index(signature);brace=text.index('{',start);at=brace+1;depth=1
 while depth:
  depth+=(text[at]=='{')-(text[at]=='}');at+=1
 return text[start:at]
with tempfile.TemporaryDirectory(prefix='a36-raw-readers-') as temp:
 out=Path(temp)
 def run(name,text,extra=(),c=False):
  src=out/(name+('.c' if c else '.cpp'));src.write_text(text);binary=out/name
  subprocess.run(['cc' if c else 'c++','-std=gnu11' if c else '-std=c++17','-Wall','-Wextra','-Werror','-I'+str(common/'gamepad/include'),'-I'+str(common/'UsbMode/controller'),'-I'+str(common/'joyMouse/src'),str(src),*map(str,extra),'-o',str(binary)],check=True)
  subprocess.run([str(binary)],check=True)
 main=(common/'UsbMode/controller/main.cpp').read_text()
 # Compile the actual Input fields and Inputs::state, including the conversion
 # at the daemon boundary (Controller::button itself accepts legacy codes).
 state=function(main,'    State state() const')
 inp=main[main.index('struct Input {'):main.index('\nclass Inputs {')]
 assert 'd.normalized = a36_pad_native_words(d.supported.data(), d.supported.size());' in main
 run('usb-joymouse',r'''
#include <algorithm>
#include <initializer_list>
#include <cassert>
#include <cstdio>
#include <array>
#include <vector>
#include <sys/ioctl.h>
#include "Controller.h"
#include "Buttons.h"
using namespace controller;
constexpr size_t kBits=sizeof(unsigned long)*8;
template<size_t N> bool bit(const std::array<unsigned long,N>& b,unsigned c){return b[c/kBits]&(1UL<<(c%kBits));}
'''+inp+'\nstruct Inputs { std::vector<Input> devices;\n'+state+r'''
};
int main(){
 const int expected[]={B,A,Y,X,LB,RB,LT,RT,L3,R3,Back,Start,Guide,Up,Down,Left,Right};
 for(bool native:{false,true}){
  Input in{};in.fd=-1;joymouse::KeyBits supported;
  for(const auto& b:a36_pad_bindings){unsigned c=native?b.canonical:b.legacy;in.supported[c/kBits]|=1UL<<(c%kBits);supported.set(c);}
  in.normalized=a36_pad_native_words(in.supported.data(),in.supported.size());assert(in.normalized==native);
  joymouse::ButtonMap mapping(supported);assert(mapping.normalized()==native);
  Inputs inputs;inputs.devices.push_back(in);
  for(const auto& b:a36_pad_bindings){
   unsigned c=a36_pad_code(b.role,native);auto got=mapping.button(c);assert(got && unsigned(*got)==unsigned(b.role));
   inputs.devices[0].keys.fill(0);inputs.devices[0].keys[c/kBits]|=1UL<<(c%kBits);
   auto s=inputs.state();for(int n=0;n<Count;n++)assert(s.buttons[n]==(n==expected[b.role]));
  }
  auto chord=joymouse::defaultToggleChord(mapping);assert(chord.size()==2 && chord[0]==joymouse::Button::L3 && chord[1]==joymouse::Button::R3);
 }
 // Generic external pads don't acquire native extra-key aliases.
 joymouse::KeyBits generic;generic.set(BTN_SELECT);generic.set(BTN_START);generic.set(BTN_THUMBL);generic.set(BTN_THUMBR);
 joymouse::ButtonMap external(generic);assert(!external.normalized() && !external.button(704));
 puts("PASS USB daemon actual state + joyMouse actual ButtonMap: both layouts, all17 buttons, native Select/Start/L3/R3/Fn, external passthrough");
}
''',[common/'UsbMode/controller/Controller.cpp',common/'joyMouse/src/Buttons.cpp'])
 recovery=(root/'bootable/recovery/minui/events.cpp').read_text()
 funcs='\n'.join(function(recovery,sig) for sig in ['int ev_get_input(', 'int ev_sync_key_state(', 'void ev_iterate_available_keys('])
 run('recovery',r'''
#include <algorithm>
#include <initializer_list>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <functional>
#include <vector>
#include <cerrno>
#include <cstdarg>
#include <unistd.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <linux/input.h>
static unsigned char supported[(KEY_MAX+8)/8],held[sizeof(supported)];
static input_event sample{};
static int fixture_ioctl(int,unsigned long request,...){
 va_list ap;va_start(ap,request);void* target=va_arg(ap,void*);va_end(ap);
 unsigned nr=_IOC_NR(request),size=_IOC_SIZE(request);
 if(nr==0x20){memset(target,0,size);static_cast<unsigned char*>(target)[0]|=1U<<EV_KEY;}
 else if(nr==0x20+EV_KEY || nr==0x18){size=std::min<unsigned>(size,sizeof(supported));memcpy(target,nr==0x18?held:supported,size);}
 else return -1;
 return size;
}
static ssize_t fixture_read(int,void* target,size_t count){assert(count==sizeof(sample));memcpy(target,&sample,count);return count;}
#define ioctl fixture_ioctl
#include "andr36oid-gamepad.h"
#define read fixture_read
constexpr size_t BITS_TO_LONGS(size_t n){return (n+8*sizeof(unsigned long)-1)/(8*sizeof(unsigned long));}
static bool test_bit(size_t n,unsigned long* b){return b[n/(8*sizeof(unsigned long))]&(1UL<<(n%(8*sizeof(unsigned long))));}
struct FdInfo {int fd=7;};static FdInfo ev_fdinfo[1];static size_t g_ev_dev_count=1;static int g_epoll_fd=-1;
using ev_set_key_callback=std::function<void(int,int)>;
'''+funcs+r'''
int main(){for(bool native:{false,true}){
 memset(supported,0,sizeof(supported));for(const auto& b:a36_pad_bindings){unsigned c=a36_pad_code(b.role,native);supported[c/8]|=1U<<(c%8);}
 std::vector<int> available;ev_iterate_available_keys([&](int code){available.push_back(code);});
 assert(available.size()==A36_PAD_ROLE_COUNT);
 for(const auto& b:a36_pad_bindings){
  unsigned c=a36_pad_code(b.role,native);sample={};sample.type=EV_KEY;sample.code=c;sample.value=1;input_event ev{};
  assert(ev_get_input(7,EPOLLIN,&ev)==0 && ev.code==b.legacy && ev.value==1);
  memset(held,0,sizeof(held));held[c/8]|=1U<<(c%8);int synced=-1;
  ev_sync_key_state([&](int key,int value){assert(value==1 && synced==-1);synced=key;});assert(synced==int(b.legacy));
  assert(std::find(available.begin(),available.end(),b.legacy)!=available.end());
 }
}puts("PASS recovery production event, held-key snapshot and available-key paths: both layouts, all17 roles");}
''')
 # Real keyheld consumer with IO stubs: no /dev or /sys is read by this test.
 keyheld=(common/'PortMaster/subsystem/andr36oid-keyheld.c').read_text()
 run('keyheld',r'''
#include <assert.h>
#include <stdarg.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <glob.h>
#include <limits.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include "andr36oid-gamepad.h"
static unsigned char caps[(KEY_MAX+8)/8],down[sizeof(caps)];
static int fixture_open(const char* p,int flags,...){(void)flags;return !strcmp(p,"/dev/input/fixture")?7:-1;}
static int fixture_close(int fd){(void)fd;return 0;}
static char* fixture_realpath(const char* path,char* target){(void)path;(void)target;return NULL;}
static int fixture_ioctl(int fd,unsigned long request,...){(void)fd;va_list ap;va_start(ap,request);void* p=va_arg(ap,void*);va_end(ap);unsigned nr=_IOC_NR(request),size=_IOC_SIZE(request);
 if(nr==0x02){struct input_id* id=p;memset(id,0,sizeof(*id));id->bustype=BUS_HOST;id->vendor=0x484b;id->product=0x1100;}
 else if(nr==0x06)snprintf(p,size,"GO-Super Gamepad");
 else if(nr==0x20+EV_KEY || nr==0x18)memcpy(p,nr==0x18?down:caps,size<sizeof(caps)?size:sizeof(caps));
 else return -1;
 return size;}
#define open fixture_open
#define close fixture_close
#define realpath fixture_realpath
#define ioctl fixture_ioctl
#define main keyheld_unused_main
'''+keyheld+r'''
#undef main
int main(void){for(int native=0;native<2;native++){
 memset(caps,0,sizeof(caps));for(size_t i=0;i<A36_PAD_ROLE_COUNT;i++){unsigned c=a36_pad_code(a36_pad_bindings[i].role,native);caps[c/8]|=1U<<(c%8);}
 for(size_t i=0;i<A36_PAD_ROLE_COUNT;i++){memset(down,0,sizeof(down));unsigned c=a36_pad_code(a36_pad_bindings[i].role,native);down[c/8]|=1U<<(c%8);assert(!!start_held("/dev/input/fixture")==(a36_pad_bindings[i].role==A36_PAD_START));}
}puts("PASS real START-held reader: both layouts, Fn/Select never mistaken for START");}
''',c=True)
 # Coordinator-owned boot-log fix is tested without changing its source.
 bootlog=(root/'frameworks/base/cmds/bootanimation/BootLogScreen.cpp').read_text()
 select=bootlog[bootlog.index('                int select = -1;'):bootlog.index('                if (select >= 0)')]
 run('bootlog',r'''
#include <cassert>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include "andr36oid-gamepad.h"
static unsigned char supported[(KEY_MAX+8)/8];
static bool testBit(const unsigned char* bits,unsigned c){return bits[c/8]&(1U<<(c%8));}
static int fixture_ioctl(int,unsigned long,void* out){memcpy(out,supported,sizeof(supported));return sizeof(supported);}
#undef ioctl
#define ioctl fixture_ioctl
int selector(){int fd=7;unsigned char bits[sizeof(supported)]={};
'''+select+r'''
return select;}
int main(){for(bool native:{false,true}){memset(supported,0,sizeof(supported));for(const auto& b:a36_pad_bindings){unsigned c=a36_pad_code(b.role,native);supported[c/8]|=1U<<(c%8);}assert(selector()==int(a36_pad_code(A36_PAD_SELECT,native)));}
 memset(supported,0,sizeof(supported));supported[704/8]|=1U<<(704%8);assert(selector()==-1);
 puts("PASS coordinator boot-log Select detection: legacy and native pad, isolated extra key rejected");}
''')
 # Java screens receive EventHub's stable public scan code, not raw evdev.
 hub=(root/'frameworks/native/services/inputflinger/reader/EventHub.cpp').read_text()
 assert 'a36_pad_native_bytes(device->keyBitmask, sizeof(device->keyBitmask))\n                                ? a36_pad_to_legacy(iev.code) : iev.code;' in hub
 print('PASS Android Java raw-scan screens: EventHub shared conversion present; kernel codes stay native outside Android')
