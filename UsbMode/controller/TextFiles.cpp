#include "TextFiles.h"

#include <algorithm>
#include <cerrno>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace controller {
namespace {
bool name(const std::string& s) {
    if (s.size() < 5 || s.size() > 255 || s == "README.txt" || s.substr(s.size() - 4) != ".txt") return false;
    for (size_t i = 0; i < s.size();) {
        uint32_t point = static_cast<uint8_t>(s[i++]);
        if (point < 32 || point == 127 || point == '/') return false;
        if (point < 128) continue;
        unsigned count = point >= 0xf0 ? 3 : point >= 0xe0 ? 2 : point >= 0xc2 ? 1 : 0;
        if (!count || point > 0xf4 || i + count > s.size()) return false;
        point &= (1 << (6 - count)) - 1;
        for (unsigned n = 0; n < count; ++n) {
            const unsigned next = static_cast<uint8_t>(s[i++]);
            if ((next & 0xc0) != 0x80) return false;
            point = (point << 6) | (next & 63);
        }
        if (point < (count == 1 ? 0x80u : count == 2 ? 0x800u : 0x10000u)
                || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff)) return false;
    }
    return true;
}
Bytes error() { return {'E'}; }
}
Bytes textRequest(const std::string& root, const Bytes& request) {
    if (root.empty() || request.size() < 2 || request.size() > 4096 || request[1] != '\n' ||
            (request[0] != 'L' && request[0] != 'R')) return error();
    int parent = open(root.c_str(),O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent < 0) return error();
    if (request[0] == 'L' && mkdirat(parent,"type",0775) < 0 && errno != EEXIST) { close(parent); return error(); }
    int dir = openat(parent,"type",O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    close(parent); if (dir < 0) return error();
    Bytes result{'O'};
    if (request[0] == 'L') {
        if (request.size() > 2) {
            int readme = openat(dir,"README.txt",O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,0664);
            if (readme >= 0) { if (write(readme,request.data() + 2,request.size() - 2) < 0) result = error(); close(readme); }
        }
        DIR* list = fdopendir(dup(dir)); std::vector<std::string> names;
        if (list) {
            while (dirent* entry = readdir(list)) {
                std::string file(entry->d_name); struct stat st{};
                if (name(file) && fstatat(dir,file.c_str(),&st,AT_SYMLINK_NOFOLLOW) == 0 &&
                        S_ISREG(st.st_mode) && st.st_size <= 65536 && names.size() < 128) names.push_back(file);
            }
            closedir(list); std::sort(names.begin(),names.end());
            for (const auto& file : names) { result.push_back('\n'); result.insert(result.end(),file.begin(),file.end()); }
        } else result = error();
    } else {
        const std::string file(request.begin() + 2,request.end());
        int fd = name(file) ? openat(dir,file.c_str(),O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC) : -1;
        struct stat st{};
        if (fd < 0 || fstat(fd,&st) < 0 || !S_ISREG(st.st_mode) || st.st_size > 65536) result = error();
        else {
            uint8_t data[4096]; ssize_t n;
            while ((n = read(fd,data,sizeof(data))) > 0 && result.size() + n <= 65537)
                result.insert(result.end(),data,data + n);
            if (n != 0 || result.size() != static_cast<size_t>(st.st_size + 1)) result = error();
        }
        if (fd >= 0) close(fd);
    }
    close(dir); return result;
}
}
