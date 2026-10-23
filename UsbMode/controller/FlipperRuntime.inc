// The Flipper lease uses the same daemon, FunctionFS AIO operations and helper
// journal as controller sessions. It never grabs Android's input devices.
constexpr unsigned kFlipperDeferOutStatus = _IO('g',131);
class FlipperIo {
public:
    explicit FlipperIo(const AioOps& ops = kKernelAio) : ops_(ops) { }
    ~FlipperIo() {
        if (context_) ops_.destroy(context_);
        for (auto& e : endpoints_) if (e.fd >= 0) close(e.fd);
    }
    bool start(const FlipperManifest& manifest, const std::string& path) {
        if (ops_.setup(manifest.endpoints.size(),&context_) < 0) return false;
        unsigned node = 0;
        for (auto p : manifest.ports) for (unsigned address : {p.notification,p.output,p.input}) {
            Endpoint e; e.address = address;
            e.fd = open((path + "/ep" + std::to_string(++node)).c_str(),O_RDWR | O_NONBLOCK | O_CLOEXEC);
            endpoints_.push_back(std::move(e));
            if (endpoints_.back().fd < 0) return false;
        }
        return true;
    }
    bool pump(FlipperChannel& channel, bool enabled) {
        io_event events[6]{}; timespec timeout{};
        long n = ops_.events(context_,0,6,events,&timeout);
        if (n < 0) return errno == EINTR;
        for (long i = 0; i < n; ++i) {
            if (events[i].data >= endpoints_.size()) return false;
            auto& e = endpoints_[events[i].data]; e.busy = false;
            if (events[i].res < 0) return false;
            if (e.address & 128) {
                if (events[i].res != static_cast<int64_t>(e.size)) return false;
            } else {
                if (events[i].res > 4096) return false;
                e.size = events[i].res; e.complete = true;
            }
        }
        for (unsigned i = 0; i < endpoints_.size(); ++i) {
            auto& e = endpoints_[i];
            if (e.complete && channel.reply({4,static_cast<uint8_t>(e.address),Bytes(e.bytes.begin(),e.bytes.begin() + e.size)})) e.complete = false;
            if (!enabled || e.busy || e.complete) continue;
            if (e.address & 128) {
                auto f = std::find_if(channel.input.begin(),channel.input.end(),[&](const auto& frame) { return frame.endpoint == e.address; });
                if (f == channel.input.end()) continue;
                e.size = f->data.size(); std::copy(f->data.begin(),f->data.end(),e.bytes.begin());
                channel.input.erase(f);
            } else e.size = e.bytes.size();
            e.request = {}; e.request.aio_data = i; e.request.aio_fildes = e.fd;
            e.request.aio_lio_opcode = (e.address & 128) ? IOCB_CMD_PWRITE : IOCB_CMD_PREAD;
            e.request.aio_buf = reinterpret_cast<uintptr_t>(e.bytes.data()); e.request.aio_nbytes = e.size;
            iocb* requests[] = {&e.request};
            long submitted = ops_.submit(context_,1,requests);
            // Do not drop a guest packet on a transient submit failure.
            if (submitted != 1) return false;
            e.busy = true;
        }
        return true;
    }
private:
    struct Endpoint {
        int fd = -1; unsigned address = 0;
        bool busy = false, complete = false;
        size_t size = 0;
        std::array<uint8_t,4096> bytes{};
        iocb request{};
    };
    const AioOps& ops_;
    aio_context_t context_ = 0;
    std::vector<Endpoint> endpoints_;
};

int64_t flipperTime() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME,&ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}
bool flipperLease(const std::string& owner, const std::string& start, const std::string& uid, int64_t time) {
    const auto get = [](const char* key) { return property(std::string(kPrefix) + key); };
    const std::string stamp = get("flipper_tick");
    if (stamp.empty() || stamp.size() > 16 || stamp.find_first_not_of("0123456789") != std::string::npos) return false;
    const int64_t tick = strtoll(stamp.c_str(),nullptr,10);
    if (time < 0) time = flipperTime();
    return tick > 0 && time >= tick && time - tick < 1500 && get("flipper_pid") == owner &&
            get("flipper_start") == start && get("flipper_uid") == uid && process(owner,start);
}
bool flipperIdentity(const FlipperManifest& manifest, const std::string& root) {
    const auto word = [&](unsigned i) { char b[16]; snprintf(b,sizeof(b),"0x%04x",manifest.device[i] | (manifest.device[i + 1] << 8)); return std::string(b); };
    const std::pair<const char*,std::string> values[] = {
        {"vid",word(8)}, {"pid",word(10)}, {"release",word(12)}, {"manufacturer",manifest.text(manifest.device[14])},
        {"product",manifest.text(manifest.device[15])}, {"serial",manifest.text(manifest.device[16])},
        {"configuration",manifest.text(manifest.configuration[6])}
    };
    for (const auto& v : values) {
        const std::string path = root + "/flipper-" + v.first;
        int fd = open(path.c_str(),O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW,0600);
        if (fd < 0) return false;
        const bool ok = write(fd,v.second.data(),v.second.size()) == static_cast<ssize_t>(v.second.size());
        close(fd); if (!ok) return false;
    }
    return true;
}
// FunctionFS translates wIndex to the function's local interface numbering.
// Map that local interface back to the guest's Union master before forwarding.
bool flipperSetup(Bytes& setup, const FlipperManifest& manifest) {
    if (setup.size() != 8 || (setup[0] != 0x21 && setup[0] != 0xa1) || setup[5] ||
            setup[4] % 2 || setup[4] / 2 >= manifest.ports.size()) return false;
    const unsigned request = setup[1], length = setup[6] | (setup[7] << 8);
    if (!((setup[0] == 0x21 && request == 0x20 && length == 7) ||
            (setup[0] == 0xa1 && request == 0x21 && length == 7) ||
            (setup[0] == 0x21 && (request == 0x22 || request == 0x23) && !length))) return false;
    setup[4] = manifest.ports[setup[4] / 2].control; return true;
}
void flipperStall(int ep0, bool input) {
    uint8_t ignored = 0;
    if (input) read(ep0,&ignored,0); else write(ep0,&ignored,0);
}
int runFlipper() {
    const auto get = [](const char* key) { return property(std::string(kPrefix) + key); };
    const std::string owner = get("flipper_pid"), start = get("flipper_start"), uid = get("flipper_uid");
    FlipperChannel channel([&](int fd) {
        ucred peer{}; socklen_t n = sizeof(peer);
        return getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&n) == 0 && std::to_string(peer.pid) == owner &&
                std::to_string(peer.uid) == uid && flipperLease(owner,start,uid,-1);
    });
    if (!flipperLease(owner,start,uid,-1) || !channel.listen()) return 1;
    const std::string root = "/data/misc/andr36oid-usb-drive", path = "/dev/usb-ffs/flipper";
    FlipperIo io;
    int ep0 = -1, result = 0;
    bool requested = false, enabled = false, sawEnable = false, registered = false;
    int64_t deadline = now() + 2000, controlDeadline = 0;
    bool controlInput = false;
    while (!stopped && flipperLease(owner,start,uid,-1)) {
        const int64_t time = now();
        if (!channel.pump(time)) { result = 1; break; }
        if (!channel.accepted() && time > deadline) break;
        if (channel.configured() && !requested) {
            // Recheck the shared owner immediately before asking it to journal g1.
            if (get("request") == "start" || get("state") == "sharing" || get("state") == "preparing" ||
                    get("state") == "working" || get("state") == "stopping" ||
                    access((root + "/controller").c_str(),F_OK) == 0 || access((root + "/owned").c_str(),F_OK) == 0 ||
                    !flipperIdentity(channel.manifest,root)) { result = 1; break; }
            set("functions","262144"); set("target","none"); set("ro","1"); set("webusb","0");
            set("owner",owner.c_str()); set("error",""); set("request","start"); requested = true; deadline = time + 10000;
        }
        if (requested) {
            if (get("request") != "start" || !get("error").empty()) break;
            const auto helper = get("helper"), helperStart = get("helper_start");
            if (registered && !process(helper,helperStart)) {
                set("request","stop");
#ifdef __ANDROID__
                __system_property_set("ctl.start","andr36oid-usb-drive");
#endif
                break;
            }
            if (!registered && get("flipper_mount") == "1") {
                ep0 = open((path + "/ep0").c_str(),O_RDWR | O_CLOEXEC);
                const auto desc = channel.manifest.descriptors(), labels = channel.manifest.labels();
                if (ep0 < 0 || write(ep0,desc.data(),desc.size()) != static_cast<ssize_t>(desc.size()) ||
                        write(ep0,labels.data(),labels.size()) != static_cast<ssize_t>(labels.size()) ||
                        ioctl(ep0,kFlipperDeferOutStatus,1) < 0 || !io.start(channel.manifest,path)) { result = 1; break; }
                fcntl(ep0,F_SETFL,O_NONBLOCK); registered = true; set("ready","1");
            }
            if (!sawEnable && time > deadline) break;
        }
        if (registered) {
            if (!channel.results.empty()) {
                const auto& reply = channel.results.front().data;
                ssize_t n;
                if (reply[0]) { flipperStall(ep0,controlInput); n = 0; }
                else if (controlInput) n = write(ep0,reply.data() + 1,reply.size() - 1);
                else { uint8_t byte; n = read(ep0,&byte,0); }
                if (n < 0 || (controlInput && !reply[0] && n != static_cast<ssize_t>(reply.size() - 1))) { result = 1; break; }
                channel.results.pop_front(); controlDeadline = 0;
            }
            if (controlDeadline && time > controlDeadline) { result = 1; break; }
            if (!controlDeadline) {
                pollfd p{ep0,POLLIN,0};
                if (poll(&p,1,0) > 0 && (p.revents & POLLIN)) {
                    usb_functionfs_event event{}; ssize_t n = read(ep0,&event,sizeof(event));
                    if (n != sizeof(event)) { result = 1; break; }
                    if (event.type == FUNCTIONFS_ENABLE) { enabled = true; sawEnable = true; }
                    if (event.type == FUNCTIONFS_UNBIND || (sawEnable && event.type == FUNCTIONFS_DISABLE)) break;
                    if (event.type == FUNCTIONFS_SETUP) {
                        const auto* b = reinterpret_cast<const uint8_t*>(&event.u.setup);
                        Bytes setup(b,b + 8); controlInput = setup[0] & 128;
                        if (!flipperSetup(setup,channel.manifest)) flipperStall(ep0,controlInput);
                        else {
                            unsigned length = setup[6] | (setup[7] << 8);
                            if (!controlInput && length) {
                                Bytes data(length); ssize_t count = read(ep0,data.data(),data.size());
                                if (count != static_cast<ssize_t>(length)) { result = 1; break; }
                                setup.insert(setup.end(),data.begin(),data.end());
                            }
                            if (!channel.control(setup,time)) { result = 1; break; }
                            controlDeadline = time + 3000;
                        }
                    }
                }
            }
            if (!io.pump(channel,enabled)) { result = 1; break; }
        }
        timespec pause{0,2000000}; nanosleep(&pause,nullptr);
    }
    // Closing FunctionFS cancels traffic. The shared helper alone unbinds,
    // removes functions and restores the journal; no retry creates a new lease.
    if (ep0 >= 0) close(ep0);
    set("flipper_tick","0");
    if (requested) set("request","stop");
    for (const char* name : {"vid","pid","release","manufacturer","product","serial","configuration"}) unlink((root + "/flipper-" + name).c_str());
    return result;
}
