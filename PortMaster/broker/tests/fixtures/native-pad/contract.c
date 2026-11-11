/* Host kernel doubles. The normalization and input-registration functions are
 * extracted unchanged from the selected shipped-kernel source by the runner. */
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <linux/input.h>
typedef uint32_t u32;typedef uint16_t u16;
#define DRV_NAME "odroidgo3_joypad"
#define A36_RG351_EXTRA_FIRST 709
#define A36_RG351_EXTRA_LAST 724
#define dev_err(...) ((void)0)
#define dev_info(...) ((void)0)
struct device {int unused;};
struct input_dev {const char *name,*phys;struct input_id id;unsigned char evbit[64],keybit[KEY_MAX+1],absbit[ABS_MAX+1];int absmin[ABS_MAX+1],absmax[ABS_MAX+1],fuzz[ABS_MAX+1],flat[ABS_MAX+1];struct device dev;};
struct input_polled_dev {void *private;void (*poll)(struct input_polled_dev *),(*open)(struct input_polled_dev *),(*close)(struct input_polled_dev *);int poll_interval;struct input_dev *input;};
struct bt_gpio {int report_type,out_code;};
struct bt_adc {int report_type,min,max;};
struct joypad {struct input_dev *input;struct input_polled_dev *poll_dev;struct device *dev;struct bt_gpio *gpios;struct bt_adc *adcs;int poll_interval,no_sticks,amux_count,bt_adc_fuzz,bt_adc_flat,bt_adc_deadzone,boost_weak,boost_strong,bt_gpio_count;bool rumble,auto_repeat,button_irq;};
static struct input_dev recorded;static struct input_polled_dev polled;
static void joypad_poll(struct input_polled_dev *p){(void)p;}
static void joypad_open(struct input_polled_dev *p){(void)p;}
static void joypad_close(struct input_polled_dev *p){(void)p;}
static void rumble_play_effect(void){}
static struct input_polled_dev *devm_input_allocate_polled_device(struct device *d){(void)d;memset(&recorded,0,sizeof recorded);memset(&polled,0,sizeof polled);polled.input=&recorded;return &polled;}
static void device_property_read_string(struct device *d,const char *key,const char **v){(void)d;(void)key;*v="GO-Super Gamepad";}
static void device_property_read_u32(struct device *d,const char *key,u32 *v){(void)d;if(!strcmp(key,"joypad-revision"))*v=256;if(!strcmp(key,"joypad-product"))*v=4352;}
static void __set_bit(int bit,unsigned char *bits){bits[bit]=1;}
static void input_set_capability(struct input_dev *d,int type,int code){d->evbit[type]=1;if(type==EV_KEY)d->keybit[code]=1;}
static void input_set_abs_params(struct input_dev *d,int code,int min,int max,int fuzz,int flat){d->absbit[code]=1;d->absmin[code]=min;d->absmax[code]=max;d->fuzz[code]=fuzz;d->flat[code]=flat;d->evbit[EV_ABS]=1;}
static int input_ff_create_memless(struct input_dev *d,void *p,void (*f)(void)){(void)d;(void)p;(void)f;return 0;}
static void joypad_button_irq_setup(struct device *d,struct joypad *p){(void)d;(void)p;}
static int a36_rg351_mark_input(struct input_dev *d){(void)d;return 0;}
static void device_remove_software_node(struct device *d){(void)d;}
static int input_register_polled_device(struct input_polled_dev *p){(void)p;return 0;}
#include "kernel-fragments.h"
int normalize_code(int code,int native){return native?(a36_rg351_output_key(code)?code:-EINVAL):a36_android_to_rg351_key(code);}
int registration(int native,int no_sticks,int count,const int *codes,int scale,int fuzz,int flat){
    struct device dev={0};struct bt_gpio gpios[64]={0};struct bt_adc adcs[4]={0};
    if(count<0||count>64)return -EINVAL;
    for(int i=0;i<count;i++){gpios[i].report_type=EV_KEY;gpios[i].out_code=normalize_code(codes[i],native);if(gpios[i].out_code<0)return gpios[i].out_code;}
    int axis[4]={0,1,3,4};for(int i=0;i<4;i++){adcs[i].report_type=axis[i];adcs[i].min=-900*scale;adcs[i].max=900*scale;}
    struct joypad pad={.no_sticks=no_sticks,.bt_gpio_count=count,.gpios=gpios,.adcs=adcs,.amux_count=4,.bt_adc_fuzz=fuzz,.bt_adc_flat=flat};
    return joypad_input_setup(&dev,&pad);
}
int has_key(int code){return code>=0&&code<=KEY_MAX?recorded.keybit[code]:0;}
int has_abs(int code){return code>=0&&code<=ABS_MAX?recorded.absbit[code]:0;}
int has_abs_event(void){return recorded.evbit[EV_ABS];}
int abs_info(int code,int field){switch(field){case 0:return recorded.absmin[code];case 1:return recorded.absmax[code];case 2:return recorded.fuzz[code];default:return recorded.flat[code];}}
