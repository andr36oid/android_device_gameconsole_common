/* Feed the actual hotkey reader complete and dropped evdev frames. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdarg.h>
#define ioctl hotkey_test_ioctl
#define main guest_program_main
#include "../../subsystem/andr36oid-guest.c"
#undef main
#undef ioctl
static unsigned long held[(KEY_MAX+8*sizeof(long))/(8*sizeof(long))];
static int queries;
static bool fail_query;
int hotkey_test_ioctl(int fd,unsigned long request,...) {
    (void)fd;assert(request==EVIOCGKEY(sizeof(held)));queries++;
    if(fail_query)return -1;
    va_list ap;va_start(ap,request);void *out=va_arg(ap,void *);va_end(ap);
    memcpy(out,held,sizeof(held));return sizeof(held);
}
static void event(struct hotkey_state *keys,bool native,int type,int code,int value) {
    struct input_event ev={.type=type,.code=code,.value=value};
    hotkey_event(1,native,keys,&ev);
}
int main(void) {
    for(int native=0;native<=1;native++) {
        unsigned select=native?BTN_TRIGGER_HAPPY1:BTN_SELECT;
        unsigned start=native?BTN_TRIGGER_HAPPY2:BTN_START;
        unsigned fn=native?BTN_TRIGGER_HAPPY5:KEY_HOMEPAGE;
        struct hotkey_state keys={0};
        memset(held,0,sizeof(held));
        held[start/(8*sizeof(long))]|=1UL<<(start%(8*sizeof(long)));
        hotkey_sync(1,native,&keys);assert(keys.start && !keys.select && !keys.fn);
        event(&keys,native,EV_KEY,select,1);assert(keys.select);
        event(&keys,native,EV_KEY,select,2);assert(keys.select);
        event(&keys,native,EV_KEY,fn,1);assert(keys.fn);
        event(&keys,native,EV_KEY,select,0);assert(!keys.select);
        int before=queries;
        event(&keys,native,EV_SYN,SYN_DROPPED,0);
        assert(keys.dropped && !keys.select && !keys.start && !keys.fn && queries==before);
        event(&keys,native,EV_KEY,select,1);
        event(&keys,native,EV_KEY,start,1);
        event(&keys,native,EV_KEY,fn,1);
        assert(!keys.select && !keys.start && !keys.fn && queries==before);
        event(&keys,native,EV_SYN,SYN_REPORT,0);
        assert(!keys.dropped && keys.start && !keys.select && !keys.fn && queries==before+1);
        fail_query=true;
        event(&keys,native,EV_SYN,SYN_DROPPED,0);
        event(&keys,native,EV_SYN,SYN_REPORT,0);
        assert(!keys.dropped && !keys.select && !keys.start && !keys.fn);
        fail_query=false;
    }
    puts("PASS: native/generic hotkeys, held state, repeats, dropped frame discard and failed resync");
}
