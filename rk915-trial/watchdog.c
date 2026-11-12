#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <ifaddrs.h>
#include <netinet/in.h>
#include <stdint.h>

static int publish_ready(const char *base)
{
 char p[512],tmp[512],oldboot[37],key[32],hash[65],extra,boot[64];
 struct stat st;
 if(lstat(base,&st)||!S_ISDIR(st.st_mode)||st.st_uid||st.st_gid||(st.st_mode&0777)!=0700)return -1;
 snprintf(p,sizeof(p),"%s/consumed",base);
 if(lstat(p,&st)||!S_ISREG(st.st_mode)||st.st_uid||st.st_gid||(st.st_mode&0777)!=0600)return -1;
 FILE *f=fopen(p,"re");if(!f)return -1;
 int n=fscanf(f,"%36[0-9a-f-] %31[0-9a-z-] %64[0-9a-f] %c",oldboot,key,hash,&extra);fclose(f);
 if(n!=3||strlen(oldboot)!=36||strlen(hash)!=64)return -1;
 if(strcmp(key,"k30-g2")&&strcmp(key,"k30-g7")&&strcmp(key,"k31-g2")&&strcmp(key,"k31-g7"))return -1;
 f=fopen("/proc/sys/kernel/random/boot_id","re");if(!f)return -1;
 if(!fgets(boot,sizeof(boot),f)){fclose(f);return -1;}fclose(f);boot[strcspn(boot,"\r\n")]=0;
 if(strlen(boot)!=36||!strcmp(boot,oldboot))return -1;
 char requestboot[37],requestkey[32],requesthash[65];
 snprintf(p,sizeof(p),"%s/watchdog.request",base);
 if(lstat(p,&st)||!S_ISREG(st.st_mode)||st.st_uid||st.st_gid||(st.st_mode&0777)!=0600)return -1;
 f=fopen(p,"re");if(!f)return -1;
 n=fscanf(f,"%36[0-9a-f-] %31[0-9a-z-] %64[0-9a-f] %c",requestboot,requestkey,requesthash,&extra);fclose(f);
 if(n!=3||strcmp(requestboot,boot)||strcmp(requestkey,key)||strcmp(requesthash,hash))return -1;
 char line[192];n=snprintf(line,sizeof(line),"%s %s %s\n",boot,key,hash);
 if(n<=0||n>=(int)sizeof(line))return -1;
 snprintf(tmp,sizeof(tmp),"%s/watchdog.ready.tmp",base);snprintf(p,sizeof(p),"%s/watchdog.ready",base);
 int fd=open(tmp,O_WRONLY|O_CREAT|O_TRUNC|O_NOFOLLOW|O_CLOEXEC,0600);if(fd<0)return -1;
 int rc=write(fd,line,n)==n && fsync(fd)==0;close(fd);
 if(!rc)return -1;
 fd=open(base,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;
 rc=fsync(fd);close(fd);if(rc)return -1;
 /* Volatile, boot-ID-bound ready publication is the final fallible step. */
 return rename(tmp,p);
}

struct deadline { double offline_since, last_mono, last_boot; int initialized, offline; };
/* Count awake time; every real suspend grants a fresh reconnect interval. */
static int expired(struct deadline *d,double mono,double boot,int online,double budget)
{
 if(d->initialized && (boot-d->last_boot)-(mono-d->last_mono)>1.0)d->offline=0;
 d->last_mono=mono;d->last_boot=boot;d->initialized=1;
 if(online){d->offline=0;return 0;}
 if(!d->offline){d->offline=1;d->offline_since=mono;}
 return mono-d->offline_since>=budget;
}
static double now(clockid_t c){struct timespec t;if(clock_gettime(c,&t))return -1;return t.tv_sec+t.tv_nsec/1e9;}
static int carrier(void){char c=0;int f=open("/sys/class/net/wlan0/carrier",O_RDONLY|O_CLOEXEC);if(f<0)return 0;int n=read(f,&c,1);close(f);return n==1&&c=='1';}
static int online(void)
{
 if(!carrier())return 0;
 struct ifaddrs *a=0,*p;int ok=0;if(getifaddrs(&a))return 0;
 for(p=a;p;p=p->ifa_next)if(p->ifa_addr&&!strcmp(p->ifa_name,"wlan0")&&p->ifa_addr->sa_family==AF_INET){
  uint32_t n=((struct sockaddr_in*)p->ifa_addr)->sin_addr.s_addr;
  if(n && (ntohl(n)>>24)!=127)ok=1;
 }
 freeifaddrs(a);return ok;
}
static void record(const char *base,const char *state)
{
 char p[512],b[256];snprintf(p,sizeof(p),"%s/watchdog.log",base);int f=open(p,O_WRONLY|O_CREAT|O_APPEND|O_CLOEXEC,0600);
 if(f>=0){int n=snprintf(b,sizeof(b),"mono=%.3f boot=%.3f %s\n",now(CLOCK_MONOTONIC),now(CLOCK_BOOTTIME),state);if(n>0 && n<(int)sizeof(b)){ssize_t r=write(f,b,n);if(r==n)(void)fsync(f);}close(f);}
}
static int selftest(void)
{
 struct deadline d={0};int n=0;
 #define CHECK(x) do{n++;if(!(x)){fprintf(stderr,"FAIL %s\n",#x);return 1;}}while(0)
 CHECK(!expired(&d,0,0,0,180));CHECK(!expired(&d,179,179,0,180));CHECK(expired(&d,180,180,0,180));
 memset(&d,0,sizeof(d));CHECK(!expired(&d,1,1,1,180));CHECK(!expired(&d,2,2,0,180));CHECK(!expired(&d,3,1203,0,180));CHECK(!expired(&d,182,1382,0,180));CHECK(expired(&d,183,1383,0,180));
 CHECK(!expired(&d,184,1384,1,180));CHECK(!expired(&d,185,1385,0,180));CHECK(!expired(&d,364,1564,0,180));CHECK(expired(&d,365,1565,0,180));
 printf("RK915_WATCHDOG_SELFTEST checks=%d failures=0 (no filesystem sync or reboot executed)\n",n);return 0;
}
int main(int argc,char**argv)
{
 if(argc==2&&!strcmp(argv[1],"--self-test"))return selftest();
 if(argc!=3 || strcmp(argv[1],"/data/misc/andr36oid-rk915-trial"))return 2;
 const char *base=argv[1];double budget=strtod(argv[2],0);if(budget<60||budget>600||geteuid()!=0)return 2;
 struct deadline d={0};char abortpath[512];snprintf(abortpath,sizeof(abortpath),"%s/abort",base);int old=-1;
 if(publish_ready(base)){record(base,"refused invalid session or readiness flush failure");return 4;}
 record(base,"init-owned trial monitor ready (180 awake seconds; 5 second polls)");
 for(;;){
  if(!access(abortpath,F_OK)){record(base,"aborted before candidate use");return 0;}
  double m=now(CLOCK_MONOTONIC),b=now(CLOCK_BOOTTIME);if(m<0||b<0)return 3;
  int up=online();if(up!=old){record(base,up?"wlan0 carrier+IPv4 ready":"wlan0 unavailable");old=up;}
  if(expired(&d,m,b,up,budget)){
   record(base,"deadline expired; requesting one clean reboot to consumed-marker stock fallback");
   pid_t p=fork();if(p==0){execl("/system/bin/setprop","setprop","sys.powerctl","reboot,rk915-trial-fallback",(char*)0);_exit(127);}
   for(int i=0;i<10;i++){if(p>0&&waitpid(p,0,WNOHANG)==p)break;sleep(1);}
   record(base,"reboot request issued; local reset still required if init/kernel cannot service it");return 1;
  }
  sleep(5);
 }
}
