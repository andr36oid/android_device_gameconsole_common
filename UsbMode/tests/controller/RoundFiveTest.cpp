#include "Controller.h"
#include "Protocols.h"
#include "Services.h"
#include "TextFiles.h"
#include <cassert>
#include <cerrno>
#include <fstream>
#include <sys/stat.h>
#include <unistd.h>
using namespace controller;

int main(int argc, char** argv) {
    assert(argc == 2);
    assert(policy(Ps4).vendor == 0x054c && policy(Ps4).product == 0x09cc);
    assert(std::string(policy(Ps4 | Storage).reason) == "standalone");
    assert(std::string(policy(Soundboard).reason) == "sound");
    assert(std::string(policy(Sound | Soundboard | Keyboard).reason) == "routing");
    assert(policy(Sound | Soundboard).in == 1 && policy(Sound | Soundboard).out == 1);
    assert(policy(Sound | Soundboard).product != policy(Sound).product);
    State s;
    Bytes r = padReport(Ps4,s);
    assert(r.size() == 64 && r[0] == 1 && r[5] == 8);
    for (unsigned i = 1; i <= 4; ++i) assert(r[i] == 128);
    for (unsigned i = 13; i < 25; ++i) assert(r[i] == (i == 23 ? 0x20 : 0));
    assert(r[30] == 10 && r[33] == 1 && r[35] == 0x80 && r[39] == 0x80);
    const Button buttons[] = {X,A,B,Y,LB,RB,LT,RT,Back,Start,L3,R3};
    for (unsigned i = 0; i < 12; ++i) {
        State one; one.buttons[buttons[i]] = true;
        Bytes expected(64); expected[0] = 1; for (unsigned j = 1; j <= 4; ++j) expected[j] = 128;
        expected[5] = 8; expected[23] = 0x20; expected[30] = 10; expected[33] = 1; expected[35] = expected[39] = 0x80;
        expected[5 + (i + 4) / 8] |= 1 << ((i + 4) % 8);
        if (i == 6) expected[8] = 255;
        if (i == 7) expected[9] = 255;
        assert(padReport(Ps4,one) == expected);
    }
    s.buttons[Guide] = true; s.buttons[Back] = s.buttons[RT] = true; s.buttons[Up] = s.buttons[Right] = true;
    s.sticks = {-32768,32767,0,-1}; s.battery = 57; s.charging = true; s.sequence = 65; s.timestamp = 0x1234;
    r = padReport(Ps4,s);
    assert(r[1] == 0 && r[2] == 255 && r[3] == 128 && r[4] == 127);
    assert((r[5] & 15) == 1 && r[7] == 7 && r[9] == 255 && r[30] == 0x15 && r[10] == 0x34 && r[11] == 0x12);
    PadControl control;
    for (unsigned id : {2,3,8,0x10,0x11,0x12,0x13,0x14,0x15,0x80,0x81,0xa3}) {
        auto reply = control.setup(Ps4,0xa1,1,0x300 | id,0,64,{});
        assert(reply.action == ControlAction::Reply && reply.data[0] == id);
        assert(control.setup(Ps4,0xa1,1,0x300 | id,0,3,{}).data == Bytes(reply.data.begin(),reply.data.begin() + std::min<size_t>(3,reply.data.size())));
    }
    const Bytes calibration = ps4Feature(2);
    assert(calibration.size() == 37 && calibration[0] == 2);
    // Linux's USB offsets: zero biases; +/-8192 gyro and accelerometer; speeds 1024.
    for (unsigned i = 1; i < 7; ++i) assert(calibration[i] == 0);
    for (unsigned at : {7,11,15,23,27,31}) assert(calibration[at] == 0 && calibration[at+1] == 0x20 && calibration[at+2] == 0 && calibration[at+3] == 0xe0);
    assert(calibration[19] == 0 && calibration[20] == 4 && calibration[21] == 0 && calibration[22] == 4);
    assert(ps4Feature(0x12).size() == 16 && ps4Feature(0x81).size() == 7 && ps4Feature(0xa3).size() == 49);
    const auto pairing = ps4Feature(0x12), mac = ps4Feature(0x81);
    assert(Bytes(pairing.begin() + 1,pairing.begin() + 7) == Bytes(mac.begin() + 1,mac.end()));
    assert(control.setup(Ps4,0xa1,1,0x3f1,0,64,{}).action == ControlAction::Stall);
    assert(control.setup(Ps4,0x81,6,0x2200,0,1024,{}).data == ps4Descriptor());
    assert(control.setup(Ps4,0x21,9,0x205,0,32,{}).action == ControlAction::Receive);
    assert(control.setup(Ps4,0xa1,1,0x302,1,37,{}).action == ControlAction::Stall);
    Jiggler jiggle;
    for (unsigned interval : {30,60,120,300}) {
        assert(jiggle.delta(0,interval,false) == 0);
        assert(jiggle.delta(100,interval,true) == 0);
        assert(jiggle.delta(100 + interval * 1000 - 1,interval,true) == 0);
        assert(jiggle.delta(100 + interval * 1000,interval,true) == 1);
        // Backpressure leaves the pending pixel intact until a successful write.
        assert(jiggle.delta(200 + interval * 1000,interval,true) == 1);
        jiggle.sent(200 + interval * 1000);
        assert(jiggle.delta(219 + interval * 1000,interval,true) == 0);
        assert(jiggle.delta(220 + interval * 1000,interval,true) == -1);
        jiggle.sent(220 + interval * 1000);
        assert(jiggle.delta(220 + interval * 1000,interval,true) == 0);
    }
    assert(jiggle.delta(9999999,1,true) == 0 && jiggle.delta(9999999,0,true) == 0);
    SessionControls macro(Keyboard | Media,true);
    macro.update({},0,true,false); macro.update({},2000,true,false); assert(macro.dark());
    State key; key.buttons[A] = true; assert(macro.keyboardState(key).buttons[A]);
    SessionControls disc(Storage | XInput,false,true);
    State choose; choose.buttons[Back] = choose.buttons[Y] = true;
    assert(!disc.gamepadState(choose).buttons[Back] && !disc.gamepadState(choose).buttons[Y]);
    std::string root = std::string(argv[1]) + "/sound-files";
    mkdir(root.c_str(),0700); mkdir((root + "/soundboard").c_str(),0700);
    std::ofstream(root + "/soundboard/test.wav",std::ios::binary) << "12345678";
    symlink("test.wav",(root + "/soundboard/link.wav").c_str());
    auto request = [&](const std::string& q) { return soundRequest(root,Bytes(q.begin(),q.end())); };
    assert(request("S\n") == (Bytes{'O','\n','t','e','s','t','.','w','a','v'}));
    assert(request("G\ntest.wav\n4") == (Bytes{'O','5','6','7','8'}));
    assert(request("G\ntest.wav\n8") == (Bytes{'O'}));
    assert(request("G\nlink.wav\n0") == (Bytes{'E'}));
    assert(request("G\n../test.wav\n0") == (Bytes{'E'}));
    assert(request("G\ntest.wav\n-1") == (Bytes{'E'}));
    AudioPlayback playback; unsigned writes = 0, reads = 0, recovered = 0;
    Bytes expected{1,2,3,4,5,6,7,8};
    auto read = [&](uint8_t* data,size_t) -> ssize_t { if (reads++) return -EAGAIN; std::copy(expected.begin(),expected.end(),data); return expected.size(); };
    auto write = [&](const uint8_t* data,size_t size) -> ssize_t {
        if (writes++ == 0) { assert(size == 8 && data[0] == 1); return 4; }
        assert(size == 4 && data[0] == 5); return -EAGAIN;
    };
    assert(playback.pump(read,write,[&]{ ++recovered; }));
    assert(playback.pump(read,write,[&]{ ++recovered; }));
    assert(playback.pump(read,[](const uint8_t*,size_t)->ssize_t{return -EPIPE;},[&]{ ++recovered; }));
    assert(recovered == 1);
    assert(playback.pump(read,[](const uint8_t* data,size_t size)->ssize_t{ assert(size == 768); for(size_t i=0;i<size;++i) assert(data[i]==0); return size; },[]{}));
    puts("DS4 reports/features, jiggler timing, dark macros, sound file checks and microphone PCM backpressure passed");
}
