#include "Controller.h"
#include "Protocols.h"
#include "Services.h"
#include "TextFiles.h"
#include <cassert>
#include <cerrno>
#include <cstring>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>

using namespace controller;
Bytes discover(unsigned mac, uint8_t type = 1) {
    Bytes q(240); q[0] = q[1] = 1; q[2] = 6; q[4] = 0x12; q[5] = 0x34; q[28] = 2; q[33] = mac;
    q[236] = 99; q[237] = 130; q[238] = 83; q[239] = 99;
    q.insert(q.end(),{53,1,type}); return q;
}
int main(int argc, char** argv) {
    assert(argc == 2);
    std::set<std::pair<unsigned,unsigned>> identities;
    std::set<unsigned> masks;
    for (unsigned mask = 1; mask < 64; ++mask) masks.insert(mask);
    for (unsigned bit : {Media,Switch,Midi,Serial,Sound,Network,Ps3,GameCube,Xbox,Rndis,Ps4,Soundboard,Flipper}) {
        masks.insert(bit); masks.insert(bit | Storage | Adb);
    }
    for (unsigned mask : {Network | Keyboard | Mouse | Sound, Sound | Soundboard,
            Rndis | Keyboard | Mouse, Midi | Keyboard | Mouse, Keyboard | Media}) masks.insert(mask);
    unsigned valid = 0;
    for (unsigned mask : masks) {
        const auto p = policy(mask);
        if (std::string(p.reason) != "ok") continue;
        ++valid; assert(p.in <= 5 && p.out <= 3);
        assert(identities.insert({p.vendor,p.product}).second);
        if (!(mask & kConsoleControllers)) assert(p.vendor == 0x18d1);
    }
    assert(valid > 60);
    assert(std::string(policy(Network | Serial | Adb | Storage).reason) == "endpoints");
    assert(std::string(policy(Network | Rndis).reason) == "networks");
    assert(std::string(policy(Media | Midi).reason) == "routing");
    assert(std::string(policy(Switch | Keyboard).reason) == "standalone");
    assert(std::string(policy(Network | Keyboard | Mouse | Sound).reason) == "ok");
    assert(policy(Switch).vendor == 0x0f0d && policy(Switch).product == 0x92);
    assert(policy(Ps3).vendor == 0x054c && policy(Ps3).product == 0x0268);
    assert(policy(GameCube).vendor == 0x057e && policy(GameCube).product == 0x0337);
    assert(policy(Xbox).vendor == 0x045e && policy(Xbox).product == 0x0289);
    for (unsigned f : {Switch,Ps3,GameCube,Xbox}) {
        State s; auto neutral = padReport(f,s);
        if (f == GameCube) for (unsigned i = 4; i < 8; ++i) assert(neutral[i] == 128);
        assert(neutral.size() == (f == Switch ? 8 : f == Ps3 ? 49 : f == GameCube ? 37 : 20));
        s.buttons[A] = true; s.buttons[RT] = true; s.sticks[0] = -32768; s.sticks[1] = 32767;
        auto r = padReport(f,s); assert(r != neutral);
        if (f == Switch) { assert(r[0] == 0x82 && r[3] == 0 && r[4] == 255); }
        if (f == Ps3) { assert(r[3] == 0x42 && r[19] == 255 && r[24] == 255); }
        if (f == GameCube) { assert(r[2] == 2 && r[3] == 4 && r[9] == 255); for (unsigned i = 10; i < 37; ++i) assert(r[i] == 0); }
        if (f == Xbox) { assert(r[4] == 255 && r[11] == 255 && r[12] == 0 && r[13] == 0x80); }
    }
    PadControl pad;
    assert(pad.setup(Ps3,0x81,6,0x2100,0,9,{}).data == (Bytes{9,0x21,0x11,1,0,1,0x22,148,0}));
    assert(pad.setup(GameCube,0x81,6,0x2200,0,255,{}).data == cubeDescriptor());
    const auto original = pad.setup(Ps3,0xa1,1,0x3f2,0,17,{}).data;
    pad.identity("console-two");
    const auto identity = pad.setup(Ps3,0xa1,1,0x3f2,0,17,{}).data;
    assert(original != identity && (identity[4] & 3) == 2);
    for (uint16_t report : {0x3f2,0x3f5,0x3ef,0x301,0x3f7,0x3f8}) {
        auto r = pad.setup(Ps3,0xa1,1,report,0,64,{}); assert(r.action == ControlAction::Reply);
        assert(r.data[0] == (report & 255));
        assert(pad.setup(Ps3,0xa1,1,report,0,4,{}).data.size() == 4);
    }
    for (const char* id : {"01","ef","f5","f7","f8"}) {
        std::ifstream input(std::string(argv[1]) + "/ps3-" + id + ".hex");
        Bytes reference; unsigned byte;
        while (input >> std::hex >> byte) reference.push_back(byte);
        assert(!reference.empty());
        // F8 byte 7 echoes the EF calibration selector.
        if (std::string(id) == "f8") reference[7] = 0xa0;
        assert(pad.setup(Ps3,0xa1,1,0x300 | std::stoul(id,nullptr,16),0,128,{}).data == reference);
    }
    pad.received(Ps3,0x3f5,{0xf5,0,1,2,3,4,5,6});
    assert(pad.setup(Ps3,0xa1,1,0x3f5,0,64,{}).data[7] == 6);
    assert(pad.setup(Ps3,0xa1,1,0x399,0,64,{}).action == ControlAction::Stall);
    assert(pad.setup(Xbox,0xc1,6,0x4200,0,64,{}).data == (Bytes{16,0x42,0,1,1,2,20,6,255,255,255,255,255,255,255,255}));
    assert(pad.setup(Xbox,0xc1,1,0x100,0,64,{}).data.size() == 20);
    assert(pad.setup(Xbox,0xc1,1,0x200,0,64,{}).data == (Bytes{0,6,255,255,255,255}));
    assert(!pad.ready(GameCube)); const uint8_t init[] = {0x13}; pad.output(GameCube,init,1); assert(pad.ready(GameCube));
    pad.reset(); assert(!pad.ready(GameCube));
    State s; s.buttons[B] = s.buttons[LB] = s.buttons[X] = true;
    auto media = mediaReports(s); assert(media[0] == (Bytes{1,1})); assert(media[1][3] == 0x4b && media[1][4] == 5);
    MidiPads midi; s = {}; s.buttons[Up] = true; midi.update(s);
    assert(midi.pending().front() == (Bytes{0x99,36,100}));
    assert(midiEvent(midi.pending().front()) == (std::array<uint8_t,4>{9,0x99,36,100}));
    while (!midi.pending().empty()) midi.sent();
    s.buttons[RB] = true; midi.update(s); assert(midi.octave() == 1);
    s.buttons[Up] = false; midi.update(s); assert(midi.pending().front() == (Bytes{0x89,36,0}));
    while (!midi.pending().empty()) midi.sent();
    s.sticks[0] = 32767; s.sticks[1] = -32768; midi.update(s);
    assert(midi.pending().front() == (Bytes{0xe9,127,127})); midi.sent();
    assert(midi.pending().front() == (Bytes{0xb9,1,127}));
    midi.release(); assert(midi.pads() == 0);

    DhcpLease lease;
    auto q = discover(1); auto r = lease.reply(q,0); assert(r.size() == 300 && r[242] == 2);
    assert(r[16] == 192 && r[19] == 2 && r[4] == 0x12 && !lease.active(1));
    assert(lease.reply(discover(2),1).empty());
    q = discover(1,3); q.insert(q.end(),{50,4,192,168,36,2,54,4,192,168,36,1});
    r = lease.reply(q,1); assert(r[242] == 5 && lease.active(2));
    q = discover(1,3); q[12] = 192; q[13] = 168; q[14] = 36; q[15] = 2;
    assert(lease.reply(q,100)[242] == 5 && lease.active(3601));
    q = discover(1,3); q.insert(q.end(),{50,4,192,168,36,9}); assert(lease.reply(q,102)[242] == 6);
    q = discover(1,3); q.insert(q.end(),{54,4,10,0,0,1}); assert(lease.reply(q,102).empty());
    assert(lease.reply(discover(2),3701)[242] == 2 && !lease.active(3701));
    for (size_t size = 0; size < 243; ++size) { q = discover(2); q.resize(size); assert(lease.reply(q,3800).empty()); }
    q = discover(2); q.insert(q.end(),{50,9,1}); assert(lease.reply(q,3800).empty());
    q = discover(2); q[24] = 1; assert(lease.reply(q,3800).empty());

    AudioBuffer buffer; uint8_t audio[AudioBuffer::kPeriod]{};
    buffer.append(audio,1); assert(!buffer.next());
    for (unsigned i = 0; i < 6; ++i) { audio[0] = i; buffer.append(audio,sizeof(audio)); }
    assert(buffer.next()->front() == 2 && buffer.next()->size() == sizeof(audio));
    for (unsigned i = 2; i < 6; ++i) { assert(buffer.next()->front() == i); buffer.sent(); }
    assert(!buffer.next()); buffer.append(audio,192); buffer.clear(); assert(!buffer.next());

    AudioBridge bridge;
    unsigned reads = 0, writes = 0, recovered = 0;
    bool blocked = true;
    auto fakePcm = [&](uint8_t* out, size_t size) -> ssize_t {
        if (reads++ % 2) return -EAGAIN;
        memset(out,42,size); return size;
    };
    auto player = [&](const uint8_t* data, size_t size) -> ssize_t {
        assert(data[0] == 42); if (blocked) return -EAGAIN; ++writes; return size;
    };
    auto recover = [&] { ++recovered; };
    for (unsigned i = 0; i < 8; ++i) assert(bridge.pump(fakePcm,player,recover));
    blocked = false; assert(bridge.pump(fakePcm,player,recover)); assert(writes == 4);
    assert(bridge.pump([](uint8_t*,size_t) -> ssize_t { return -EPIPE; },player,recover));
    assert(recovered == 1);
    assert(!bridge.pump([](uint8_t*,size_t) -> ssize_t { return 3; },player,recover));
    assert(!bridge.pump(fakePcm,[](const uint8_t*,size_t) -> ssize_t { return -EIO; },recover));

    std::string root = std::string(getenv("TMPDIR")) + "/text-files";
    assert(mkdir(root.c_str(),0700) == 0);
    auto list = textRequest(root,{'L','\n','h','i'}); assert(list == (Bytes{'O'}));
    std::ofstream(root + "/type/a.txt") << "hello\n";
    std::ofstream(root + "/type/large.txt") << std::string(65537,'a');
    assert(symlink("a.txt",(root + "/type/link.txt").c_str()) == 0);
    list = textRequest(root,{'L','\n'}); assert(list == (Bytes{'O','\n','a','.','t','x','t'}));
    assert(textRequest(root,{'R','\n','a','.','t','x','t'}) == (Bytes{'O','h','e','l','l','o','\n'}));
    std::ofstream(root + "/type/ü.txt") << "unicode name";
    const std::string unicode = "R\nü.txt";
    auto unicodeFile = textRequest(root,Bytes(unicode.begin(),unicode.end()));
    assert(std::string(unicodeFile.begin() + 1,unicodeFile.end()) == "unicode name");
    unlink((root + "/type/ü.txt").c_str());
    for (const std::string bad : {"../a.txt","link.txt","large.txt","README.txt"}) {
        Bytes request{'R','\n'}; request.insert(request.end(),bad.begin(),bad.end()); assert(textRequest(root,request) == (Bytes{'E'}));
    }
    for (const char* file : {"a.txt","large.txt","link.txt","README.txt"}) unlink((root + "/type/" + file).c_str());
    rmdir((root + "/type").c_str()); rmdir(root.c_str());
    printf("%u valid USB sets: identities, reports, handshakes, MIDI, DHCP, audio buffers and bounded file reads passed\n",valid);
}
