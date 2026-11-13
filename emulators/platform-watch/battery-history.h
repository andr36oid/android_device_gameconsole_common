/* The existing native watcher owns the optional 15-minute history sampler.
 * Same CSV, units, seven-day retention and awake-only semantics as History.java.
 * Capacity measurement and notifications remain in the Java app. */
static int64_t battery_due;
static bool battery_ready;
static void battery_property(const char *value) {
#ifdef PLATFORM_WATCH_HOST_TEST
    char path[PATH_MAX];path_init(path,"/properties/sys.andr36oid.ram.battery_sampler");
    FILE *f=fopen(path,"we");if(f){fputs(value,f);fclose(f);}
#else
    property_set("sys.andr36oid.ram.battery_sampler",value);
#endif
}
static bool battery_enabled(void) {
    char value[128];get_property("persist.andr36oid.ram.boot_batterydetails",value,"0");
    return !strcmp(value,"1");
}
static bool battery_text(const char *file,char *text,size_t n) {
    FILE *f=fopen(file,"re");if(!f)return false;
    bool ok=fgets(text,(int)n,f)!=NULL;fclose(f);
    if(ok) {
        size_t first=0,last=strlen(text);
        while(first<last&&(unsigned char)text[first]<=32)first++;
        while(last>first&&(unsigned char)text[last-1]<=32)last--;
        memmove(text,text+first,last-first);text[last-first]=0;
    }
    return ok;
}
static int battery_integer(const char *base,const char *name,int divisor) {
    char path[PATH_MAX],text[128],*end;
    if(!join(path,sizeof(path),base,name)||!battery_text(path,text,sizeof(text)))return INT_MIN;
    errno=0;long long number=strtoll(text,&end,10);
    while(isspace((unsigned char)*end))end++;
    if(errno||end==text||*end)return INT_MIN;
    /* Gauge.readInt divides a Java long, then narrows to signed int. */
    uint32_t bits=(uint32_t)(number/divisor);int32_t result;
    memcpy(&result,&bits,sizeof(result));return result;
}
static bool battery_record(int64_t epoch_ms) {
    char supplies[PATH_MAX],base[PATH_MAX],file[PATH_MAX],text[128],history[PATH_MAX],lock[PATH_MAX];
    path_init(supplies,"/sys/class/power_supply");base[0]=0;
    DIR *d=opendir(supplies);if(!d)return false;struct dirent *e;
    while((e=readdir(d))) {
        if(e->d_name[0]=='.'||!join(base,sizeof(base),supplies,e->d_name)||!join(file,sizeof(file),base,"type"))continue;
        if(battery_text(file,text,sizeof(text))&&!strcmp(text,"Battery"))break;
        base[0]=0;
    }
    closedir(d);if(!base[0])return false;
    int percent=battery_integer(base,"capacity",1);if(percent==INT_MIN)return false;
    int voltage=battery_integer(base,"voltage_now",1000),current=battery_integer(base,"current_now",1000);
    int charging=join(file,sizeof(file),base,"status")&&battery_text(file,text,sizeof(text))&&!strcmp(text,"Charging");
    path_init(history,"/data/user/0/org.andr36oid.batterydetails/files/history.csv");
    path_init(lock,"/data/user/0/org.andr36oid.batterydetails/files/history.lock");
    int fd=open(lock,O_WRONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)return false;
    /* Never stall ROM watching behind an open settings screen. Retry next tick. */
    struct flock held={.l_type=F_WRLCK,.l_whence=SEEK_SET,.l_start=0,.l_len=0};
    if(fcntl(fd,F_SETLK,&held)){close(fd);return false;}
    int out=open(history,O_WRONLY|O_APPEND|O_CLOEXEC|O_NOFOLLOW);
    bool ok=out>=0;
    if(ok) {
        char line[192];int n=snprintf(line,sizeof(line),"%lld,%d,%d,%d,%d\n",(long long)epoch_ms,percent,voltage,current,charging);
        if(ok)ok=write(out,line,(size_t)n)==n;
        if(close(out))ok=false;
    }
    FILE *f=ok?fopen(history,"re"):NULL;
    if(f) {
        size_t lines=0,cap=0;char *line=NULL;
        while(getline(&line,&cap,f)>=0)lines++;
        if(lines>=1000) {
            rewind(f);char tmp[PATH_MAX];
            if(snprintf(tmp,sizeof(tmp),"%s.tmp",history)>=(int)sizeof(tmp))ok=false;
            FILE *to=ok?fopen(tmp,"we"):NULL;
            if(!to)ok=false;
            if(to) {
                int64_t oldest=epoch_ms-7LL*24*3600*1000;
                while(getline(&line,&cap,f)>=0) {
                    char *end;errno=0;long long when=strtoll(line,&end,10);
                    if(!errno&&!isspace((unsigned char)line[0])&&end!=line&&(*end==','||*end=='\n'||!*end)&&when>=oldest)
                        if(fputs(line,to)<0)ok=false;
                }
#ifndef PLATFORM_WATCH_HOST_TEST
                if(fchown(fileno(to),1000,1000))ok=false;
#endif
                if(fchmod(fileno(to),0600))ok=false;
                if(fclose(to))ok=false;
                if(ok&&rename(tmp,history))ok=false;
                if(!ok)unlink(tmp);
            }
        }
        free(line);fclose(f);
    }
    close(fd);return ok;
}
static void battery_tick(void) {
    bool enabled=battery_enabled();
    if(!enabled) {
        if(battery_ready)battery_property("0");
        battery_ready=false;battery_due=0;return;
    }
    struct timespec t;clock_gettime(CLOCK_BOOTTIME,&t);
    int64_t now=(int64_t)t.tv_sec*1000+t.tv_nsec/1000000;
    if(now<battery_due)return;
    clock_gettime(CLOCK_REALTIME,&t);
    bool ok=battery_record((int64_t)t.tv_sec*1000+t.tv_nsec/1000000);
    // Claim ownership only after proving that this package's files are writable.
    // A failed write revokes it, so the consolidated helper restores the Java job.
    if(ok!=battery_ready){battery_ready=ok;battery_property(ok?"1":"0");}
    battery_due=now+(ok?15*60*1000:15000);
}
