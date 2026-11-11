/* Exercise the same processed-libudev monitor API used by SDL2, in the VM. */
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
static void mark(const char *path) {int fd=open(path,O_CREAT|O_WRONLY,0644);assert(fd>=0);close(fd);}
#define LOAD(ret,name,args) ret (*name)args=dlsym(lib,#name);assert(name)
int main(void) {
 void *lib=dlopen("/lib/libudev.so.1",RTLD_NOW);if(!lib){fprintf(stderr,"dlopen: %s\n",dlerror());return 1;}
 LOAD(void *,udev_new,(void));LOAD(void *,udev_monitor_new_from_netlink,(void *,const char *));
 LOAD(int,udev_monitor_filter_add_match_subsystem_devtype,(void *,const char *,const char *));
 LOAD(int,udev_monitor_enable_receiving,(void *));LOAD(int,udev_monitor_get_fd,(void *));
 LOAD(void *,udev_monitor_receive_device,(void *));LOAD(void *,udev_device_unref,(void *));
 LOAD(const char *,udev_device_get_property_value,(void *,const char *));LOAD(const char *,udev_device_get_action,(void *));
 LOAD(const char *,udev_device_get_devnode,(void *));
 LOAD(void *,udev_enumerate_new,(void *));LOAD(int,udev_enumerate_add_match_subsystem,(void *,const char *));
 LOAD(int,udev_enumerate_scan_devices,(void *));LOAD(void *,udev_enumerate_unref,(void *));
 void *u=udev_new();assert(u);void *m=udev_monitor_new_from_netlink(u,"udev");assert(m);
 assert(!udev_monitor_filter_add_match_subsystem_devtype(m,"input",NULL));assert(!udev_monitor_enable_receiving(m));
 void *e=udev_enumerate_new(u);assert(e && !udev_enumerate_add_match_subsystem(e,"input") && !udev_enumerate_scan_devices(e));udev_enumerate_unref(e);
 // Initial enumeration has completed BEFORE the parent creates the mapper.
 mark("/monitor-ready");int input=-1;char path[128]={0};
 for(int i=0;i<100;i++) {
  struct pollfd p={.fd=udev_monitor_get_fd(m),.events=POLLIN};
  if(poll(&p,1,100)<=0)continue;
  void *d=udev_monitor_receive_device(m);if(!d)continue;
  const char *action=udev_device_get_action(d),*node=udev_device_get_devnode(d);
  if(action && node && !strcmp(action,"add") && input<0) {
   const char *mouse=udev_device_get_property_value(d,"ID_INPUT_MOUSE");
   const char *keyboard=udev_device_get_property_value(d,"ID_INPUT_KEYBOARD");
   const char *key=udev_device_get_property_value(d,"ID_INPUT_KEY");
   if(mouse && keyboard && key && !strcmp(mouse,"1") && !strcmp(keyboard,"1") && !strcmp(key,"1")) {
    input=open(node,O_RDONLY|O_NONBLOCK|O_CLOEXEC);assert(input>=0);snprintf(path,sizeof(path),"%s",node);
    mark("/monitor-open");
    struct pollfd ev={.fd=input,.events=POLLIN};assert(poll(&ev,1,1000)==1);
    struct input_event events[16];ssize_t n=read(input,events,sizeof(events));bool x=false,y=false,k=false;
    for(ssize_t j=0;j<n/(ssize_t)sizeof(events[0]);j++) {
     if(events[j].type==EV_REL && events[j].code==REL_X && events[j].value==19)x=true;
     if(events[j].type==EV_REL && events[j].code==REL_Y && events[j].value==-9)y=true;
     if(events[j].type==EV_KEY && events[j].code==KEY_ENTER && events[j].value==1)k=true;
    }
    assert(x && y && k);mark("/monitor-got-input");
   }
  } else if(action && node && input>=0 && !strcmp(action,"remove") && !strcmp(node,path)) {
   close(input);udev_device_unref(d);mark("/monitor-done");puts("REAL_LIBUDEV_LATE_MOUSE_ADD_XY_KEY_REMOVE_PASS");return 0;
  }
  udev_device_unref(d);
 }
 fprintf(stderr,"No processed late mouse event delivered\n");return 2;
}
