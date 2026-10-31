/* Shared evdev state for the namespace supervisor and native Fn menu watcher. */
#ifndef ANDR36OID_INPUT_H
#define ANDR36OID_INPUT_H
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define KEY_WORD_BITS (8*sizeof(unsigned long))
#define KEY_WORDS ((KEY_MAX+KEY_WORD_BITS)/KEY_WORD_BITS)
struct hotkey_state {
    bool select,start,fn,dropped,sync_needed;
    unsigned long held[KEY_WORDS];
    unsigned other;
    bool combination_used,tap_pending,has_fired;
    long long press_ms,release_ms,last_fire;
    long long combo_ms,force_ms;
    bool exit_fired;
};
static inline long long now_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t);
    return (long long)t.tv_sec*1000+t.tv_nsec/1000000;
}
static inline int pad_open(void) {
    char path[128],name[128];
    for(int i=0;i<128;i++) {
        snprintf(path,sizeof(path),"/dev/input/event%d",i);
        int fd=open(path,O_RDONLY|O_NONBLOCK|O_CLOEXEC);
        if(fd<0)continue;
        int clock=CLOCK_MONOTONIC;
        if(ioctl(fd,EVIOCGNAME(sizeof(name)),name)>=0 && !strcmp(name,"GO-Super Gamepad") &&
           ioctl(fd,EVIOCSCLOCKID,&clock)==0)return fd;
        close(fd);
    }
    return -1;
}
static inline bool key_held(const struct hotkey_state *keys,unsigned code) {
    return (keys->held[code/KEY_WORD_BITS]>>(code%KEY_WORD_BITS))&1UL;
}
static inline void hotkey_invalidate(struct hotkey_state *keys,bool dropped) {
    // Preserve debounce across reconnects and lost frames, but never synthesize
    // a clean Fn press from a snapshot whose earlier history is unknown.
    long long last=keys->last_fire;bool fired=keys->has_fired;
    *keys=(struct hotkey_state){.dropped=dropped,.sync_needed=true,
        .press_ms=-1,.last_fire=last,.has_fired=fired};
}
static inline bool hotkey_sync(int pad,bool native,struct hotkey_state *keys) {
    unsigned long held[KEY_WORDS]={0};
    hotkey_invalidate(keys,false);
    // On this kernel EVIOCGKEY atomically snapshots and flushes EV_KEY on this
    // client. Call at open or after the dropped frame's SYN_REPORT. Readers
    // MUST read one event at a time: a userspace batch could replay stale keys
    // after this ioctl. Do not drain after querying (that could lose new keys).
    if(ioctl(pad,EVIOCGKEY(sizeof(held)),held)!=(int)sizeof(held))return false;
    memcpy(keys->held,held,sizeof(held));
    unsigned fn=native?BTN_TRIGGER_HAPPY5:KEY_HOMEPAGE;
    keys->select=key_held(keys,native?BTN_TRIGGER_HAPPY1:BTN_SELECT);
    keys->start=key_held(keys,native?BTN_TRIGGER_HAPPY2:BTN_START);
    keys->fn=key_held(keys,fn);
    for(unsigned code=0;code<=KEY_MAX;code++)
        if(code!=fn && key_held(keys,code))keys->other++;
    keys->combination_used=keys->fn;
    keys->sync_needed=false;
    return true;
}
// Returns one menu request only at the end of a complete, clean tap frame.
static inline bool hotkey_event(int pad,bool native,struct hotkey_state *keys,const struct input_event *ev) {
    if(ev->type==EV_SYN && ev->code==SYN_DROPPED) {
        hotkey_invalidate(keys,true);return false;
    }
    if(keys->dropped || keys->sync_needed) {
        if(ev->type==EV_SYN && ev->code==SYN_REPORT)hotkey_sync(pad,native,keys);
        return false;
    }
    if(ev->type==EV_SYN && ev->code==SYN_REPORT) {
        bool fire=keys->tap_pending;
        keys->tap_pending=false;
        if(fire) {keys->has_fired=true;keys->last_fire=keys->release_ms;}
        return fire;
    }
    if(ev->type!=EV_KEY || ev->code>KEY_MAX || ev->value<0 || ev->value>2)return false;
    unsigned code=ev->code,fn=native?BTN_TRIGGER_HAPPY5:KEY_HOMEPAGE;
    bool down=ev->value!=0,was=key_held(keys,code);
    long long time=(long long)ev->time.tv_sec*1000+ev->time.tv_usec/1000;
    if(code!=fn) {
        keys->tap_pending=false;
        if(down && keys->fn)keys->combination_used=true;
    }
    if(was==down)return false; // Repeats/duplicate edges do not change counts or timing.
    if(down)keys->held[code/KEY_WORD_BITS]|=1UL<<(code%KEY_WORD_BITS);
    else keys->held[code/KEY_WORD_BITS]&=~(1UL<<(code%KEY_WORD_BITS));
    if(code==fn) {
        keys->fn=down;
        if(down) {
            keys->tap_pending=false;
            keys->press_ms=ev->value==1?time:-1;
            keys->combination_used=keys->other!=0;
        } else {
            long long duration=time-keys->press_ms;
            if(keys->press_ms>=0 && !keys->combination_used && !keys->other &&
               duration>=0 && duration<=600 &&
               (!keys->has_fired || time-keys->last_fire>400)) {
                keys->tap_pending=true;
                keys->release_ms=time;
            }
            keys->press_ms=-1;
        }
    } else if(down)keys->other++;
    else keys->other--;
    keys->select=key_held(keys,native?BTN_TRIGGER_HAPPY1:BTN_SELECT);
    keys->start=key_held(keys,native?BTN_TRIGGER_HAPPY2:BTN_START);
    if(!keys->fn || !keys->start)keys->force_ms=0;
    if(!keys->select || !keys->start || keys->fn) {keys->combo_ms=0;keys->exit_fired=false;}
    return false;
}
enum { HOTKEY_EXIT_GAME=1,HOTKEY_RETURN_ANDROID=2 };
static inline int hotkey_tick(struct hotkey_state *keys,long long now) {
    int action=0;
    if(keys->sync_needed)return 0;
    if(keys->fn && keys->start) {
        if(!keys->force_ms)keys->force_ms=now;
        if(now-keys->force_ms>=2000)action|=HOTKEY_RETURN_ANDROID;
    } else keys->force_ms=0;
    if(keys->select && keys->start && !keys->fn) {
        if(!keys->combo_ms)keys->combo_ms=now;
        if(now-keys->combo_ms>=1200 && !keys->exit_fired) {
            action|=HOTKEY_EXIT_GAME;keys->exit_fired=true;
        }
        if(now-keys->combo_ms>=4000)action|=HOTKEY_RETURN_ANDROID;
    } else {keys->combo_ms=0;keys->exit_fired=false;}
    return action;
}
#endif
