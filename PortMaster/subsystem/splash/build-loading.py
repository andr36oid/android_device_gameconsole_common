#!/usr/bin/env python3
"""Lossless blueprint pack from the current PNG directory. Never renders art.
The first legacy 640x480 BGRA frame remains a readable, independent fallback.
Each size shares an exact RGB palette; each sheet is a separate LZ4 block, so
random selection reads/decompresses one sheet, not an entire 36-frame archive.
"""
from pathlib import Path
from PIL import Image
import argparse,hashlib,io,json,os,re,struct,tempfile
import numpy as np
import lz4.block

SIZES=['640x480','720x720','1024x768','480x320','854x480','1280x720','1280x800','1024x600']
PREFIX=640*480*4
HEADER=struct.Struct('<8sIIIIQ')
GROUP=struct.Struct('<IIIIQII')
FRAME=struct.Struct('<IIQQII')

def build(png_dir,logo,out,sizes):
 groups=[];manifest=[];frame_count=0
 for size in sizes:
  width,height=map(int,size.split('x'));pixels=[];unique=[]
  for nn in range(1,37):
   path=png_dir/f'sheet-{nn:02d}-{size}.png';data=path.read_bytes()
   im=Image.open(io.BytesIO(data));im.load()
   if im.size!=(width,height):raise ValueError(f'{path}: dimensions do not match filename')
   if (im.mode in ('RGBA','LA') or 'transparency' in im.info) and im.convert('RGBA').getextrema()[3]!=(255,255):raise ValueError(f'{path}: transparent art needs an explicit approved background')
   rgb=np.asarray(im.convert('RGB'));colors=(rgb[:,:,0].astype(np.uint32)<<16)|(rgb[:,:,1].astype(np.uint32)<<8)|rgb[:,:,2]
   pixels.append(colors);unique.append(np.unique(colors));manifest.append(dict(file=path.name,sha256=hashlib.sha256(data).hexdigest(),bytes=len(data)))
  palette=np.unique(np.concatenate(unique));codec=1 if len(palette)<=65536 else 2
  palette_bytes=palette.astype('<u4').tobytes() if codec==1 else b''
  blocks=[]
  for nn,colors in enumerate(pixels,1):
   decoded=np.searchsorted(palette,colors).astype('<u2').tobytes() if codec==1 else colors.astype('<u4').tobytes()
   compressed=lz4.block.compress(decoded,mode='high_compression',compression=9,store_size=False)
   assert lz4.block.decompress(compressed,uncompressed_size=len(decoded))==decoded
   if codec==1:assert np.array_equal(palette[np.frombuffer(decoded,dtype='<u2')].reshape(height,width),colors)
   blocks.append((nn,compressed,len(decoded)))
  groups.append(dict(width=width,height=height,palette=palette_bytes,palette_count=len(palette) if codec==1 else 0,codec=codec,blocks=blocks))
  frame_count+=len(blocks)
 fallback=logo.read_bytes()[:PREFIX]
 if len(fallback)!=PREFIX:raise ValueError('legacy logo is shorter than one frame')
 meta=HEADER.size+GROUP.size*len(groups)+FRAME.size*frame_count;offset=PREFIX+meta;data=bytearray();gt=[];ft=[]
 for i,g in enumerate(groups):
  po=offset+len(data);data.extend(g['palette']);first=len(ft)
  for nn,block,raw_size in g['blocks']:
   ft.append(FRAME.pack(nn,i,offset+len(data),len(block),raw_size,0));data.extend(block)
  gt.append(GROUP.pack(g['width'],g['height'],g['palette_count'],len(g['blocks']),po,first,g['codec']))
 if offset+len(data)>512*1024*1024:raise ValueError('loading pack exceeds the helper file-size bound')
 payload=fallback+HEADER.pack(b'A36LOAD1',1,len(groups),frame_count,0,offset+len(data))+b''.join(gt)+b''.join(ft)+data
 out.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile(dir=out.parent,prefix=out.name+'.',delete=False) as f:
  tmp=Path(f.name);f.write(payload)
 tmp.chmod(0o644);tmp.replace(out)
 result=dict(format=1,png_dir=str(png_dir),sizes=sizes,sheets_per_size=36,source_files=manifest,bytes=len(payload),sha256=hashlib.sha256(payload).hexdigest(),legacy_prefix_bytes=PREFIX,metadata_bytes=meta,groups=[dict(size=f"{g['width']}x{g['height']}",palette_colors=g['palette_count'],codec='palette16-lz4' if g['codec']==1 else 'xrgb32-lz4',palette_bytes=len(g['palette']),compressed_sheet_bytes=sum(len(b) for _,b,_ in g['blocks'])) for g in groups])
 out.with_suffix(out.suffix+'.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='source_files'},indent=2))

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--png-dir',type=Path,required=True);ap.add_argument('--logo',type=Path,default=Path(__file__).parent/'andr36oid-splash-640x480.raw');ap.add_argument('--output',type=Path,default=Path(__file__).parent/'prebuilt/andr36oid-loading.pack');ap.add_argument('--sizes',default='all',help='all discovered sizes, or comma-separated WxH sizes');a=ap.parse_args()
 available={m[1] for p in a.png_dir.glob('sheet-*-*.png') if (m:=re.fullmatch(r'sheet-[0-9]{2}-([0-9]+x[0-9]+)\.png',p.name))}
 sizes=[x for x in SIZES if x in available]+sorted(available-set(SIZES)) if a.sizes=='all' else a.sizes.split(',')
 if not sizes or len(sizes)>16 or len(set(sizes))!=len(sizes) or any(not re.fullmatch(r'[1-9][0-9]*x[1-9][0-9]*',x) or x not in available for x in sizes):ap.error('choose 1..16 complete PNG sizes present in the input directory')
 for size in sizes:
  w,h=map(int,size.split('x'))
  if w>8192 or h>8192 or w*h>16*1024*1024:ap.error('panel size exceeds the helper bounds')
 build(a.png_dir,a.logo,a.output,sizes)
if __name__=='__main__':main()
