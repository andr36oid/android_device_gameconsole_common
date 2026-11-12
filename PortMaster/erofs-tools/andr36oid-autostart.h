/* A single short-lived static watcher; no getprop/sleep process per poll. */
static uint64_t boot_watch_ms(void) {
    struct timespec now;if(clock_gettime(CLOCK_BOOTTIME,&now))die("autostart clock");
    return (uint64_t)now.tv_sec*1000+(uint64_t)now.tv_nsec/1000000;
}
static int boot_owner_alive(unsigned pid,unsigned long long expected) {
    char path[64],data[4096];snprintf(path,sizeof(path),"/proc/%u/stat",pid);
    int fd=open(path,O_RDONLY|O_CLOEXEC);if(fd<0)return 0;
    ssize_t n=read(fd,data,sizeof(data)-1);close(fd);if(n<=0)return 0;data[n]=0;
    char *tail=strrchr(data,')');if(!tail || tail[1]!=' ')return 0;
    char *save=NULL,*word=strtok_r(tail+2," ",&save);unsigned field=3;
    if(!word || *word=='Z' || *word=='X')return 0;
    while(word && field<22){word=strtok_r(NULL," ",&save);field++;}
    if(!word)return 0;
    char *end;errno=0;unsigned long long actual=strtoull(word,&end,10);
    return !errno && !*end && actual==expected;
}
static void autostart_watch(const char *path,const char *token,const char *launch_arg,const char *success_arg) {
    require(strlen(token)>0 && strlen(token)<=64 && strspn(token,"0123456789abcdef-")==strlen(token),"autostart token");
    uint64_t launch=number(launch_arg),success=number(success_arg);
    require(launch>=1 && launch<=300000 && success>=1 && success<=60000,"autostart watch limits");
    rdeath(getppid());uint64_t began=boot_watch_ms(),running=0;int reached=0;
    for(;;){
        char data[512],got[65],phase[16];unsigned pid=0;unsigned long long stamp=0;int used=0;
        int fd=open(path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);ssize_t n=-1;
        if(fd>=0){struct stat st;if(!fstat(fd,&st) && S_ISREG(st.st_mode))n=read(fd,data,sizeof(data)-1);close(fd);}
        uint64_t now=boot_watch_ms();
        if(n>0){
            data[n]=0;
            if(sscanf(data,"token=%64[0-9a-f-]\nphase=%15[a-z_]\npid=%u\nstart=%llu\n%n",got,phase,&pid,&stamp,&used)!=4 || used!=n){puts(reached?"ended_early":"failed_launch");return;}
            if(strcmp(got,token)){puts("changed");return;}
            if(!strcmp(phase,"running")){
                if(!pid || pid>INT_MAX || !stamp || !boot_owner_alive(pid,stamp)){puts(reached?"ended_early":"failed_launch");return;}
                if(!reached){reached=1;running=now;}
                if(now-running>=success){puts("success");return;}
            }else if(!strcmp(phase,"ended")){puts(reached?"ended_early":"failed_launch");return;}
            else if(strcmp(phase,"waiting") || reached){puts(reached?"ended_early":"failed_launch");return;}
        }
        if(!reached && now-began>=launch){puts("failed_launch");return;}
        struct timespec pause={.tv_sec=0,.tv_nsec=50000000};
        while(nanosleep(&pause,&pause) && errno==EINTR){}
    }
}
