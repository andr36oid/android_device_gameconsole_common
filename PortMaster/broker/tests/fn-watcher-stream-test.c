/* Run the actual native watcher against a scripted, nonblocking evdev client. */
#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <netinet/in.h>
#include <poll.h>
#include <setjmp.h>
#include <stdarg.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <unistd.h>
static int test_open(const char *,int,...);
static int test_close(int);
static int test_ioctl(int,unsigned long,...);
static int test_poll(struct pollfd *,nfds_t,int);
static ssize_t test_read(int,void *,size_t);
static ssize_t test_sendto(int,const void *,size_t,int,const struct sockaddr *,socklen_t);
static int test_socket(int,int,int);
static DIR *test_opendir(const char *);
static struct dirent *test_readdir(DIR *);
#define open test_open
#define close test_close
#define ioctl test_ioctl
#define poll test_poll
#define read test_read
#define sendto test_sendto
#define socket test_socket
#define opendir test_opendir
#define readdir test_readdir
#define main watcher_main
#include "../../subsystem/andr36oid-fn-watch.c"
#undef main
#undef open
#undef close
#undef ioctl
#undef poll
#undef read
#undef sendto
#undef socket
#undef opendir
#undef readdir

static jmp_buf finished;
static struct input_event queue[12];
static unsigned queued,cursor,stage,opens,queries,flushed,fail_left;
static unsigned menus,menus32;
static unsigned closes,backoffs,sockets,socket_closes;
static bool connected,inject_after_query,send_failure;
static int read_fault,socket_fd=-1;
static bool proc_scanned;
static DIR *test_opendir(const char *path) {
    assert(!strcmp(path,"/proc"));proc_scanned=false;return opendir(path);
}
static struct dirent *test_readdir(DIR *dir) {
    (void)dir;
    if(proc_scanned)return NULL;
    proc_scanned=true;
    // Read the real comm of this process only. Other tests or host processes
    // named retroarch must not make the negative matching cases nondeterministic.
    static struct dirent entry;
    snprintf(entry.d_name,sizeof(entry.d_name),"%ld",(long)getpid());return &entry;
}
static unsigned long snapshot[KEY_WORDS];
static void hold(unsigned code) {snapshot[code/KEY_WORD_BITS]|=1UL<<(code%KEY_WORD_BITS);}
static void key(unsigned code,int value,long long time) {
    queue[queued++]=(struct input_event){.type=EV_KEY,.code=code,.value=value,
        .time={.tv_sec=time/1000,.tv_usec=time%1000*1000}};
}
static void syn(unsigned code) {queue[queued++]=(struct input_event){.type=EV_SYN,.code=code};}
static int test_open(const char *path,int flags,...) {
    assert(!strcmp(path,"/dev/input/event0"));
    assert(flags==(O_RDONLY|O_NONBLOCK|O_CLOEXEC));
    assert(!connected);connected=true;
    opens++;
    if(opens>1) {
        memset(snapshot,0,sizeof(snapshot));hold(BTN_TRIGGER_HAPPY5);hold(BTN_TRIGGER_HAPPY2);
        if(opens==3)fail_left=2;
    }
    return 101;
}
static int test_close(int fd) {
    if(fd==101) {assert(connected);connected=false;closes++;return 0;}
    assert(fd==socket_fd);socket_fd=-1;socket_closes++;return close(fd);
}
static int test_socket(int family,int type,int protocol) {
    assert(socket_fd==-1 && family==AF_INET && type==(SOCK_DGRAM|SOCK_CLOEXEC|SOCK_NONBLOCK) && !protocol);
    socket_fd=socket(family,type,protocol);assert(socket_fd>=0);sockets++;return socket_fd;
}
static int test_ioctl(int fd,unsigned long request,...) {
    assert(fd==101);
    va_list ap;va_start(ap,request);void *arg=va_arg(ap,void *);va_end(ap);
    if(request==EVIOCGNAME(128)) {strcpy(arg,"GO-Super Gamepad");return 17;}
    if(request==EVIOCSCLOCKID) {assert(*(int *)arg==CLOCK_MONOTONIC);return 0;}
    assert(request==EVIOCGKEY(sizeof(snapshot)));queries++;
    if(fail_left) {fail_left--;errno=EIO;return -1;}
    memcpy(arg,snapshot,sizeof(snapshot));
    // Model this kernel's snapshot+EV_KEY flush on the SAME client. Leave SYN
    // records queued, just as evdev_handle_get_val/__evdev_flush_queue do.
    unsigned end=cursor;
    for(unsigned i=cursor;i<queued;i++) {
        if(queue[i].type==EV_KEY)flushed++;
        else queue[end++]=queue[i];
    }
    queued=end;
    if(inject_after_query) {
        inject_after_query=false;
        key(BTN_TRIGGER_HAPPY2,0,3400);key(BTN_TRIGGER_HAPPY5,0,3400);syn(SYN_REPORT);
        key(BTN_TRIGGER_HAPPY5,1,3500);key(BTN_TRIGGER_HAPPY5,0,3600);syn(SYN_REPORT);
    }
    return sizeof(snapshot);
}
static int test_poll(struct pollfd *p,nfds_t count,int timeout) {
    if(!p) {assert(!count && timeout==2000 && !connected);backoffs++;return 0;}
    assert(p && count==1 && p->fd==101 && timeout==100);
    assert(cursor==queued);
    cursor=queued=0;p->revents=POLLIN;
    memset(snapshot,0,sizeof(snapshot));
    switch(stage++) {
    case 0: key(BTN_TRIGGER_HAPPY5,1,1000);break;
    case 1: key(BTN_DPAD_UP,1,1100);break;
    case 2: key(BTN_DPAD_UP,0,1200);break;
    case 3: key(BTN_TRIGGER_HAPPY5,0,1300);break;
    case 4: key(BTN_TRIGGER_HAPPY5,1,2000);break;
    case 5: key(BTN_TRIGGER_HAPPY5,0,2100);break;
    case 6: key(BTN_TRIGGER_HAPPY5,1,3000);break;
    case 7:
        inject_after_query=true;
        hold(BTN_TRIGGER_HAPPY5);hold(BTN_TRIGGER_HAPPY2);
        syn(SYN_DROPPED);key(BTN_TRIGGER_HAPPY5,0,3100);syn(SYN_REPORT);
        key(BTN_TRIGGER_HAPPY2,0,3110);key(BTN_TRIGGER_HAPPY5,0,3110);
        break;
    case 8: key(BTN_TRIGGER_HAPPY2,0,3700);key(BTN_TRIGGER_HAPPY5,0,3710);break;
    case 9: key(BTN_TRIGGER_HAPPY5,1,4000);break;
    case 10: p->revents=POLLHUP;return 1;
    case 11: key(BTN_TRIGGER_HAPPY5,0,4200);key(BTN_TRIGGER_HAPPY2,0,4200);break;
    case 12: key(BTN_TRIGGER_HAPPY5,1,5000);break;
    case 13: key(BTN_TRIGGER_HAPPY5,0,5100);break;
    case 14: return 1; // EOF reconnect, without POLLHUP.
    case 15: hold(BTN_TRIGGER_HAPPY5);hold(BTN_TRIGGER_HAPPY2);return 0;
    case 16: hold(BTN_TRIGGER_HAPPY5);hold(BTN_TRIGGER_HAPPY2);return 0;
    case 17: key(BTN_TRIGGER_HAPPY5,0,6200);key(BTN_TRIGGER_HAPPY2,0,6200);break;
    case 18: key(BTN_TRIGGER_HAPPY5,1,7000);break;
    case 19: key(BTN_TRIGGER_HAPPY5,0,7100);break;
    case 20: read_fault=1;return 1;
    case 21: read_fault=2;return 1;
    case 22: errno=ENOMEM;return -1;
    case 23: p->revents=POLLERR;return 1;
    case 24: p->revents=POLLNVAL;return 1;
    default: longjmp(finished,1);
    }
    syn(SYN_REPORT);
    return 1;
}
static ssize_t test_read(int fd,void *out,size_t size) {
    assert(fd==101 && size==sizeof(struct input_event)); // No read-ahead past a resync.
    if(cursor<queued) {memcpy(out,&queue[cursor++],size);return size;}
    if(read_fault==1) {read_fault=0;return size-1;}
    if(read_fault==2) {read_fault=0;errno=ENODEV;return -1;}
    if(stage==15)return 0;
    errno=EAGAIN;return -1;
}
static ssize_t test_sendto(int fd,const void *data,size_t size,int flags,const struct sockaddr *addr,socklen_t len) {
    (void)fd;
    assert(size==12 && !memcmp(data,"MENU_TOGGLE\n",12) && !flags && len==sizeof(struct sockaddr_in));
    const struct sockaddr_in *a=(const struct sockaddr_in *)addr;
    assert(a->sin_family==AF_INET && a->sin_addr.s_addr==htonl(INADDR_LOOPBACK));
    if(send_failure) {errno=ENOBUFS;return -1;}
    if(a->sin_port==htons(55355))menus++;
    else {assert(a->sin_port==htons(55356));menus32++;}
    return size;
}
int main(void) {
    assert(!prctl(PR_SET_NAME,"retroarch"));
    if(!setjmp(finished))watcher_main();
    assert(menus==4 && opens==8 && closes==7 && backoffs==7 && queries>=10 && flushed==2 && !fail_left);
    unsigned before_menu=menus;
    assert(!prctl(PR_SET_NAME,"retroarch\nsuffix"));
    send_menu_toggle();assert(menus==before_menu);
    unsigned before=menus32;
    assert(!prctl(PR_SET_NAME,"retroarch32\nx"));
    send_menu_toggle();assert(menus32==before);
    assert(!prctl(PR_SET_NAME,"retroarch-extra-long"));
    send_menu_toggle();assert(menus==before_menu);
    assert(!prctl(PR_SET_NAME,"retroarch32"));
    send_menu_toggle();assert(menus32==before+1);
    send_failure=true;send_menu_toggle();assert(menus32==before+1);
    assert(socket_fd==-1 && sockets==socket_closes);
    puts("PASS: actual Fn watcher stream, atomic snapshot/queue boundary, HUP/EOF reconnect, idle retry, and RetroArch UDP commands");
}
