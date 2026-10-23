#pragma once

#include "Controller.h"
#include <functional>
#include <deque>
#include <sys/types.h>

namespace controller {
class DhcpLease {
public:
    Bytes reply(const Bytes& request, int64_t seconds);
    bool active(int64_t seconds) const { return acknowledged_ && expires_ > seconds; }
private:
    std::array<uint8_t, 6> client_{};
    int64_t expires_ = 0;
    bool acknowledged_ = false;
};
// PCM is always stereo S16 at 48 kHz; discard old audio if the player falls behind.
class AudioBuffer {
public:
    static constexpr size_t kPeriod = 192 * 4, kLimit = kPeriod * 4;
    void append(const uint8_t* bytes, size_t size);
    const Bytes* next() const { return queue_.empty() ? nullptr : &queue_.front(); }
    void sent() { queue_.pop_front(); }
    void clear() { queue_.clear(); }
private:
    std::deque<Bytes> queue_;
};
class AudioBridge {
public:
    using Read = std::function<ssize_t(uint8_t*, size_t)>;
    using Write = std::function<ssize_t(const uint8_t*, size_t)>;
    bool pump(const Read& read, const Write& write, const std::function<void()>& recover);
    void clear() { buffer_.clear(); }
private:
    AudioBuffer buffer_;
};
class AudioPlayback {
public:
    bool pump(const AudioBridge::Read& read, const AudioBridge::Write& write, const std::function<void()>& recover);
    void clear() { queue_.clear(); pending_.clear(); }
private:
    AudioBuffer queue_;
    Bytes pending_;
};
class Services {
public:
    using Publish = std::function<void(const char*, const std::string&)>;
    using Authorize = std::function<bool(int)>;
    Services(unsigned functions, Publish publish, Authorize authorize, std::string typeRoot = "");
    ~Services();
    bool pump(int64_t time);
    int midiNode();
    bool ready() const {
        return (!(functions_ & (Network | Rndis)) || net_ >= 0) &&
                (!(functions_ & Serial) || serial_ >= 0) && (!(functions_ & Sound) || pcm_ != nullptr) && (!(functions_ & Soundboard) || playback_ != nullptr);
    }
private:
    void audio();
    bool network(int64_t time);
    void serial();
    void files();
    unsigned functions_;
    Publish publish_;
    Authorize authorize_;
    int files_ = -1, fileClient_ = -1;
    std::string typeRoot_;
    Bytes fileReply_;
    int net_ = -1, serial_ = -1, audioServer_ = -1, audioClient_ = -1;
    void* pcm_ = nullptr;
    void* playback_ = nullptr;
    AudioPlayback captureBridge_;
    DhcpLease lease_;
    AudioBridge audioBridge_;
    std::string netName_, serialText_, audioStatus_;
    void audioStatus(const char* value) {
        if (audioStatus_ != value) { audioStatus_ = value; publish_("audio",value); }
    }
    Bytes echo_;
    int64_t lastStatus_ = -1000;
};
}
