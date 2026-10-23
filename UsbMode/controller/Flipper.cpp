#include "Flipper.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <selinux/selinux.h>
#endif

namespace controller {
namespace {
unsigned le(const Bytes& b, size_t p) { return b[p] | (b[p + 1] << 8); }
void number(Bytes& b, unsigned n, unsigned count) { while (count--) { b.push_back(n & 255); n >>= 8; } }
bool header(const Bytes& b) {
    return b.size() >= 12 && std::equal(b.begin(),b.begin() + 5,Bytes{'F','U','S','B',1}.begin()) &&
            b[5] >= 1 && b[5] <= 9 && !b[7] && !b[10] && !b[11] && le(b,8) <= 4096 &&
            ((b[5] == 5 && b[6] >= 0x81 && b[6] <= 0x8f) || (b[5] != 5 && !b[6]));
}
}
bool FlipperManifest::parse(const Bytes& payload) {
    *this = {};
    size_t pos = 0;
    auto blob = [&](Bytes& b) {
        if (pos + 2 > payload.size()) return false;
        unsigned n = le(payload,pos); pos += 2;
        if (pos + n > payload.size()) return false;
        b.assign(payload.begin() + pos,payload.begin() + pos + n); pos += n; return true;
    };
    if (!blob(device) || !blob(configuration) || pos >= payload.size()) return false;
    unsigned count = payload[pos++];
    while (count--) {
        if (pos >= payload.size()) return false;
        unsigned id = payload[pos++]; Bytes b;
        if (strings.count(id) || !blob(b) || b.size() < 2 || b.size() > 254 || b[0] != b.size() ||
                b[1] != 3 || b.size() % 2 || (!id && (b.size() < 4 || le(b,2) != 0x409))) return false;
        // Configfs strings must be valid, single-line UTF-8 without embedded NUL.
        for (size_t i = 2; id && i < b.size(); i += 2) {
            unsigned c = le(b,i);
            if (c < 32 || c == 127 || (c >= 0xd800 && c <= 0xdfff)) return false;
        }
        strings.emplace(id,std::move(b));
    }
    if (pos != payload.size() || device.size() != 18 || device[0] != 18 || device[1] != 1 ||
            device[17] != 1 || (device[7] != 8 && device[7] != 16 && device[7] != 32 && device[7] != 64) ||
            configuration.size() < 9 || configuration[0] != 9 || configuration[1] != 2 ||
            le(configuration,2) != configuration.size() || !configuration[5] || (configuration[7] & 0x9f) != 0x80) return false;
    for (unsigned i : {14,15,16}) if (device[i] && !strings.count(device[i])) return false;
    if (configuration[6] && !strings.count(configuration[6])) return false;
    std::map<unsigned,unsigned> classes, unions, declared, functional, calls, associations;
    std::map<unsigned,std::vector<FlipperEndpoint>> eps;
    int current = -1;
    for (pos = 9; pos < configuration.size();) {
        unsigned n = configuration[pos], type = pos + 1 < configuration.size() ? configuration[pos + 1] : 0;
        if (n < 2 || pos + n > configuration.size()) return false;
        const uint8_t* b = configuration.data() + pos;
        if (type == 4) {
            if (n != 9 || b[3] || classes.count(b[2]) || (b[5] != 2 && b[5] != 10) ||
                    (b[5] == 2 && (b[6] != 2 || b[7] > 1)) || (b[5] == 10 && (b[6] || b[7])) || (b[8] && !strings.count(b[8]))) return false;
            current = b[2]; classes[current] = b[5]; declared[current] = b[4];
        } else if (type == 5) {
            if (n != 7 || current < 0 || !(b[2] & 15) || (b[2] & 0x70) || b[4] < 1 || b[4] > 64 || b[5] ||
                    (b[3] != 2 && b[3] != 3) || (b[3] == 3 && !b[6])) return false;
            FlipperEndpoint ep{b[2],b[4],b[3]};
            if (std::any_of(endpoints.begin(),endpoints.end(),[&](auto e) { return e.address == ep.address; })) return false;
            endpoints.push_back(ep); eps[current].push_back(ep);
        } else if (type == 0x24) {
            if (current < 0 || classes[current] != 2 || n < 3) return false;
            unsigned bit = b[2] == 0 ? 1 : b[2] == 1 ? 2 : b[2] == 2 ? 4 : b[2] == 6 ? 8 : 0;
            if (!bit || (functional[current] & bit)) return false;
            functional[current] |= bit;
            if (b[2] == 1 && n == 5) calls[current] = b[4];
            if (b[2] == 6) {
                if (n != 5 || b[3] != current || unions.count(current)) return false;
                unions[current] = b[4];
            } else if (!((b[2] == 0 && n == 5) || (b[2] == 1 && n == 5) || (b[2] == 2 && n == 4))) return false;
        } else {
            if (type != 11 || n != 8 || b[3] != 2 || b[4] != 2 || b[5] != 2 || b[6] > 1 ||
                    associations.count(b[2]) || (b[7] && !strings.count(b[7]))) return false;
            associations[b[2]] = b[2] + 1;
        }
        pos += n;
    }
    if (unions.empty() || unions.size() > 2 || classes.size() != unions.size() * 2 ||
            configuration[4] != classes.size()) return false;
    for (auto entry : classes) if (eps[entry.first].size() != declared[entry.first]) return false;
    for (auto entry : unions) {
        unsigned c = entry.first, d = entry.second;
        if (classes[c] != 2 || classes[d] != 10 || functional[c] != 15 || calls[c] != d ||
                eps[c].size() != 1 || eps[d].size() != 2) return false;
        auto notify = eps[c][0];
        if (notify.kind != 3 || !(notify.address & 128)) return false;
        unsigned out = 0, in = 0;
        for (auto ep : eps[d]) { if (ep.kind != 2) return false; if (ep.address & 128) in = ep.address; else out = ep.address; }
        if (!in || !out) return false;
        for (auto p : ports) if (p.data == d) return false;
        ports.push_back({c,d,notify.address,out,in});
    }
    for (auto entry : associations) if (!unions.count(entry.first) || unions[entry.first] != entry.second) return false;
    std::vector<FunctionBudget> budget;
    for (auto p : ports) {
        auto need = kFunctions[9];
        const auto notify = std::find_if(endpoints.begin(),endpoints.end(),[&](auto e) { return e.address == p.notification; });
        need.notify = std::max(need.notify,(notify->packet + 3) / 4 + 1);
        budget.push_back(need);
    }
    return endpoints.size() == ports.size() * 3 && std::string(endpointBudget(budget).reason) == "ok";
}
std::string FlipperManifest::text(unsigned index) const {
    auto found = strings.find(index);
    if (!index || found == strings.end()) return {};
    std::string out;
    for (size_t i = 2; i < found->second.size(); i += 2) {
        unsigned c = le(found->second,i);
        if (c < 128) out += static_cast<char>(c);
        else {
            if (c >= 2048) out += static_cast<char>(0xe0 | (c >> 12));
            out += static_cast<char>((c >= 2048 ? 0x80 : 0xc0) | ((c >> 6) & (c >= 2048 ? 63 : 31)));
            out += static_cast<char>(0x80 | (c & 63));
        }
    }
    return out;
}
Bytes FlipperManifest::descriptors() const {
    Bytes out; number(out,3,4); number(out,0,4); number(out,3,4);
    number(out,ports.size() * 10,4); number(out,ports.size() * 10,4);
    for (bool high : {false,true}) for (unsigned i = 0; i < ports.size(); ++i) {
        const auto& p = ports[i];
        auto endpoint = [&](unsigned address, unsigned kind) {
            auto ep = std::find_if(endpoints.begin(),endpoints.end(),[&](auto e) { return e.address == address; });
            unsigned packet = kind == 2 ? (high ? 512 : 64) : ep->packet;
            Bytes bytes{7,5,static_cast<uint8_t>(address),static_cast<uint8_t>(kind),
                static_cast<uint8_t>(packet),static_cast<uint8_t>(packet >> 8),static_cast<uint8_t>(kind == 3 ? (high ? 9 : 16) : 0)};
            out.insert(out.end(),bytes.begin(),bytes.end());
        };
        uint8_t c = i * 2, d = c + 1;
        const Bytes interfaces{8,11,c,2,2,2,1,1, 9,4,c,0,1,2,2,1,1,
            5,0x24,0,0x10,1, 5,0x24,1,0,d, 4,0x24,2,2, 5,0x24,6,c,d};
        out.insert(out.end(),interfaces.begin(),interfaces.end());
        endpoint(p.notification,3);
        const Bytes data{9,4,d,0,2,10,0,0,0}; out.insert(out.end(),data.begin(),data.end());
        endpoint(p.output,2); endpoint(p.input,2);
    }
    unsigned size = out.size(); for (unsigned i = 0; i < 4; ++i) out[4 + i] = (size >> (i * 8)) & 255;
    return out;
}
Bytes FlipperManifest::labels() const {
    const std::string label = text(device[15]);
    Bytes b; number(b,2,4); number(b,19 + label.size(),4); number(b,1,4); number(b,1,4); number(b,0x409,2);
    b.insert(b.end(),label.begin(),label.end()); b.push_back(0); return b;
}
Bytes flipperWire(const FlipperFrame& frame) {
    Bytes b{'F','U','S','B',1,frame.type,frame.endpoint,0}; number(b,frame.data.size(),4);
    b.insert(b.end(),frame.data.begin(),frame.data.end()); return b;
}
FlipperChannel::~FlipperChannel() { if (client_ >= 0) close(client_); if (server_ >= 0) close(server_); }
bool FlipperChannel::listen(const char* name) {
#ifdef __ANDROID__
    if (setsockcreatecon("u:object_r:flipper_usb_socket:s0") < 0) return false;
#endif
    server_ = socket(AF_UNIX,SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC,0);
#ifdef __ANDROID__
    if (setsockcreatecon(nullptr) < 0) return false;
#endif
    if (server_ < 0 || strlen(name) >= sizeof(sockaddr_un::sun_path) - 1) return false;
    sockaddr_un a{}; a.sun_family = AF_UNIX; memcpy(a.sun_path + 1,name,strlen(name));
    return bind(server_,reinterpret_cast<sockaddr*>(&a),offsetof(sockaddr_un,sun_path) + 1 + strlen(name)) == 0 &&
            ::listen(server_,1) == 0;
}
bool FlipperChannel::reply(FlipperFrame f) {
    if (replies_.size() >= (f.type == 4 ? 15u : 16u) || f.data.size() > 4096) return false;
    auto wire = flipperWire(f);
    if (f.type == 2) replies_.insert(replies_.begin() + (sent_ ? 1 : 0),std::move(wire));
    else replies_.push_back(std::move(wire));
    return true;
}
bool FlipperChannel::control(const Bytes& setup, int64_t time) {
    if (controlDeadline_ || setup.size() < 8 || setup.size() > 4104) return false;
    setup_ = setup; controlDeadline_ = time + 3000;
    return reply({2,0,setup});
}
bool FlipperChannel::frame(FlipperFrame f, int64_t time) {
    if (f.type == 6 || f.type == 9) return false;
    if (!hello_) {
        if (f.type != 7 || !f.data.empty()) return false;
        hello_ = true; deadline_ = time + 300000; return reply({8,0,{}});
    }
    if (!configured_) {
        if (f.type != 1 || !manifest.parse(f.data)) return false;
        configured_ = true; deadline_ = time + 1800000; return true;
    }
    if (f.type == 5) {
        auto ep = std::find_if(manifest.endpoints.begin(),manifest.endpoints.end(),[&](auto e) { return e.address == f.endpoint && (e.address & 128); });
        if (ep == manifest.endpoints.end() || f.data.size() > ep->packet) return false;
        input.push_back(std::move(f)); return true;
    }
    if (f.type != 3 || !controlDeadline_ || !results.empty() || f.data.empty() || f.data[0] > 1 ||
            (f.data[0] && f.data.size() != 1) || (!(setup_[0] & 128) && f.data.size() != 1) ||
            f.data.size() > 1 + le(setup_,6)) return false;
    results.push_back(std::move(f)); controlDeadline_ = 0; return true;
}
bool FlipperChannel::pump(int64_t time) {
    int fd = accept4(server_,nullptr,nullptr,SOCK_NONBLOCK | SOCK_CLOEXEC);
    if (fd >= 0) {
        if (client_ < 0 && authorize_(fd)) { client_ = fd; deadline_ = time + 2000; }
        else { auto error = flipperWire({9,0,{}}); send(fd,error.data(),error.size(),MSG_NOSIGNAL | MSG_DONTWAIT); close(fd); }
    }
    if (client_ < 0) return true;
    if (time > deadline_ || (controlDeadline_ && time > controlDeadline_)) return false;
    for (unsigned i = 0; i < 32 && input.size() < 16; ++i) {
        uint8_t b[4108]; ssize_t n = recv(client_,b,wanted_ - receive_.size(),MSG_DONTWAIT);
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) break;
        if (n <= 0) return false;
        receive_.insert(receive_.end(),b,b + n);
        if (receive_.size() != wanted_) continue;
        if (wanted_ == 12) {
            if (!header(receive_)) return false;
            wanted_ = 12 + le(receive_,8);
            if (wanted_ != 12) continue;
        }
        FlipperFrame f{receive_[5],receive_[6],Bytes(receive_.begin() + 12,receive_.end())};
        receive_.clear(); wanted_ = 12;
        if (!frame(std::move(f),time)) return false;
    }
    pollfd closed{client_,POLLRDHUP,0};
    if (poll(&closed,1,0) > 0 && (closed.revents & (POLLRDHUP | POLLHUP | POLLERR))) return false;
    // Check EOF even when backpressure has filled the endpoint queue.
    uint8_t byte; ssize_t peek = recv(client_,&byte,1,MSG_PEEK | MSG_DONTWAIT);
    if (!peek || (peek < 0 && errno != EAGAIN && errno != EINTR)) return false;
    if (!replies_.empty()) {
        auto& b = replies_.front(); ssize_t n = send(client_,b.data() + sent_,b.size() - sent_,MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n > 0) { sent_ += n; if (sent_ == b.size()) { replies_.pop_front(); sent_ = 0; } }
        else if (n == 0 || (errno != EAGAIN && errno != EINTR)) return false;
    }
    return true;
}
}
