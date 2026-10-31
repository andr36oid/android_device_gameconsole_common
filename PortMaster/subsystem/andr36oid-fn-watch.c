/* Static guest-side replacement for dArkOSen's evtest shell Fn watcher. */
#define _GNU_SOURCE
#include <dirent.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include "andr36oid-input.h"

static void send_menu_toggle(void) {
    bool ra=false,ra32=false;
    DIR *d=opendir("/proc");if(!d)return;
    struct dirent *e;
    while((e=readdir(d))) {
        if(e->d_name[0]<'0' || e->d_name[0]>'9')continue;
        char path[288],name[32];
        snprintf(path,sizeof(path),"/proc/%s/comm",e->d_name);
        FILE *f=fopen(path,"re");if(!f)continue;
        size_t n=fread(name,1,sizeof(name),f);
        if(!ferror(f)) {
            // comm may contain newlines. Compare the complete byte sequence,
            // including procfs's final newline, rather than its first line.
            if(n==10 && !memcmp(name,"retroarch\n",10))ra=true;
            if(n==12 && !memcmp(name,"retroarch32\n",12))ra32=true;
        }
        fclose(f);
    }
    closedir(d);
    if(!ra && !ra32)return;
    int fd=socket(AF_INET,SOCK_DGRAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0);if(fd<0)return;
    struct sockaddr_in addr={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    if(ra) {
        addr.sin_port=htons(55355);
        sendto(fd,"MENU_TOGGLE\n",12,0,(struct sockaddr *)&addr,sizeof(addr));
    }
    if(ra32) {
        addr.sin_port=htons(55356);
        sendto(fd,"MENU_TOGGLE\n",12,0,(struct sockaddr *)&addr,sizeof(addr));
    }
    close(fd);
}
int main(void) {
    struct hotkey_state keys={0};
    int pad=-1;
    for(;;) {
        if(pad<0) {
            pad=pad_open();
            if(pad<0) {poll(NULL,0,2000);continue;}
            hotkey_sync(pad,true,&keys);
        }
        struct pollfd p={.fd=pad,.events=POLLIN};
        if(poll(&p,1,100)<0) {
            if(errno==EINTR)continue;
            goto disconnected;
        }
        if(p.revents&(POLLHUP|POLLERR|POLLNVAL))goto disconnected;
        if(keys.sync_needed && !keys.dropped)hotkey_sync(pad,true,&keys);
        for(;;) {
            struct input_event ev;
            ssize_t n=read(pad,&ev,sizeof(ev));
            if(n<0 && errno==EINTR)continue;
            if(n<0 && (errno==EAGAIN || errno==EWOULDBLOCK))break;
            if(n!=sizeof(ev))goto disconnected;
            if(hotkey_event(pad,true,&keys,&ev))send_menu_toggle();
        }
        continue;
disconnected:
        close(pad);pad=-1;hotkey_invalidate(&keys,false);
        poll(NULL,0,2000);
    }
}
