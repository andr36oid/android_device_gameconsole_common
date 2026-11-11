#!/usr/bin/env python3
"""All shipped DTBs -> actual source normalization/registration -> consumer indexes.

No kernel, device or disk image is built. Compiles unchanged extracted production
functions against host kernel doubles; these are source-level proofs, not GPIO or
ADC hardware validation. Dynamic user extra-GPIO/remap choices remain native.
"""
from pathlib import Path
import argparse,ctypes,hashlib,json,re,struct,subprocess,tempfile,xml.etree.ElementTree as ET
CANON=[304,305,307,308,310,311,312,313,544,545,546,547,704,705,706,707,708]
EXPECTED={'up':8,'down':9,'left':10,'right':11,'select':12,'start':13,'leftthumb':14,'rightthumb':15,'hotkeyenable':16}
CODES={'up':544,'down':545,'left':546,'right':547,'select':704,'start':705,'leftthumb':706,'rightthumb':707,'hotkeyenable':708}

def fdt(path):
 b=path.read_bytes();h=struct.unpack_from('>10I',b);assert h[0]==0xd00dfeed;at,strings=h[2:4];stack=[];nodes={}
 while True:
  op=struct.unpack_from('>I',b,at)[0];at+=4
  if op==1:
   end=b.index(0,at);stack.append(b[at:end].decode());at=(end+4)&~3;nodes['/'+'/'.join(stack[1:])]={}
  elif op==2:stack.pop()
  elif op==3:
   size,name=struct.unpack_from('>II',b,at);at+=8;end=b.index(0,strings+name);nodes['/'+'/'.join(stack[1:])][b[strings+name:end].decode()]=b[at:at+size];at=(at+size+3)&~3
  elif op==4:pass
  elif op==9:return nodes
  else:raise AssertionError(op)
def words(b):return list(struct.unpack('>'+'I'*(len(b)//4),b))
def string(b):return b.rstrip(b'\0').decode()
def function(text,name):
 m=re.search(r'(?:static (?:inline )?)?(?:int|bool) '+name+r'\([^;]*?\)\s*\{',text,re.S);assert m,name
 depth=1;end=m.end()
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[m.start():end]+'\n'
def es(path):
 configs=[x for x in ET.parse(path).getroot().findall('inputConfig') if x.get('deviceName')=='GO-Super Gamepad'];assert len(configs)==1,path
 return {x.get('name'):int(x.get('id')) for x in configs[0].findall('input') if x.get('type')=='button'}
def ra(text):return {k:int(v) for k,v in re.findall(r'^input_(\w+)_btn\s*=\s*"(\d+)"',text,re.M)}
def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--dtbs',type=Path,required=True);ap.add_argument('--kernel',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--stock-root',type=Path,action='append',default=[]);a=ap.parse_args();here=Path(__file__).resolve().parent;common=here.parents[2];root=common/'PortMaster';helper=root/'broker/andr36oid-guest.sh';source=(a.kernel/'drivers/input/joystick/odroidgo3-joypad.c').read_text();keymap=a.kernel/'include/linux/andr36oid-keymap.h';fragments=''.join(function(keymap.read_text(),name) for name in ['a36_rg351_core_key','a36_rg351_output_key','a36_android_to_rg351_key'])+function(source,'joypad_input_setup')
 assert 'joypad->no_sticks = device_property_present(dev, "andr36oid,no-sticks")' in source
 assert 'adc->max = (ADC_MAX_VOLTAGE / 2)' in source and '#define\tADC_MAX_VOLTAGE\t\t1800' in source
 assert 'adc->max *= adc->scale' in source and 'adc->min *= adc->scale' in source
 script=helper.read_text();start=script.index('input_driver = "udev"\ninput_device = "GO-Super Gamepad"');runtime_ra=ra(script[start:script.index('\nEOF',start)]);tracked_ra=ra((root/'subsystem/input/retroarch/GO-Super-Gamepad.cfg').read_text());assert runtime_ra==tracked_ra
 es_map=es(root/'subsystem/input/es_input.cfg')
 for name,value in EXPECTED.items():assert es_map[name]==value,(name,es_map)
 for name,value in [('up',8),('down',9),('left',10),('right',11),('select',12),('start',13),('l3',14),('r3',15),('enable_hotkey',12),('menu_toggle',16)]:assert runtime_ra[name]==value,(name,runtime_ra)
 assert 'input_vendor_id = "18507"' in script and 'input_product_id = "4352"' in script
 files=sorted(a.dtbs.glob('*.dtb'));assert len(files)==54,('expected final packaged54 set',len(files));rows=[]
 with tempfile.TemporaryDirectory(prefix='a36-pad-contract-') as temp:
  tmp=Path(temp);(tmp/'kernel-fragments.h').write_text(fragments);lib=tmp/'contract.so';subprocess.run(['cc','-shared','-fPIC','-std=c11','-O2','-Wall','-Wextra','-Werror','-I'+str(tmp),str(here/'fixtures/native-pad/contract.c'),'-o',str(lib)],check=True);c=ctypes.CDLL(str(lib));c.registration.argtypes=[ctypes.c_int]*3+[ctypes.POINTER(ctypes.c_int)]+[ctypes.c_int]*3
  for p in files:
   nodes=fdt(p);path,pad=next((path,n) for path,n in nodes.items() if b'odroidgo3-joypad\0' in n.get('compatible',b''));assert pad.get('status',b'okay\0')!=b'disabled\0';native=pad.get('andr36oid,keycode-set',b'android\0')==b'rg351\0';codes=[words(n['linux,code'])[0] for q,n in nodes.items() if q.startswith(path+'/') and q.count('/')==path.count('/')+1 and 'linux,code'in n];no_sticks='andr36oid,no-sticks'in pad;scale=words(pad['button-adc-scale'])[0];fuzz=words(pad['button-adc-fuzz'])[0];flat=words(pad['button-adc-flat'])[0];vector=(ctypes.c_int*len(codes))(*codes);assert c.registration(native,no_sticks,len(codes),vector,scale,fuzz,flat)==0,p.name
   presented=[i for i in range(768) if c.has_key(i)];axes=[i for i in range(64) if c.has_abs(i)];expected=CANON+([709] if p.name=='rk3326-r50s-android.dtb' else []);assert presented==expected,(p.name,presented);assert axes==([] if no_sticks else [0,1,3,4]),(p.name,axes);assert bool(c.has_abs_event())==bool(axes),p.name
   for axis in axes:assert [c.abs_info(axis,f) for f in range(4)]==[-1800,1800,32,32],(p.name,axis)
   # SDL enumerates BTN_JOYSTICK..KEY_MAX first, then low key codes. All
   # presented native keys are in that first group; RA udev order is identical.
   buttons=sorted(x for x in presented if x>=0x120)+sorted(x for x in presented if x<0x120);indices={k:i for i,k in enumerate(buttons)}
   for name,code in CODES.items():assert indices[code]==EXPECTED[name]==es_map[name],(p.name,name)
   split=[]
   for q,n in nodes.items():
    if n.get('compatible',b'').split(b'\0')[0]==b'adc-joystick' and n.get('status',b'okay\0')!=b'disabled\0':
     split=[words(child['linux,code'])[0] for childpath,child in nodes.items() if childpath.startswith(q+'/') and 'linux,code'in child];assert sorted(split)==[0,1,3,4],(p.name,split)
   if p.name=='rk3326-r36s-clone-d28s.dtb':assert no_sticks and axes==[] and not c.has_abs_event() and split==[]
   if p.name=='rk3326-r36s-clone-okroll.dtb':assert no_sticks and axes==[] and sorted(split)==[0,1,3,4]
   if p.name=='rk3326-r50s-android.dtb':assert indices[709]==17 and 17 not in es_map.values() and 17 not in runtime_ra.values()
   rows.append(dict(file=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),dt_domain='rg351' if native else 'android',dt_codes=codes,presented_keys=presented,presented_abs=axes,presented_ev_abs=bool(axes),separate_play_joystick_abs=sorted(split),extra_index=indices.get(709),standard_indices_match=True))
 stock=[]
 for directory in a.stock_root:
  f=directory/'etc/emulationstation/es_input.cfg';mapping=es(f)
  for name,value in EXPECTED.items():
   if name in mapping:assert mapping[name]==value,(str(f),name)
  checks=[]
  for f in directory.rglob('*'):
   if not f.is_file() or not (('GO-Super' in f.name or 'go_super' in f.name) and f.suffix in ['.cfg','.xml']):continue
   text=f.read_text(errors='replace')
   mapped_17=bool(re.search(r'button="17"|_btn\s*=\s*"17"|bind\d+\s*=\s*17:',text));assert not mapped_17,('stock maps extra17: review instead of silently dropping',str(f));checks.append(dict(file=str(f.relative_to(directory)),sha256=hashlib.sha256(f.read_bytes()).hexdigest(),extra17_mapped=False))
  stock.append(dict(root=str(directory),GO_Super_files=checks,es_indices=mapping))
 result=dict(status='PASS',scope='source-extracted real normalization + real input registration over all packaged DTBs; not hardware ADC/GPIO validation',kernel_source=str(a.kernel),keymap_sha256=hashlib.sha256(keymap.read_bytes()).hexdigest(),driver_sha256=hashlib.sha256(source.encode()).hexdigest(),compiled_functions=['a36_rg351_core_key','a36_rg351_output_key','a36_android_to_rg351_key','joypad_input_setup'],dtb_count=len(rows),dtb_set=[x['file'] for x in rows],boards=rows,stock=stock,virtual_pad_cancelled=True,external_pads='unchanged native passthrough',templates_sha256={str(f.relative_to(common)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [root/'subsystem/input/es_input.cfg',root/'subsystem/input/retroarch/GO-Super-Gamepad.cfg']});a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2)+'\n');print('NATIVE_PAD_CONTRACT_PASS',len(rows),'DTBs; D28S EV_ABS absent; OkRoll split sticks remain separate; R50S709 index17 unmapped; native ES/RA match')
if __name__=='__main__':main()
