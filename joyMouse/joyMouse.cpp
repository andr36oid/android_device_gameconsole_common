#include <linux/uinput.h>
#include <linux/input.h>
#include <fcntl.h>
#include <unistd.h>
#include <iostream>
#include <sys/time.h>
#include <cmath>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstring>
#include <sys/system_properties.h>

const char* JOY_EVENT = "/dev/input/event3";

const int RX_DEADZONE = 400;
const int RY_DEADZONE = 400;

const int MAX_STICK = 1800;     // derived from adc calibration (~center 1800)
const int MAX_MOVE  = 15;       // max pixels per poll

const int POLL_INTERVAL_MS = 8;
const int R3_KEY_CODE = 125;
const int L3_KEY_CODE = 158;
const int LONGPRESS_MS = 600;

std::atomic<int> rx(0), ry(0);
std::atomic<bool> running(true), mouseMode(false), r3Pressed(false), l3Pressed(false);

void emitEvent(int fd,int type,int code,int value){
    struct input_event ev{};
    gettimeofday(&ev.time,nullptr);
    ev.type=type;
    ev.code=code;
    ev.value=value;
    write(fd,&ev,sizeof(ev));
}

bool detectInvertRY(){
    char device[PROP_VALUE_MAX];
    memset(device,0,sizeof(device));
    __system_property_get("ro.product.device",device);
    return strcmp(device,"r36s")==0;
}

int stickToSpeed(int val,int deadzone)
{
    int sign = (val >= 0) ? 1 : -1;
    int absVal = std::abs(val);

    if(absVal <= deadzone)
        return 0;

    int adj = absVal - deadzone;

    float norm = (float)adj / (MAX_STICK - deadzone);

    if(norm > 1.0f)
        norm = 1.0f;

    float speed;

    if(norm < 0.9f)
        speed = norm * MAX_MOVE * 0.5f;
    else
        speed = MAX_MOVE;

    int result = (int)speed * sign;

    if(result == 0)
        result = sign;

    return result;
}

void readJoystick(){
    int joy_fd=open(JOY_EVENT,O_RDONLY);
    if(joy_fd<0){
        perror("Failed to open joystick");
        running=false;
        return;
    }

    struct input_event ev;

    while(running){
        if(read(joy_fd,&ev,sizeof(ev))!=sizeof(ev)) continue;

        if(ev.type==EV_ABS){
            if(ev.code==ABS_RX) rx=ev.value;
            if(ev.code==ABS_RY) ry=ev.value;
        }
        else if(ev.type==EV_KEY){
            if(ev.code==R3_KEY_CODE) r3Pressed=ev.value;
            if(ev.code==L3_KEY_CODE) l3Pressed=ev.value;
        }
    }

    close(joy_fd);
}

int main(){

    std::thread joyThread(readJoystick);

    int uinput_fd=open("/dev/uinput",O_WRONLY|O_NONBLOCK);
    if(uinput_fd<0){
        perror("Failed to open uinput");
        running=false;
        joyThread.join();
        return 1;
    }

    ioctl(uinput_fd,UI_SET_EVBIT,EV_REL);
    ioctl(uinput_fd,UI_SET_RELBIT,REL_X);
    ioctl(uinput_fd,UI_SET_RELBIT,REL_Y);

    ioctl(uinput_fd,UI_SET_EVBIT,EV_KEY);
    ioctl(uinput_fd,UI_SET_KEYBIT,BTN_LEFT);

    struct uinput_user_dev uidev{};
    snprintf(uidev.name,UINPUT_MAX_NAME_SIZE,"steamdeck_joymouse");

    uidev.id.bustype=BUS_USB;
    uidev.id.vendor=0x1234;
    uidev.id.product=0x5678;
    uidev.id.version=1;

    write(uinput_fd,&uidev,sizeof(uidev));
    ioctl(uinput_fd,UI_DEV_CREATE);

    bool lastR3=false;
    bool lastL3=false;
    bool longpressHandled=false;
    bool lastBtnState=false;

    auto pressStart=std::chrono::steady_clock::now();

    float smoothX=0;
    float smoothY=0;

    while(running){

        bool curR3=r3Pressed.load();
        bool curL3=l3Pressed.load();

        // detect simultaneous press start
        if(curR3 && curL3 && !(lastR3 && lastL3)){
            pressStart=std::chrono::steady_clock::now();
            longpressHandled=false;
        }

        // long press toggle for simultaneous R3+L3
        if(curR3 && curL3 && !longpressHandled){
            auto now=std::chrono::steady_clock::now();
            auto held=std::chrono::duration_cast<std::chrono::milliseconds>(now-pressStart).count();

            if(held>=LONGPRESS_MS){
                mouseMode = !mouseMode;
                longpressHandled = true;
            }
        }

        lastR3 = curR3;
        lastL3 = curL3;

        if(mouseMode){

            int mx=stickToSpeed(rx.load(),RX_DEADZONE);

            int my;
            if(detectInvertRY())
                my=stickToSpeed(ry.load(),RY_DEADZONE);
            else
                my=-stickToSpeed(ry.load(),RY_DEADZONE);

            // smoothing filter for ADC jitter
            smoothX = smoothX*0.6f + mx*0.4f;
            smoothY = smoothY*0.6f + my*0.4f;

            mx = (int)smoothX;
            my = (int)smoothY;

            bool btnState=curR3;

            if(btnState != lastBtnState){
                emitEvent(uinput_fd,EV_KEY,BTN_LEFT,btnState?1:0);
                emitEvent(uinput_fd,EV_SYN,SYN_REPORT,0);
                lastBtnState=btnState;
            }

            if(mx || my){
                emitEvent(uinput_fd,EV_REL,REL_X,mx);
                emitEvent(uinput_fd,EV_REL,REL_Y,my);
                emitEvent(uinput_fd,EV_SYN,SYN_REPORT,0);
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(POLL_INTERVAL_MS));
    }

    ioctl(uinput_fd,UI_DEV_DESTROY);
    close(uinput_fd);

    running=false;
    joyThread.join();

    return 0;
}