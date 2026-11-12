#ifndef ANDR36OID_PROPERTIES_H
#define ANDR36OID_PROPERTIES_H
/* Short-lived host-side batch operations. Never a daemon, and no shell/eval. */
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

static int a36_property_name(const char *name) {
    return *name && strlen(name) < 128 &&
        strspn(name,"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-")==strlen(name);
}
static int a36_property_get(const char *name, char value[92]) {
    value[0]=0;
#ifdef A36_PROPERTIES_FIXTURE
    const char *root=getenv("A36_PROPERTIES_DIR");char path[PATH_MAX];
    if(!root||snprintf(path,sizeof(path),"%s/%s",root,name)>=(int)sizeof(path))return -1;
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0)return errno==ENOENT?0:-1;
    char data[94];ssize_t n=read(fd,data,sizeof(data));close(fd);
    if(n<0)return -1;
    while(n&&data[n-1]=='\n')n--;
    if(n>91){errno=EOVERFLOW;return -1;}
    memcpy(value,data,n);value[n]=0;return 0;
#elif defined(__ANDROID__)
    return __system_property_get(name,value)<0?-1:0;
#else
    (void)name;errno=ENOTSUP;return -1;
#endif
}
static int a36_property_set(const char *name, const char *value) {
    if(!a36_property_name(name)||strlen(value)>91){errno=EINVAL;return -1;}
#ifdef A36_PROPERTIES_FIXTURE
    const char *root=getenv("A36_PROPERTIES_DIR");char path[PATH_MAX];
    if(!root||snprintf(path,sizeof(path),"%s/%s",root,name)>=(int)sizeof(path))return -1;
    const char *denied=getenv("A36_PROPERTIES_DENY");
    if(denied&&!strcmp(denied,name)){errno=EACCES;return -1;}
    int fd=open(path,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0666);
    if(fd<0)return -1;
    size_t n=strlen(value);
    int rc=write(fd,value,n)==(ssize_t)n&&write(fd,"\n",1)==1?0:-1;
    if(close(fd))rc=-1;
    return rc;
#elif defined(__ANDROID__)
    return __system_property_set(name,value);
#else
    (void)value;errno=ENOTSUP;return -1;
#endif
}
static int a36_properties(const char *mode) {
    bool get=!strcmp(mode,"get"),set=!strcmp(mode,"set");
    if(!get&&!set)return 2;
    struct record {char name[128],value[92];};
    struct record *rows=calloc(512,sizeof(*rows));
    if(!rows)return 1;
    char line[256];unsigned count=0;int rc=0;
    while(fgets(line,sizeof(line),stdin)) {
        size_t n=strlen(line);
        if(n&&line[n-1]=='\n')line[--n]=0;
        else if(!feof(stdin)){rc=2;goto done;}
        if(!n)continue;
        char *value=strchr(line,'\t');
        if(set&&!value){rc=2;goto done;}
        if(value)*value++=0;
        if(count==512||!a36_property_name(line)||(set&&strlen(value)>91)){rc=2;goto done;}
        strcpy(rows[count].name,line);
        if(get) {
            if(value||a36_property_get(line,rows[count].value)||strpbrk(rows[count].value,"\t\n")){rc=2;goto done;}
        } else strcpy(rows[count].value,value);
        count++;
    }
    if(ferror(stdin)){rc=1;goto done;}
    for(unsigned i=0;i<count;i++) {
        if(get)printf("%s\t%s\n",rows[i].name,rows[i].value);
        else if(a36_property_set(rows[i].name,rows[i].value))rc=1;
    }
    if(get&&fflush(stdout))rc=1;
done:
    free(rows);return rc;
}
#endif
