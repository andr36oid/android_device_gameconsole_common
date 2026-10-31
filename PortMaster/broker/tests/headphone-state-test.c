/* Actual ALSA jack reader: normalized polarity, scalar validation and errors. */
#define _GNU_SOURCE
/* Keep Bionic's unsigned ioctl overload from aliasing past the test hook. */
#define BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD
#include <assert.h>
#include <stdarg.h>
#define open jack_test_open
#define ioctl jack_test_ioctl
#define main guest_program_main
#include "../../subsystem/andr36oid-guest.c"
#undef main
#undef ioctl
#undef open
static int fault, raw, active_low, flip;
int jack_test_open(const char *path,int flags,...) {
    assert(!strcmp(path,"/dev/snd/controlC0"));
    if(fault==1)return -1;
    return syscall(SYS_openat,AT_FDCWD,"/dev/null",flags,0);
}
#ifdef __ANDROID__
typedef int jack_ioctl_request;
#else
typedef unsigned long jack_ioctl_request;
#endif
int jack_test_ioctl(int fd,jack_ioctl_request request,...) {
    (void)fd;
    va_list ap;va_start(ap,request);void *arg=va_arg(ap,void *);va_end(ap);
    unsigned long operation=(unsigned int)request;
    if(operation==SNDRV_CTL_IOCTL_ELEM_INFO) {
        struct snd_ctl_elem_info *i=arg;
        assert(i->id.iface==SNDRV_CTL_ELEM_IFACE_CARD);
        assert(!strcmp((char *)i->id.name,"Headphones Jack"));
        if(fault==2)return -1;
        i->type=fault==3?SNDRV_CTL_ELEM_TYPE_INTEGER:SNDRV_CTL_ELEM_TYPE_BOOLEAN;
        i->count=fault==4?2:1;
        i->access=fault==5?0:SNDRV_CTL_ELEM_ACCESS_READ;
        return 0;
    }
    assert(operation==SNDRV_CTL_IOCTL_ELEM_READ);
    if(fault==6)return -1;
    struct snd_ctl_elem_value *v=arg;
    v->value.integer.value[0]=fault==7?-1:fault==8?2:raw^active_low^flip;
    return 0;
}
int main(int argc,char **argv) {
    assert(argc==1 || argc==2);
    int saved=dup(STDOUT_FILENO);assert(saved>=0);
    FILE *output=argc==2?fopen(argv[1],"w+"):tmpfile();assert(output);
    assert(dup2(fileno(output),STDOUT_FILENO)>=0);
    for(fault=0;fault<=8;fault++) {
        for(raw=0;raw<=1;raw++)for(active_low=0;active_low<=1;active_low++)for(flip=0;flip<=1;flip++) {
            assert(!ftruncate(fileno(output),0));rewind(output);
            assert(headphone_state()==(fault?1:0));
            rewind(output);
            if(fault)assert(fgetc(output)==EOF);
            else {assert(fgetc(output)=='0'+(raw^active_low^flip));assert(fgetc(output)=='\n');assert(fgetc(output)==EOF);}
        }
    }
    assert(dup2(saved,STDOUT_FILENO)>=0);close(saved);fclose(output);
    if(argc==2)assert(!unlink(argv[1]));
    puts("PASS: validated kernel-polarized ALSA headphone state and read failures");
    return 0;
}
