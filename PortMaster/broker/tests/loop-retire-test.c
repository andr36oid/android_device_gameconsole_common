/* Recycled and empty loop associations must never reach LOOP_CLR_FD. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdarg.h>
#include <sys/wait.h>
#define open loop_open
#define ioctl loop_ioctl
#define main support_main
#include "../../erofs-tools/andr36oid-erofs-support.c"
#undef main
#undef ioctl
#undef open
static int scenario,detached;
int loop_open(const char *path,int flags,...) {
    (void)path;return syscall(SYS_openat,AT_FDCWD,"/dev/null",flags,0);
}
int loop_ioctl(int fd,unsigned long request,...) {
    (void)fd;
    if(request==LOOP_CLR_FD) {
        assert(scenario==0 || scenario==7);
        if(scenario==7){errno=EBUSY;return -1;}
        detached++;return 0;
    }
    assert(request==LOOP_GET_STATUS64);
    if(scenario==5 || scenario==6){errno=scenario==5?ENXIO:EIO;return -1;}
    va_list ap;va_start(ap,request);struct loop_info64 *i=va_arg(ap,void *);va_end(ap);
    memset(i,0,sizeof(*i));i->lo_device=42;i->lo_inode=91;i->lo_offset=512;i->lo_flags=LO_FLAGS_READ_ONLY;
    if(scenario==1)i->lo_device++;
    if(scenario==2)i->lo_inode++;
    if(scenario==3)i->lo_offset++;
    if(scenario==4)i->lo_flags=0;
    return 0;
}
int main(void) {
    for(scenario=0;scenario<8;scenario++) {
        pid_t child=fork();assert(child>=0);
        if(!child) {
            char *args[]={"support","retire-owned","/dev/loop17","42","91","512",NULL};
            int result=support_main(6,args);
            assert(detached==(scenario==0));_exit(result);
        }
        int status;assert(waitpid(child,&status,0)==child && WIFEXITED(status));
        assert(WEXITSTATUS(status)==(scenario>=6?1:0));
    }
    puts("PASS: loop identity matches detach; device/inode/offset/RO mismatches and empty loops do not detach; I/O/busy errors fail");
}
