/* SPDX-License-Identifier: MIT */
#include "andr36oid-gamepad.h"
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
int main(int argc,char **argv) {
 unsigned char bits[(KEY_MAX+8)/8]={0};
 if(argc!=2)return 2;
 int fd=open(argv[1],O_RDONLY|O_NONBLOCK|O_CLOEXEC);
 if(fd<0)return 2;
 int ret=ioctl(fd,EVIOCGBIT(EV_KEY,sizeof(bits)),bits);close(fd);
 if(ret<0)return 2;
 bool native=a36_pad_native_bytes(bits,sizeof(bits));
 if(!native)for(size_t i=0;i<A36_PAD_ROLE_COUNT;i++){
  unsigned c=a36_pad_bindings[i].legacy;
  if(!((bits[c/8]>>(c%8))&1U))return 2;
 }
 const enum a36_pad_role roles[]={A36_PAD_A,A36_PAD_B,A36_PAD_X,A36_PAD_Y,
  A36_PAD_R1,A36_PAD_R2,A36_PAD_L1,A36_PAD_L2,A36_PAD_MODE};
 for(size_t i=0;i<sizeof(roles)/sizeof(roles[0]);i++)
  printf("%s%u",i?" ":"",a36_pad_code(roles[i],native));
 putchar('\n');return 0;
}
