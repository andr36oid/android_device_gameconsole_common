#define CONTROLLER_TEST_PROPERTIES
#define main controllerEntry
#include "../../controller/main.cpp"
#undef main

#include <cassert>

Bytes hex(const char* text) {
    Bytes b; unsigned n;
    while (*text) { assert(sscanf(text,"%2x",&n) == 1); b.push_back(n); text += 2; } return b;
}
Bytes manifest(bool dual = false) {
    Bytes device = hex("12010002ef02010883044057000101020301");
    Bytes config = hex("09024b00020100c0fa080b00020202000009040000010202000005240010010524010001042402000524060001070583030800ff09040100020a0000000705010240000107058202400001");
    if (dual) {
        auto second = hex("080b02020202000009040200010202000005240010010524010003042402000524060203070586030800ff09040300020a0000000705040240000107058502400001");
        config.insert(config.end(),second.begin(),second.end()); config[2] = config.size(); config[4] = 4;
    }
    Bytes b; auto blob = [&](const Bytes& data) { b.push_back(data.size() & 255); b.push_back(data.size() >> 8); b.insert(b.end(),data.begin(),data.end()); };
    blob(device); blob(config); b.push_back(4);
    b.push_back(0); blob({4,3,9,4});
    for (unsigned i = 1; i <= 3; ++i) { b.push_back(i); blob({4,3,static_cast<uint8_t>('a' + i),0}); }
    return b;
}
struct ChannelTest {
    FlipperChannel service;
    int client;
    std::string name;
    int64_t time = 1;
    explicit ChannelTest(bool auth = true) : service([auth](int fd) {
        ucred peer{}; socklen_t n = sizeof(peer);
        return auth && getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&n) == 0 && peer.pid == getpid() && peer.uid == getuid();
    }) {
        static unsigned index = 0;
        name = "flipper-test-" + std::to_string(getpid()) + "-" + std::to_string(++index);
        assert(service.listen(name.c_str()));
        client = socket(AF_UNIX,SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC,0); assert(client >= 0);
        sockaddr_un a{}; a.sun_family = AF_UNIX; memcpy(a.sun_path + 1,name.data(),name.size());
        assert(connect(client,reinterpret_cast<sockaddr*>(&a),offsetof(sockaddr_un,sun_path) + 1 + name.size()) == 0);
        assert(service.pump(time));
    }
    ~ChannelTest() { if (client >= 0) close(client); }
    bool sendFrame(FlipperFrame frame) {
        const auto b = flipperWire(frame);
        assert(write(client,b.data(),b.size()) == static_cast<ssize_t>(b.size())); return service.pump(++time);
    }
    void handshake() {
        assert(sendFrame({7,0,{}})); uint8_t ready[12]; assert(read(client,ready,12) == 12 && ready[5] == 8);
        assert(sendFrame({1,0,manifest()})); assert(service.configured());
    }
};
struct EndpointAio {
    static inline std::vector<iocb> requests;
    static inline std::vector<io_event> completions;
    static inline unsigned destroys = 0;
    static const AioOps& ops() {
        static const AioOps ops{
            [](unsigned n, aio_context_t* c) { assert(n == 3); *c = 1; return 0L; },
            [](aio_context_t) { destroys++; return 0L; },
            [](aio_context_t, long min, long, io_event* events, timespec*) {
                assert(min == 0); long n = completions.size(); std::copy(completions.begin(),completions.end(),events); completions.clear(); return n;
            },
            [](aio_context_t, long n, iocb** r) { assert(n == 1); requests.push_back(**r); return 1L; }
        }; return ops;
    }
};
int main(int argc, char** argv) {
    assert(argc == 2);
    for (bool dual : {false,true}) {
        FlipperManifest m; assert(m.parse(manifest(dual))); assert(m.ports.size() == (dual ? 2 : 1));
        assert(m.descriptors().size() == 20 + m.ports.size() * 132);
        assert(m.labels().size() == 20); assert(m.text(1) == "b");
        FILE* f = fopen((std::string(argv[1]) + (dual ? "/cdc-dual" : "/cdc-single")).c_str(),"wb");
        auto b = m.descriptors(); assert(fwrite(b.data(),1,b.size(),f) == b.size()); fclose(f);
    }
    {
        auto b = manifest(); b[28] = 4; FlipperManifest m; assert(!m.parse(b));
        b[28] = 2; assert(m.parse(b) && m.text(m.configuration[6]) == "c");
    }
    for (unsigned i = 0; i < manifest().size(); ++i) {
        auto b = manifest(); b.resize(i); FlipperManifest m; assert(!m.parse(b));
    }
    assert(std::string(endpointBudget({kFunctions[9],kFunctions[9]}).reason) == "ok");
    assert(std::string(endpointBudget({kFunctions[9],kFunctions[9],kFunctions[9]}).reason) == "endpoints");
    assert(std::string(policy(Flipper | Serial).reason) == "standalone");
    { ChannelTest t(false); uint8_t b[12]; assert(read(t.client,b,sizeof(b)) == 12 && b[5] == 9 && !t.service.accepted()); }
    { ChannelTest t; assert(!t.sendFrame({8,0,{}})); }
    { ChannelTest t; assert(!t.service.pump(2002)); }
    {
        ChannelTest t;
        auto hello = flipperWire({7,0,{}});
        for (auto byte : hello) { assert(write(t.client,&byte,1) == 1); assert(t.service.pump(++t.time)); }
        uint8_t ready[12]; assert(read(t.client,ready,12) == 12 && ready[5] == 8);
    }
    {
        ChannelTest t; t.handshake();
        int second = socket(AF_UNIX,SOCK_STREAM | SOCK_CLOEXEC,0); assert(second >= 0);
        sockaddr_un a{}; a.sun_family = AF_UNIX; memcpy(a.sun_path + 1,t.name.data(),t.name.size());
        assert(connect(second,reinterpret_cast<sockaddr*>(&a),offsetof(sockaddr_un,sun_path) + 1 + t.name.size()) == 0);
        assert(t.service.pump(100)); uint8_t b[12]; assert(read(second,b,12) == 12 && b[5] == 9); close(second);
        assert(t.sendFrame({5,0x82,{42}}) && t.service.input.front().data == Bytes{42});
    }
    {
        ChannelTest t; t.handshake();
        for (unsigned i = 0; i < 16; ++i) assert(t.sendFrame({5,0x82,Bytes(64,i)}));
        assert(t.service.input.size() == 16);
        assert(t.sendFrame({5,0x82,{99}}) && t.service.input.size() == 16);
        close(t.client); t.client = -1; assert(!t.service.pump(200));
    }
    { ChannelTest t; t.handshake(); assert(!t.sendFrame({7,0,{}})); }
    { ChannelTest t; t.handshake(); assert(!t.sendFrame({5,0x8e,{1}})); }
    { ChannelTest t; t.handshake(); assert(!t.sendFrame({5,0x83,Bytes(9)})); }
    { ChannelTest t; t.handshake(); assert(!t.sendFrame({3,0,{0}})); }
    { ChannelTest t; t.handshake(); assert(!t.sendFrame({6,0,{}})); }
    { ChannelTest t; t.handshake(); assert(!t.sendFrame({9,0,{}})); }
    for (unsigned offset : {4u,5u,6u,7u,10u,11u}) {
        ChannelTest t; auto b = flipperWire({7,0,{}}); b[offset] = 255;
        assert(write(t.client,b.data(),b.size()) == static_cast<ssize_t>(b.size())); assert(!t.service.pump(2));
    }
    { ChannelTest t; t.handshake(); close(t.client); t.client = -1; assert(!t.service.pump(100)); }
    { ChannelTest t; t.handshake(); assert(!t.service.pump(1800010)); }
    {
        ChannelTest t; t.handshake();
        for (unsigned i = 0; i < 15; ++i) assert(t.service.reply({4,1,Bytes(4096,i)}));
        assert(!t.service.reply({4,1,{42}}));
        assert(t.service.control(hex("2122010000000000"),100));
        assert(t.service.pump(100)); uint8_t b[32];
        assert(read(t.client,b,sizeof(b)) == 20 && b[5] == 2);
    }
    {
        ChannelTest t; t.handshake();
        auto setup = hex("212000000000070000c20100000008");
        assert(t.service.control(setup,100)); assert(!t.service.control(setup,100));
        assert(t.service.pump(100)); uint8_t b[64]; assert(read(t.client,b,sizeof(b)) == 27 && b[5] == 2);
        assert(t.sendFrame({3,0,{0}})); assert(t.service.results.front().data == Bytes{0});
        t.service.results.clear();
        assert(t.service.control(hex("a121000000000700"),100));
        assert(t.sendFrame({3,0,hex("0000c20100000008")}));
        assert(t.service.results.front().data == hex("0000c20100000008")); t.service.results.clear();
        assert(t.service.control(setup,100)); assert(!t.service.pump(3101));
    }
    {
        ChannelTest t; t.handshake();
        const std::string path = argv[1];
        for (unsigned i = 1; i <= 3; ++i) { int fd = open((path + "/ep" + std::to_string(i)).c_str(),O_CREAT | O_RDWR,0600); assert(fd >= 0); close(fd); }
        {
            FlipperIo io(EndpointAio::ops()); assert(io.start(t.service.manifest,path));
            assert(io.pump(t.service,false)); assert(EndpointAio::requests.empty());
            assert(t.sendFrame({5,0x82,{'b','a','n','n','e','r','>',' '}}));
            assert(io.pump(t.service,true)); assert(EndpointAio::requests.size() == 2);
            auto out = EndpointAio::requests[0], in = EndpointAio::requests[1];
            assert(out.aio_lio_opcode == IOCB_CMD_PREAD && in.aio_lio_opcode == IOCB_CMD_PWRITE);
            assert(std::string(reinterpret_cast<char*>(in.aio_buf),in.aio_nbytes) == "banner> ");
            memcpy(reinterpret_cast<void*>(out.aio_buf),"help\r",5);
            io_event complete{}; complete.data = out.aio_data; complete.res = 5; EndpointAio::completions.push_back(complete);
            assert(io.pump(t.service,true)); assert(t.service.pump(200));
            uint8_t reply[64]; assert(read(t.client,reply,sizeof(reply)) == 17 && reply[5] == 4 && reply[6] == 1 && !memcmp(reply + 12,"help\r",5));
            // An outstanding IN is retained while the PC is silent; OUT and control still progress.
            assert(EndpointAio::requests.size() == 3);
            auto setup = hex("2122000000000000"); assert(t.service.control(setup,200));
            assert(io.pump(t.service,true)); assert(EndpointAio::requests.size() == 3);
        }
        assert(EndpointAio::destroys == 1);
    }
    const std::string owner = std::to_string(getpid());
    std::string stat = readText("/proc/" + owner + "/stat"); stat.erase(0,stat.rfind(')') + 2);
    for (int i = 3; i < 22; ++i) stat.erase(0,stat.find(' ') + 1);
    const std::string start = stat.substr(0,stat.find(' '));
    testProperties[std::string(kPrefix) + "flipper_pid"] = owner;
    testProperties[std::string(kPrefix) + "flipper_start"] = start;
    testProperties[std::string(kPrefix) + "flipper_uid"] = "10001";
    testProperties[std::string(kPrefix) + "flipper_tick"] = "1000";
    assert(flipperLease(owner,start,"10001",1001));
    testProperties[std::string(kPrefix) + "flipper_tick"] = std::to_string(flipperTime());
    assert(flipperLease(owner,start,"10001",-1));
    testProperties[std::string(kPrefix) + "flipper_tick"] = "1000";
    assert(!flipperLease(owner,start,"10002",1001));
    assert(!flipperLease(owner,"0","10001",1001));
    assert(!flipperLease(owner,start,"10001",2501));
    assert(!flipperLease(owner,start,"10001",999));
    testProperties[std::string(kPrefix) + "flipper_tick"] = "invalid";
    assert(!flipperLease(owner,start,"10001",1001));
    FlipperManifest m; assert(m.parse(manifest(true)));
    auto setup = hex("2122010002000000"); assert(flipperSetup(setup,m) && setup[4] == 2);
    setup[4] = 1; assert(!flipperSetup(setup,m));
    puts("Flipper: descriptors, shared budget, peer credentials, framing, controls, bidirectional AIO, deadlines, EOF and cancellation passed");
}
