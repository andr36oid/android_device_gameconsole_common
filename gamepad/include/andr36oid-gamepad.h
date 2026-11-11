/* SPDX-License-Identifier: MIT
 * Shared raw-consumer contract. Kernel evdev is native rg351; Android public
 * scan codes and saved settings retain the legacy identity across rollback.
 */
#ifndef ANDR36OID_GAMEPAD_H
#define ANDR36OID_GAMEPAD_H
#include <stdbool.h>
#include <stddef.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#ifndef BTN_DPAD_UP
#define BTN_DPAD_UP 0x220
#define BTN_DPAD_DOWN 0x221
#define BTN_DPAD_LEFT 0x222
#define BTN_DPAD_RIGHT 0x223
#endif

enum a36_pad_role {
 A36_PAD_A, A36_PAD_B, A36_PAD_X, A36_PAD_Y,
 A36_PAD_L1, A36_PAD_R1, A36_PAD_L2, A36_PAD_R2,
 A36_PAD_L3, A36_PAD_R3, A36_PAD_SELECT, A36_PAD_START,
 A36_PAD_MODE, A36_PAD_UP, A36_PAD_DOWN, A36_PAD_LEFT, A36_PAD_RIGHT,
 A36_PAD_ROLE_COUNT
};
struct a36_pad_binding { unsigned canonical, legacy; enum a36_pad_role role; };
static const struct a36_pad_binding a36_pad_bindings[] = {
 {305,304,A36_PAD_A}, {304,305,A36_PAD_B}, {307,307,A36_PAD_X}, {308,308,A36_PAD_Y},
 {310,310,A36_PAD_L1}, {311,311,A36_PAD_R1}, {312,312,A36_PAD_L2}, {313,313,A36_PAD_R2},
 {706,158,A36_PAD_L3}, {707,125,A36_PAD_R3}, {704,314,A36_PAD_SELECT}, {705,315,A36_PAD_START},
 {708,172,A36_PAD_MODE}, {544,103,A36_PAD_UP}, {545,108,A36_PAD_DOWN},
 {546,105,A36_PAD_LEFT}, {547,106,A36_PAD_RIGHT}
};
typedef bool (*a36_pad_has_key)(const void *, unsigned);
static inline bool a36_pad_is_native(a36_pad_has_key has, const void *ctx) {
 for (size_t i=0;i<A36_PAD_ROLE_COUNT;i++) if (!has(ctx,a36_pad_bindings[i].canonical)) return false;
 return true;
}
struct a36_pad_bytes { const unsigned char *bits; size_t count; };
static inline bool a36_pad_has_byte(const void *ctx,unsigned code) {
 const struct a36_pad_bytes *p=(const struct a36_pad_bytes *)ctx;
 return code/8<p->count && ((p->bits[code/8]>>(code%8))&1U);
}
static inline bool a36_pad_native_bytes(const unsigned char *bits,size_t count) {
 const struct a36_pad_bytes ctx={bits,count}; return a36_pad_is_native(a36_pad_has_byte,&ctx);
}
struct a36_pad_words { const unsigned long *bits; size_t count; };
static inline bool a36_pad_has_word(const void *ctx,unsigned code) {
 const struct a36_pad_words *p=(const struct a36_pad_words *)ctx;
 const unsigned width=8*sizeof(unsigned long);
 return code/width<p->count && ((p->bits[code/width]>>(code%width))&1UL);
}
static inline bool a36_pad_native_words(const unsigned long *bits,size_t count) {
 const struct a36_pad_words ctx={bits,count};return a36_pad_is_native(a36_pad_has_word,&ctx);
}
static inline bool a36_pad_native_fd(int fd) {
 unsigned char bits[(KEY_MAX+8)/8]={0};
 return ioctl(fd,EVIOCGBIT(EV_KEY,sizeof(bits)),bits)>=0 && a36_pad_native_bytes(bits,sizeof(bits));
}
static inline unsigned a36_pad_code(enum a36_pad_role role,bool native) {
 for(size_t i=0;i<A36_PAD_ROLE_COUNT;i++)if(a36_pad_bindings[i].role==role)
  return native?a36_pad_bindings[i].canonical:a36_pad_bindings[i].legacy;
 return 0;
}
static inline unsigned a36_pad_to_legacy(unsigned code) {
 for(size_t i=0;i<A36_PAD_ROLE_COUNT;i++)if(a36_pad_bindings[i].canonical==code)return a36_pad_bindings[i].legacy;
 if(code>=709&&code<=724)return code-5;
 return code;
}
static inline unsigned a36_pad_to_native(unsigned code) {
 for(size_t i=0;i<A36_PAD_ROLE_COUNT;i++)if(a36_pad_bindings[i].legacy==code)return a36_pad_bindings[i].canonical;
 if(code==BTN_THUMBL)return 706;
 if(code==BTN_THUMBR)return 707;
 if(code>=704&&code<=719)return code+5;
 return code;
}
#endif
