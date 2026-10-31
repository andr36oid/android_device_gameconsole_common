/* Feed the actual hotkey reader complete and dropped evdev frames. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdarg.h>
#include <sys/ioctl.h>
int hotkey_test_ioctl(int fd,unsigned long request,...);
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
static long long event_ms;
static unsigned menus;
static void event(struct hotkey_state *keys,bool native,int type,int code,int value) {
    struct input_event ev={.type=type,.code=code,.value=value,
        .time={.tv_sec=event_ms/1000,.tv_usec=(event_ms%1000)*1000}};
    if(hotkey_event(1,native,keys,&ev))menus++;
}
static void frame(struct hotkey_state *keys,bool native,unsigned code,int value,long long time) {
    event_ms=time;
    event(keys,native,EV_KEY,code,value);
    event(keys,native,EV_SYN,SYN_REPORT,0);
}
static void hold(unsigned code) {
    held[code/KEY_WORD_BITS]|=1UL<<(code%KEY_WORD_BITS);
}
static void fn_regression(bool native) {
    unsigned fn=native?BTN_TRIGGER_HAPPY5:KEY_HOMEPAGE;
    unsigned start=native?BTN_TRIGGER_HAPPY2:BTN_START;
    unsigned select=native?BTN_TRIGGER_HAPPY1:BTN_SELECT;
    unsigned up=native?BTN_DPAD_UP:KEY_UP;
    struct hotkey_state keys={0};
    memset(held,0,sizeof(held));menus=0;
    assert(hotkey_sync(1,native,&keys));
    frame(&keys,native,fn,1,1000);
    frame(&keys,native,fn,1,1100); // Duplicate press must not reset duration.
    frame(&keys,native,fn,2,1200);
    frame(&keys,native,fn,0,1600);assert(menus==1);
    frame(&keys,native,fn,0,1601);assert(menus==1);
    frame(&keys,native,fn,1,1700);
    frame(&keys,native,fn,0,1800);assert(menus==1); // Debounce.
    frame(&keys,native,fn,1,2100);
    frame(&keys,native,fn,2,2700);
    frame(&keys,native,fn,0,2800);assert(menus==1); // Long hold, despite repeat.
    // Finding 10: brightness combination ends before Fn is released.
    frame(&keys,native,fn,1,3000);
    frame(&keys,native,up,1,3100);
    frame(&keys,native,up,0,3200);
    frame(&keys,native,fn,0,3300);assert(menus==1);
    // Two held buttons, duplicate/repeat events and releasing only one.
    frame(&keys,native,up,1,4000);
    frame(&keys,native,start,1,4010);
    frame(&keys,native,up,1,4020);
    frame(&keys,native,up,2,4030);assert(keys.other==2);
    frame(&keys,native,up,0,4040);
    frame(&keys,native,up,0,4050);assert(keys.other==1);
    frame(&keys,native,fn,1,4100);
    frame(&keys,native,start,0,4200);assert(keys.other==0);
    frame(&keys,native,fn,0,4300);assert(menus==1);
    // Do not commit a release from an incomplete or dropped frame.
    frame(&keys,native,fn,1,5000);
    event_ms=5100;event(&keys,native,EV_KEY,fn,0);assert(menus==1);
    event(&keys,native,EV_SYN,SYN_DROPPED,0);
    event(&keys,native,EV_SYN,SYN_REPORT,0);assert(menus==1);
    // Startup/reconnect/drop snapshot with Fn+Start already held: preserve
    // the chord, never treat releasing the recovered Fn as a clean tap.
    for(int transition=0;transition<3;transition++) {
        memset(held,0,sizeof(held));hold(fn);hold(start);hold(up);
        if(transition==0)assert(hotkey_sync(1,native,&keys));
        if(transition==1) {
            hotkey_invalidate(&keys,false);assert(hotkey_sync(1,native,&keys));
        }
        if(transition==2) {
            event(&keys,native,EV_SYN,SYN_DROPPED,0);
            event(&keys,native,EV_KEY,fn,0); // Invalid frame cannot undo held Fn.
            event(&keys,native,EV_SYN,SYN_REPORT,0);
        }
        assert(keys.fn && keys.start && keys.other==2 && !keys.sync_needed);
        assert(hotkey_tick(&keys,6000)==0);
        assert(hotkey_tick(&keys,7999)==0);
        assert(hotkey_tick(&keys,8000)==HOTKEY_RETURN_ANDROID);
        frame(&keys,native,up,0,8100);
        frame(&keys,native,start,0,8200);
        frame(&keys,native,fn,0,8300);assert(menus==1);
    }
    // A failed snapshot stays invalid, including at idle: the periodic retry
    // must recover a held chord even when no further events arrive.
    fail_query=true;assert(!hotkey_sync(1,native,&keys));
    assert(keys.sync_needed && hotkey_tick(&keys,10000)==0);
    frame(&keys,native,fn,1,10100);assert(!keys.fn && keys.sync_needed);
    fail_query=false;assert(hotkey_sync(1,native,&keys));
    assert(keys.fn && keys.start && hotkey_tick(&keys,11000)==0);
    assert(hotkey_tick(&keys,13000)==HOTKEY_RETURN_ANDROID);
    // Release/repress within a single read drain restarts hold time.
    frame(&keys,native,fn,0,13100);
    frame(&keys,native,fn,1,13200);
    assert(hotkey_tick(&keys,13200)==0);
    assert(hotkey_tick(&keys,15199)==0);
    assert(hotkey_tick(&keys,15200)==HOTKEY_RETURN_ANDROID);
    frame(&keys,native,fn,0,15300);
    frame(&keys,native,start,0,15400);
    frame(&keys,native,up,0,15500);
    frame(&keys,native,fn,1,16000);
    frame(&keys,native,fn,0,16100);assert(menus==2); // Recovery re-arms exactly once.
    // Select+Start retains its one-shot game exit and eventual forced return.
    frame(&keys,native,select,1,17000);
    frame(&keys,native,start,1,17000);
    assert(hotkey_tick(&keys,17000)==0);
    assert(hotkey_tick(&keys,18200)==HOTKEY_EXIT_GAME);
    assert(hotkey_tick(&keys,18201)==0);
    assert(hotkey_tick(&keys,21000)==HOTKEY_RETURN_ANDROID);
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
        fn_regression(native);
    }
    puts("PASS: native/generic Fn taps, sticky combinations, held-key sets, repeats, drop/reconnect/failed resync, and timed escape chords");
}
