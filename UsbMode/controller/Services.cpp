#include "Services.h"
#include "TextFiles.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <dirent.h>
#include <fcntl.h>
#include <net/if.h>
#include <arpa/inet.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <termios.h>
#include <unistd.h>
#include <fstream>
#ifdef __ANDROID__
#include <tinyalsa/asoundlib.h>
#include <sound/asound.h>
#endif

namespace controller {
namespace {
constexpr uint8_t kServer[] = {192,168,36,1}, kPeer[] = {192,168,36,2};
bool same(const uint8_t* a, const uint8_t* b) { return memcmp(a,b,4) == 0; }
std::string text(const std::string& path) { std::ifstream in(path); std::string s; std::getline(in,s); return s; }
int card(const std::string& id) {
    for (int n = 0; n < 32; ++n) if (text("/proc/asound/card" + std::to_string(n) + "/id") == id) return n;
    return -1;
}
}
Bytes DhcpLease::reply(const Bytes& q, int64_t time) {
    if (q.size() < 240 || q.size() > 1500 || q[0] != 1 || q[1] != 1 || q[2] != 6 || q[3] ||
            q[236] != 99 || q[237] != 130 || q[238] != 83 || q[239] != 99) return {};
    for (unsigned i = 24; i < 28; ++i) if (q[i]) return {}; // No relay or forwarding.
    Bytes requested, server;
    unsigned type = 0;
    for (size_t i = 240; i < q.size();) {
        unsigned code = q[i++];
        if (code == 255) break;
        if (!code) continue;
        if (i >= q.size()) return {};
        unsigned size = q[i++]; if (i + size > q.size()) return {};
        if (code == 53) { if (size != 1 || type) return {}; type = q[i]; }
        if (code == 50) { if (size != 4 || !requested.empty()) return {}; requested.assign(q.begin() + i,q.begin() + i + size); }
        if (code == 54) { if (size != 4 || !server.empty()) return {}; server.assign(q.begin() + i,q.begin() + i + size); }
        i += size;
    }
    if (!server.empty() && !same(server.data(),kServer)) return {};
    const bool owner = std::equal(client_.begin(),client_.end(),q.begin() + 28);
    if (expires_ > time && !owner) return {};
    if (type == 7 && owner) { expires_ = 0; acknowledged_ = false; return {}; }
    if (type != 1 && type != 3) return {};
    const uint8_t zero[4]{};
    const bool valid = requested.empty() ? same(q.data() + 12,kPeer)
            : same(requested.data(),kPeer) && (same(q.data() + 12,zero) || same(q.data() + 12,kPeer));
    unsigned response = type == 1 ? 2 : valid ? 5 : 6;
    if (response != 6) {
        std::copy_n(q.begin() + 28,6,client_.begin());
        expires_ = time + (response == 2 ? 60 : 3600); acknowledged_ = response == 5;
    }
    Bytes r(q.begin(),q.begin() + 240); r[0] = 2;
    std::fill(r.begin() + 12,r.begin() + 28,0);
    std::fill(r.begin() + 44,r.begin() + 236,0);
    if (response != 6) std::copy_n(kPeer,4,r.begin() + 16);
    std::copy_n(kServer,4,r.begin() + 20);
    r.insert(r.end(), {53,1,static_cast<uint8_t>(response),54,4,192,168,36,1});
    if (response != 6) r.insert(r.end(), {1,4,255,255,255,0,51,4,0,0,14,16,
                                       58,4,0,0,7,8,59,4,0,0,12,78});
    r.push_back(255); r.resize(std::max<size_t>(300,r.size()),0);
    return r;
}
void AudioBuffer::append(const uint8_t* bytes, size_t size) {
    if (!size || size % 4 || size > kPeriod) return;
    while (queue_.size() >= kLimit / kPeriod) queue_.pop_front();
    queue_.emplace_back(bytes,bytes + size);
}
bool AudioBridge::pump(const Read& read, const Write& write, const std::function<void()>& recover) {
    for (unsigned i = 0; i < 4; ++i) {
        uint8_t data[AudioBuffer::kPeriod]; const ssize_t n = read(data,sizeof(data));
        if (n == -EAGAIN || n == -EINTR || n == 0) break;
        if (n == -EPIPE || n == -ESTRPIPE) { buffer_.clear(); recover(); break; }
        if (n < 0 || n > static_cast<ssize_t>(sizeof(data)) || n % 4) { buffer_.clear(); return false; }
        buffer_.append(data,n);
    }
    for (unsigned i = 0; i < 4 && buffer_.next(); ++i) {
        const auto* data = buffer_.next(); const ssize_t n = write(data->data(),data->size());
        if (n == -EAGAIN || n == -EINTR) break;
        if (n != static_cast<ssize_t>(data->size())) { buffer_.clear(); return false; }
        buffer_.sent();
    }
    return true;
}
Services::Services(unsigned functions, Publish publish, Authorize authorize, std::string typeRoot)
        : functions_(functions), publish_(std::move(publish)), authorize_(std::move(authorize)), typeRoot_(std::move(typeRoot)) { }
Services::~Services() {
#ifdef __ANDROID__
    if (pcm_) pcm_close(static_cast<pcm*>(pcm_));
#endif
    for (int fd : {net_,serial_,audioServer_,audioClient_,files_,fileClient_}) if (fd >= 0) close(fd);
    for (const char* key : {"link","peer","net_iface","serial_text","audio"}) publish_(key,"");
}
int Services::midiNode() {
    const int n = card("ar36midi");
    return n < 0 ? -1 : open(("/dev/snd/midiC" + std::to_string(n) + "D0").c_str(),O_WRONLY | O_NONBLOCK | O_CLOEXEC);
}
bool Services::network(int64_t time) {
    if (net_ < 0) {
        netName_ = text(std::string("/config/usb_gadget/g1/functions/") + (functions_ & Network ? "ncm.link" : "rndis.link") + "/ifname");
        if (netName_.empty() || netName_.size() >= IFNAMSIZ || !if_nametoindex(netName_.c_str())) return true;
        net_ = socket(AF_INET,SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC,0);
        if (net_ < 0) return false;
        ifreq req{}; strncpy(req.ifr_name,netName_.c_str(),IFNAMSIZ - 1);
        auto* ip = reinterpret_cast<sockaddr_in*>(&req.ifr_addr);
        ip->sin_family = AF_INET; inet_pton(AF_INET,"192.168.36.1",&ip->sin_addr);
        if (ioctl(net_,SIOCSIFADDR,&req) < 0) return false;
        inet_pton(AF_INET,"255.255.255.0",&ip->sin_addr);
        if (ioctl(net_,SIOCSIFNETMASK,&req) < 0 || ioctl(net_,SIOCGIFFLAGS,&req) < 0) return false;
        req.ifr_flags |= IFF_UP;
        if (ioctl(net_,SIOCSIFFLAGS,&req) < 0) return false;
        int one = 1;
        if (setsockopt(net_,SOL_SOCKET,SO_BROADCAST,&one,sizeof(one)) < 0 ||
                setsockopt(net_,SOL_SOCKET,SO_BINDTODEVICE,netName_.c_str(),netName_.size() + 1) < 0) return false;
        publish_("net_iface",netName_);
        sockaddr_in local{}; local.sin_family = AF_INET; local.sin_port = htons(67);
        if (bind(net_,reinterpret_cast<sockaddr*>(&local),sizeof(local)) < 0) return false;
    }
    for (unsigned i = 0; i < 8; ++i) {
        uint8_t packet[1501]; sockaddr_in from{}; socklen_t size = sizeof(from);
        ssize_t n = recvfrom(net_,packet,sizeof(packet),0,reinterpret_cast<sockaddr*>(&from),&size);
        if (n < 0) { if (errno == EAGAIN || errno == EINTR) break; return false; }
        if (from.sin_port != htons(68)) continue;
        const auto reply = lease_.reply(Bytes(packet,packet + n),time / 1000);
        if (reply.empty()) continue;
        sockaddr_in target{}; target.sin_family = AF_INET; target.sin_port = htons(68);
        target.sin_addr.s_addr = INADDR_BROADCAST;
        if (sendto(net_,reply.data(),reply.size(),0,reinterpret_cast<sockaddr*>(&target),sizeof(target)) < 0
                && errno != EAGAIN && errno != EINTR && errno != ENETDOWN && errno != ENETUNREACH) return false;
    }
    if (time - lastStatus_ >= 500) {
        lastStatus_ = time;
        publish_("link",text("/sys/class/net/" + netName_ + "/carrier") == "1" ? "up" : "down");
        publish_("peer",lease_.active(time / 1000) ? "192.168.36.2" : "");
    }
    return true;
}
void Services::serial() {
    if (serial_ < 0) {
        const auto port = text("/config/usb_gadget/g1/functions/acm.echo/port_num");
        if (port.empty() || port.find_first_not_of("0123456789") != std::string::npos) return;
        serial_ = open(("/dev/ttyGS" + port).c_str(),O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
        if (serial_ < 0) return;
        termios settings{}; if (tcgetattr(serial_,&settings) == 0) { cfmakeraw(&settings); tcsetattr(serial_,TCSANOW,&settings); }
    }
    uint8_t data[128]; ssize_t n = read(serial_,data,sizeof(data));
    if (n > 0) {
        for (ssize_t i = 0; i < n; ++i) {
            if (data[i] == '\r' || data[i] == '\n') serialText_ += '\n';
            else if (data[i] >= 32 && data[i] < 127) serialText_ += static_cast<char>(data[i]);
        }
        if (serialText_.size() > 90) serialText_.erase(0,serialText_.size() - 90);
        publish_("serial_text",serialText_);
        if (echo_.size() + n <= 4096) echo_.insert(echo_.end(),data,data + n);
    } else if (n < 0 && errno != EAGAIN && errno != EINTR) { close(serial_); serial_ = -1; echo_.clear(); }
    if (!echo_.empty() && serial_ >= 0) {
        n = write(serial_,echo_.data(),echo_.size());
        if (n > 0) echo_.erase(echo_.begin(),echo_.begin() + n);
    }
}
void Services::audio() {
#ifdef __ANDROID__
    if (audioServer_ < 0) {
        audioServer_ = socket(AF_UNIX,SOCK_SEQPACKET | SOCK_NONBLOCK | SOCK_CLOEXEC,0);
        sockaddr_un address{}; address.sun_family = AF_UNIX;
        const char* name = "andr36oid-controller-audio"; memcpy(address.sun_path + 1,name,strlen(name));
        if (audioServer_ < 0 || bind(audioServer_,reinterpret_cast<sockaddr*>(&address),
                offsetof(sockaddr_un,sun_path) + 1 + strlen(name)) < 0 || listen(audioServer_,1) < 0) {
            audioStatus("error"); return;
        }
    }
    if (audioClient_ < 0) {
        int fd = accept4(audioServer_,nullptr,nullptr,SOCK_NONBLOCK | SOCK_CLOEXEC);
        if (fd >= 0) { if (authorize_(fd)) audioClient_ = fd; else close(fd); }
    }
    if (!pcm_) {
        const int n = card("UAC2Gadget"); if (n < 0) return;
        pcm_config config{}; config.channels = 2; config.rate = 48000;
        config.period_size = 192; config.period_count = 4; config.format = PCM_FORMAT_S16_LE;
        pcm* capture = pcm_open(n,0,PCM_IN,&config);
        if (!capture || !pcm_is_ready(capture)) { if (capture) pcm_close(capture); audioStatus("error"); return; }
        pcm_ = capture;
        const int fd = pcm_get_poll_fd(capture); fcntl(fd,F_SETFL,O_NONBLOCK);
        pcm_start(capture);
    }
    pcm* capture = static_cast<pcm*>(pcm_);
    const bool ok = audioBridge_.pump([&](uint8_t* data, size_t size) -> ssize_t {
        snd_xferi transfer{}; transfer.buf = data; transfer.frames = size / 4;
        if (ioctl(pcm_get_poll_fd(capture),SNDRV_PCM_IOCTL_READI_FRAMES,&transfer) < 0) return -errno;
        return transfer.result > 0 ? transfer.result * 4 : transfer.result;
    },[&](const uint8_t* data, size_t size) -> ssize_t {
        if (audioClient_ < 0) return -EAGAIN;
        ssize_t n = send(audioClient_,data,size,MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n > 0) audioStatus("playing");
        return n < 0 ? -errno : n;
    },[&] { pcm_prepare(capture); pcm_start(capture); });
    if (!ok) {
        if (audioClient_ >= 0) close(audioClient_);
        audioClient_ = -1; pcm_close(capture); pcm_ = nullptr; audioStatus("error");
    }
#else
    (void)authorize_;
#endif
}
void Services::files() {
    if (files_ < 0) {
        files_ = socket(AF_UNIX,SOCK_SEQPACKET | SOCK_NONBLOCK | SOCK_CLOEXEC,0);
        sockaddr_un address{}; address.sun_family = AF_UNIX;
        const char* name = "andr36oid-controller-files"; memcpy(address.sun_path + 1,name,strlen(name));
        if (files_ < 0 || bind(files_,reinterpret_cast<sockaddr*>(&address),
                offsetof(sockaddr_un,sun_path) + 1 + strlen(name)) < 0 || listen(files_,1) < 0) return;
    }
    if (fileClient_ < 0) {
        int fd = accept4(files_,nullptr,nullptr,SOCK_NONBLOCK | SOCK_CLOEXEC);
        if (fd >= 0) { if (authorize_(fd)) fileClient_ = fd; else close(fd); }
    }
    if (fileClient_ < 0) return;
    if (fileReply_.empty()) {
        uint8_t request[4096]; ssize_t n = recv(fileClient_,request,sizeof(request),MSG_DONTWAIT | MSG_TRUNC);
        if (n > 0 && n <= static_cast<ssize_t>(sizeof(request)))
            fileReply_ = textRequest(typeRoot_,Bytes(request,request + n));
        else if (n != -1 || (errno != EAGAIN && errno != EINTR)) { close(fileClient_); fileClient_ = -1; }
    }
    if (!fileReply_.empty()) {
        ssize_t n = send(fileClient_,fileReply_.data(),fileReply_.size(),MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n >= 0 || (errno != EAGAIN && errno != EINTR)) {
            close(fileClient_); fileClient_ = -1; fileReply_.clear();
        }
    }
}
bool Services::pump(int64_t time) {
    if ((functions_ & (Network | Rndis)) && !network(time)) return false;
    if (functions_ & Serial) serial();
    if (functions_ & Sound) audio();
    if ((functions_ & Keyboard) && !typeRoot_.empty()) files();
    return true;
}
}
