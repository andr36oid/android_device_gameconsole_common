/* Run the supervisor's actual reader without starting a payload or signalling
 * host processes. All input and clock syscalls below are scripted. */
#define _GNU_SOURCE
#include <assert.h>
#include <poll.h>
#include <setjmp.h>
#include <stdarg.h>
#include <dirent.h>
int stream_poll(struct pollfd *,nfds_t,int);
DIR *stream_opendir(const char *);
#define open stream_open
#define close stream_close
#define ioctl stream_ioctl
#define read stream_read
#define poll stream_poll
#define clock_gettime stream_clock_gettime
#define fork stream_fork
#define waitpid stream_waitpid
#define kill stream_kill
#define access stream_access
#define prctl stream_prctl
#define usleep stream_usleep
#define opendir stream_opendir
#define main guest_program_main
#include "../../subsystem/andr36oid-guest.c"
#undef main
#undef open
#undef close
#undef ioctl
#undef read
#undef poll
#undef clock_gettime
#undef fork
#undef waitpid
#undef kill
#undef access
#undef prctl
#undef usleep
#undef opendir

static jmp_buf finished;
static struct input_event queue[16];
static unsigned queued,cursor,stage,opens,closes,queries,flushed,fail_left;
static unsigned fn,start,select_key;
static unsigned long snapshot[KEY_WORDS];
static long long test_ms,closed_ms;
static bool connected,inject_releases;
static int read_fault;
static unsigned sleeps;
static bool fail_absent_poll;
static bool policy_case,emergency_case,recovered_case;
static int expected_hold;
static unsigned game_exits;
static long long chord_ms;
static void hold(unsigned code) {snapshot[code/KEY_WORD_BITS]|=1UL<<(code%KEY_WORD_BITS);}
static void key(unsigned code,int value) {
    assert(queued<sizeof(queue)/sizeof(*queue));
    queue[queued++]=(struct input_event){.type=EV_KEY,.code=code,.value=value,
        .time={.tv_sec=test_ms/1000,.tv_usec=test_ms%1000*1000}};
}
static void syn(unsigned code) {
    assert(queued<sizeof(queue)/sizeof(*queue));
    queue[queued++]=(struct input_event){.type=EV_SYN,.code=code};
}
int stream_open(const char *path,int flags,...) {
    assert(!strcmp(path,"/dev/input/event0"));
    assert(flags==(O_RDONLY|O_NONBLOCK|O_CLOEXEC) && !connected);
    if(opens)assert(test_ms-closed_ms>=2000);
    connected=true;opens++;
    memset(snapshot,0,sizeof(snapshot));
    if(!policy_case && opens==2) {hold(fn);hold(start);fail_left=2;}
    if(!policy_case && opens==3) {hold(fn);hold(start);inject_releases=true;}
    return 101;
}
int stream_close(int fd) {
    assert(fd==101 && connected);
    connected=false;closes++;closed_ms=test_ms;
    return 0;
}
int stream_ioctl(int fd,unsigned long request,...) {
    assert(fd==101 && connected);
    va_list ap;va_start(ap,request);void *arg=va_arg(ap,void *);va_end(ap);
    if(request==EVIOCGNAME(128)) {strcpy(arg,"GO-Super Gamepad");return 17;}
    if(request==EVIOCSCLOCKID) {assert(*(int *)arg==CLOCK_MONOTONIC);return 0;}
    assert(request==EVIOCGKEY(sizeof(snapshot)));queries++;
    if(fail_left) {fail_left--;errno=EIO;return -1;}
    memcpy(arg,snapshot,sizeof(snapshot));
    unsigned end=cursor;
    for(unsigned i=cursor;i<queued;i++) {
        if(queue[i].type==EV_KEY)flushed++;
        else queue[end++]=queue[i];
    }
    queued=end;
    if(inject_releases) {
        inject_releases=false;
        // These edges arrive AFTER the snapshot/flush. A post-query drain
        // loses them, leaving a false escape chord held indefinitely.
        key(fn,0);key(start,0);syn(SYN_REPORT);
    }
    return sizeof(snapshot);
}
int stream_poll(struct pollfd *p,nfds_t count,int timeout) {
    assert(count==1 && timeout==50);
    test_ms+=timeout;p->revents=0;
    if(p->fd<0) {
        assert(!connected);
        if(fail_absent_poll) {fail_absent_poll=false;errno=ENOMEM;return -1;}
        return 0;
    }
    assert(p->fd==101 && connected);
    if(cursor!=queued) {p->revents=POLLIN;return 1;}
    cursor=queued=0;
    if(policy_case) {
        assert(!stopping && game_exits==(stage>=3 && !emergency_case ? 1U : 0U));
        switch(stage++) {
        case 0:
            chord_ms=test_ms;
            if(recovered_case) {
                hold(select_key);hold(start);if(emergency_case)hold(fn);
                syn(SYN_DROPPED);key(start,0);syn(SYN_REPORT);
            } else {
                key(select_key,1);key(start,1);if(emergency_case)key(fn,1);
                syn(SYN_REPORT);
            }
            p->revents=POLLIN;return 1;
        case 1:
            // Environment changes after admission cannot alter this session's
            // policy, including when the chord was recovered by SYN_DROPPED.
            assert(setenv("A36_EXIT_HOLD_MS",expected_hold==1200?"6000":"1200",1)==0);
            test_ms=chord_ms+(emergency_case?2000:expected_hold)-1;return 0;
        case 2: test_ms=chord_ms+(emergency_case?2000:expected_hold);return 0;
        case 3:
            assert(!emergency_case);
            test_ms=chord_ms+expected_hold+1;return 0;
        case 4: test_ms=chord_ms+7999;return 0;
        case 5: test_ms=chord_ms+8000;return 0;
        default: assert(!"missing full-return action");
        }
    }
    switch(stage++) {
    case 0:
        hold(fn);hold(start);inject_releases=true;
        syn(SYN_DROPPED);key(fn,0);syn(SYN_REPORT);
        key(fn,0);key(start,0);syn(SYN_REPORT);
        break;
    case 1: test_ms+=5000;return 0; // Lost fresh releases would now force return.
    case 2: p->revents=POLLIN|POLLHUP;return 1;
    case 3: assert(opens==2 && fail_left==1);return 0;
    case 4: assert(!fail_left);return 0; // Idle retry must recover the snapshot.
    case 5: key(fn,0);key(start,0);syn(SYN_REPORT);break;
    case 6: read_fault=1;return 1; // EOF, without POLLHUP.
    case 7: assert(opens==3);test_ms+=5000;return 0;
    case 8: read_fault=2;return 1; // Short read.
    case 9: assert(opens==4);read_fault=3;return 1; // ENODEV, without POLLHUP.
    case 10: assert(opens==5);errno=EINTR;return -1;
    case 11: errno=ENOMEM;fail_absent_poll=true;return -1;
    case 12: assert(opens==6 && sleeps==2);return 0;
    default: longjmp(finished,1);
    }
    p->revents=POLLIN;return 1;
}
ssize_t stream_read(int fd,void *out,size_t size) {
    assert(fd==101 && connected && size==sizeof(struct input_event));
    if(cursor<queued) {memcpy(out,&queue[cursor++],size);return size;}
    int fault=read_fault;read_fault=0;
    if(fault==1)return 0;
    if(fault==2)return size-1;
    errno=fault==3?ENODEV:EAGAIN;return -1;
}
int stream_clock_gettime(clockid_t clock,struct timespec *out) {
    assert(clock==CLOCK_MONOTONIC);
    *out=(struct timespec){.tv_sec=test_ms/1000,.tv_nsec=test_ms%1000*1000000};return 0;
}
pid_t stream_fork(void) {return 42;}
pid_t stream_waitpid(pid_t pid,int *status,int options) {
    (void)status;assert(pid==-1 && options==WNOHANG);return 0;
}
int stream_kill(pid_t pid,int sig) {
    assert(policy_case && pid==-1 && sig==SIGTERM && stopping);
    assert(test_ms==chord_ms+(emergency_case?2000:8000));
    assert(game_exits==(emergency_case?0U:1U));
    longjmp(finished,1);
}
DIR *stream_opendir(const char *path) {
    // exit_game probes /proc once. Stub it to observe the request without
    // inspecting or signalling host processes.
    assert(policy_case && !emergency_case && !strcmp(path,"/proc"));
    assert(test_ms==chord_ms+expected_hold && game_exits++==0);
    return NULL;
}
int stream_access(const char *path,int mode) {(void)path;(void)mode;return -1;}
int stream_prctl(int option,...) {assert(option==PR_ANDR36OID_GUEST);return 0;}
int stream_usleep(useconds_t usec) {assert(usec==50000);test_ms+=50;sleeps++;return 0;}
int main(void) {
    for(volatile int native=0;native<=1;native++) {
        fn=native?BTN_TRIGGER_HAPPY5:KEY_HOMEPAGE;
        start=native?BTN_TRIGGER_HAPPY2:BTN_START;
        queued=cursor=stage=opens=closes=queries=flushed=fail_left=0;
        test_ms=10000;closed_ms=0;connected=inject_releases=false;read_fault=0;stopping=0;
        sleeps=0;fail_absent_poll=false;
        char *argv[]={"unused",NULL};
        if(!setjmp(finished))supervise(argv,native,true);
        assert(opens==6 && closes==5 && queries==9 && flushed==2 && !stopping);
    }
    const char *values[]={NULL,"","1200","2000","3000","4000","5000","6000",
        "6001","8000","-1","6000junk"," 6000","06000","9999999999999999999"};
    const int durations[]={1200,1200,1200,2000,3000,4000,5000,6000,
        1200,1200,1200,1200,1200,1200,1200};
    for(volatile int native=0;native<=1;native++) {
        fn=native?BTN_TRIGGER_HAPPY5:KEY_HOMEPAGE;
        start=native?BTN_TRIGGER_HAPPY2:BTN_START;
        select_key=native?BTN_TRIGGER_HAPPY1:BTN_SELECT;
        for(volatile unsigned choice=0;choice<sizeof(values)/sizeof(*values);choice++) {
            for(volatile int scenario=0;scenario<4;scenario++) {
                if(values[choice])assert(setenv("A36_EXIT_HOLD_MS",values[choice],1)==0);
                else assert(unsetenv("A36_EXIT_HOLD_MS")==0);
                policy_case=true;emergency_case=scenario>=2;recovered_case=scenario%2;
                expected_hold=durations[choice];game_exits=0;
                queued=cursor=stage=opens=closes=queries=flushed=fail_left=0;
                test_ms=10000;closed_ms=0;connected=inject_releases=false;read_fault=0;stopping=0;
                sleeps=0;fail_absent_poll=false;
                char *argv[]={"unused",NULL};
                if(!setjmp(finished))supervise(argv,native,true);
                assert(opens==1 && closes==0 && stopping);
                assert(queries==(recovered_case?2U:1U));
            }
        }
    }
    puts("PASS: actual native/generic guest reader, drop/reconnect/failed resync, validated 1.2/2/3/4/5/6s exits, exact 8s return and always-available 2s Fn emergency");
}
