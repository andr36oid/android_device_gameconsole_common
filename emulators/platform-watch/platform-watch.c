#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/inotify.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#ifndef PLATFORM_WATCH_HOST_TEST
#include <cutils/properties.h>
#endif

/* One native event loop; all database and mount-journal changes stay in their
 * existing locked workers. The seed is interpreted only by gameconsole-setup. */
struct folder { char *name, *extensions; struct folder *next; };
struct watch { int wd; char *path; struct watch *next; };
static struct folder *folders;
static struct watch *watches;
static char helper[PATH_MAX], mounts_file[PATH_MAX], media_root[PATH_MAX];
static char retry_file[PATH_MAX], session_mounts[PATH_MAX], broker[PATH_MAX];
static char root[PATH_MAX];
static struct stat root_stat;
static int notify_fd = -1;
static pid_t child;
static bool eviction_pending;
static int64_t due, next_poll, retry_at, fallback_at, policy_retry;
static int poll_ms = 15000, debounce_ms = 2000, retry_ms = 60000;
static volatile sig_atomic_t stopping;
#ifdef PLATFORM_WATCH_HOST_TEST
static const char *test_root = "";
#endif

static int64_t now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static void signal_event(int sig) { if (sig != SIGCHLD) stopping = 1; }
static bool join(char *out, size_t size, const char *a, const char *b) {
    return snprintf(out, size, "%s/%s", a, b) < (int)size;
}
static void path_init(char *out, const char *absolute) {
#ifdef PLATFORM_WATCH_HOST_TEST
    if (snprintf(out, PATH_MAX, "%s%s", test_root, absolute) >= PATH_MAX) exit(2);
#else
    if (snprintf(out, PATH_MAX, "%s", absolute) >= PATH_MAX) exit(2);
#endif
}
static void get_property(const char *name, char value[128], const char *fallback) {
#ifdef PLATFORM_WATCH_HOST_TEST
    char path[PATH_MAX];
    if (snprintf(path, sizeof(path), "%s/properties/%s", test_root, name) >= (int)sizeof(path)) exit(2);
    FILE *f = fopen(path, "re");
    if (f && fgets(value, 128, f)) value[strcspn(value, "\r\n")] = 0;
    else snprintf(value, 128, "%s", fallback);
    if (f) fclose(f);
#else
    char data[PROPERTY_VALUE_MAX];
    property_get(name, data, fallback);
    snprintf(value, 128, "%s", data);
#endif
}
static void fallback(void) {
    if (notify_fd >= 0) close(notify_fd);
    fprintf(stderr, "platform-watch: using original Go watcher\n");
    setenv("A36_PLATFORM_WATCH_LEGACY", "1", 1);
    execl(helper, helper, "watch-platforms", (char *)NULL);
    perror("platform-watch fallback");
    exit(1);
}
static void child_lifetime(pid_t parent) {
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    if (getppid() != parent) _exit(1);
}

/* Read a bounded config from one short Go process, with no shell and no daemon
 * left behind. Unsupported/new seed expressions fall back to the old engine. */
static bool load_config(void) {
    int pipefd[2];
    if (pipe2(pipefd, O_CLOEXEC)) return false;
    pid_t parent = getpid(), pid = fork();
    if (!pid) {
        child_lifetime(parent);
        close(pipefd[0]);
        if (dup2(pipefd[1], STDOUT_FILENO) < 0) _exit(1);
        close(pipefd[1]);
        execl(helper, helper, "watch-config", (char *)NULL);
        _exit(1);
    }
    close(pipefd[1]);
    if (pid < 0) { close(pipefd[0]); return false; }
    fcntl(pipefd[0], F_SETFL, O_NONBLOCK);
    char *data = malloc(256 * 1024);
    size_t used = 0;
    bool complete = false;
    int64_t deadline = now_ms() + 10000;
    while (data && !stopping && now_ms() < deadline && used < 256 * 1024 - 1) {
        struct pollfd p = { .fd = pipefd[0], .events = POLLIN };
        if (poll(&p, 1, 200) < 0 && errno != EINTR) break;
        ssize_t n = read(pipefd[0], data + used, 256 * 1024 - 1 - used);
        if (!n) { complete = true; break; }
        if (n > 0) used += (size_t)n;
        else if (errno != EAGAIN && errno != EINTR) break;
    }
    close(pipefd[0]);
    if (!complete) kill(pid, SIGKILL);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    if (!complete || !WIFEXITED(status) || WEXITSTATUS(status)) { free(data); return false; }
    data[used] = 0;
    char *save = NULL, *line = strtok_r(data, "\n", &save);
    if (!line || strcmp(line, "a36-platform-watch\t1")) { free(data); return false; }
    unsigned count = 0;
    while ((line = strtok_r(NULL, "\n", &save))) {
        char *tab = strchr(line, '\t');
        if (!tab || tab == line || !tab[1] || ++count > 1024) { free(data); return false; }
        *tab++ = 0;
        if (line[0] == '.' || strchr(line, '/') || strchr(line, '\r')) { free(data); return false; }
        for (char *p = tab; *p; p++)
            if (!(*p >= 'a' && *p <= 'z') && !isdigit((unsigned char)*p) && *p != '_' && *p != '|') { free(data); return false; }
        struct folder *f = calloc(1, sizeof(*f));
        if (!f) { free(data); return false; }
        f->name = strdup(line); f->extensions = strdup(tab);
        if (!f->name || !f->extensions) { free(f->name); free(f->extensions); free(f); free(data); return false; }
        f->next = folders; folders = f;
    }
    free(data);
    return true;
}
static struct folder *find_folder(const char *relative) {
    size_t n = strcspn(relative, "/");
    for (struct folder *f = folders; f; f = f->next)
        if (strlen(f->name) == n && !strncmp(f->name, relative, n)) return f;
    return NULL;
}
static bool hidden(const char *relative) {
    for (const char *p = relative; *p; p++)
        if (*p == '.' && (p == relative || p[-1] == '/')) return true;
    return false;
}
static bool game_name(const char *name, const struct folder *f) {
    if (!f || name[0] == '.') return false;
    size_t len = strlen(name);
    if (len >= 9 && !strcasecmp(name + len - 9, ".pure.zip")) return false;
    const char *ext = strrchr(name, '.');
    if (!ext || !*++ext) return false;
    /* Unicode case folding remains Go's decision: a conservative event invokes
     * the canonical scanner instead of dropping a potentially valid game. */
    for (const unsigned char *p = (const unsigned char *)ext; *p; p++) if (*p >= 128) return true;
    for (const char *p = f->extensions; *p;) {
        size_t n = strcspn(p, "|");
        if (strlen(ext) == n && !strncasecmp(p, ext, n)) return true;
        p += n; if (*p) p++;
    }
    return false;
}
static void clear_watches(void) {
    if (notify_fd >= 0) close(notify_fd);
    notify_fd = -1;
    while (watches) { struct watch *w = watches; watches = w->next; free(w->path); free(w); }
}
static bool add_watch(const char *path) {
    uint32_t mask = IN_CLOSE_WRITE | IN_MOVED_TO | IN_MOVED_FROM | IN_CREATE | IN_DELETE |
                    IN_DELETE_SELF | IN_MOVE_SELF | IN_UNMOUNT;
    int wd = inotify_add_watch(notify_fd, path, mask);
    if (wd < 0) return false;
    for (struct watch *w = watches; w; w = w->next)
        if (w->wd == wd) return true;
    struct watch *w = calloc(1, sizeof(*w));
    if (!w) return false;
    w->wd = wd; w->path = strdup(path);
    if (!w->path) { free(w); return false; }
    w->next = watches; watches = w;
    return true;
}
static bool add_tree(const char *path) {
    struct stat st;
    if (lstat(path, &st)) return errno == ENOENT;
    if (!S_ISDIR(st.st_mode)) return true;
    if (!add_watch(path)) return false;
    DIR *d = opendir(path);
    if (!d) return false;
    bool ok = true;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        if (e->d_type != DT_DIR && e->d_type != DT_UNKNOWN) continue;
        char nested[PATH_MAX];
        if (!join(nested, sizeof(nested), path, e->d_name)) { ok = false; break; }
        if (lstat(nested, &st)) { if (errno == ENOENT) continue; ok = false; break; }
        if (S_ISDIR(st.st_mode) && !add_tree(nested)) { ok = false; break; }
    }
    closedir(d);
    return ok;
}
static void schedule(int64_t now) { due = now + debounce_ms; }
static void find_root(char found[PATH_MAX], struct stat *info) {
    found[0] = 0;
    char id[128], ready[128], dev[PATH_MAX], dir[PATH_MAX], source[256];
    get_property("sys.andr36oid.easyroms.id", id, "");
    get_property("sys.andr36oid.easyroms.ready", ready, "");
    if (!*id && strcmp(ready, "1")) snprintf(id, sizeof(id), "public:179,7");
    if (!*id || snprintf(source, sizeof(source), "/dev/block/vold/%s", id) >= (int)sizeof(source)) return;
    FILE *f = fopen(mounts_file, "re");
    if (!f) return;
    char *line = NULL; size_t cap = 0;
    while (getline(&line, &cap, f) >= 0) {
        if (sscanf(line, "%4095s %4095s", dev, dir) != 2 || strcmp(dev, source)) continue;
        size_t n = strlen(media_root);
        if (!strncmp(dir, media_root, n) && dir[n] == '/' && dir[n+1] &&
            !stat(dir, info) && S_ISDIR(info->st_mode)) { snprintf(found, PATH_MAX, "%s", dir); break; }
    }
    free(line); fclose(f);
}
static void refresh(int64_t now) {
    struct stat st;
    char value[128], session[128];
    get_property("persist.andr36oid.subsystem.prestage", value, "");
    get_property("sys.andr36oid.subsystem.session", session, "");
    eviction_pending = strcmp(value, "1") && strcmp(session, "running") &&
                       !stat(session_mounts, &st) && st.st_size > 0;
    char found[PATH_MAX]; struct stat info = {0};
    find_root(found, &info);
    if (strcmp(root, found) || (*found && (info.st_dev != root_stat.st_dev || info.st_ino != root_stat.st_ino))) {
        clear_watches(); snprintf(root, sizeof(root), "%s", found); root_stat = info;
        retry_at = fallback_at = 0;
    }
    if (!*root) return;
    if (!access(retry_file, F_OK) && now >= policy_retry) { schedule(now); policy_retry = now + retry_ms; }
    if (notify_fd < 0 && now >= retry_at) {
#ifdef PLATFORM_WATCH_HOST_TEST
        notify_fd = getenv("A36_WATCH_TEST_INOTIFY_FAIL") ? -1 : inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
#else
        notify_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
#endif
        bool ok = notify_fd >= 0 && add_watch(root);
        for (struct folder *f = folders; f && ok; f = f->next) {
            char dir[PATH_MAX];
            ok = join(dir, sizeof(dir), root, f->name) && add_tree(dir);
        }
        if (ok) schedule(now);
        else { clear_watches(); retry_at = now + retry_ms; }
    }
    if (notify_fd < 0 && now >= fallback_at) { schedule(now); fallback_at = now + retry_ms; }
}
static void events(int64_t now) {
    char data[64 * 1024] __attribute__((aligned(8)));
    ssize_t n = read(notify_fd, data, sizeof(data));
    if (!n) { clear_watches(); next_poll = 0; schedule(now); return; }
    if (n < 0) {
        if (errno != EINTR && errno != EAGAIN) { clear_watches(); next_poll = 0; schedule(now); }
        return;
    }
    for (size_t off = 0; off + sizeof(struct inotify_event) <= (size_t)n;) {
        const struct inotify_event *e = (const void *)(data + off);
        off += sizeof(*e) + e->len;
        if (off > (size_t)n) { clear_watches(); next_poll = 0; schedule(now); return; }
        if (e->mask & IN_Q_OVERFLOW) { clear_watches(); next_poll = 0; schedule(now); return; }
        struct watch **slot = &watches;
        while (*slot && (*slot)->wd != e->wd) slot = &(*slot)->next;
        if (!*slot) continue;
        struct watch *w = *slot;
        if (e->mask & IN_IGNORED) { *slot = w->next; free(w->path); free(w); continue; }
        if (e->mask & (IN_DELETE_SELF | IN_MOVE_SELF | IN_UNMOUNT)) {
            clear_watches(); next_poll = 0; schedule(now); return;
        }
        char path[PATH_MAX];
        if (!join(path, sizeof(path), w->path, e->len ? e->name : "")) { schedule(now); continue; }
        const char *relative = path + strlen(root);
        if (*relative == '/') relative++;
        struct folder *f = find_folder(relative);
        if (!f || hidden(relative)) continue;
        if (e->mask & IN_ISDIR) {
            if ((e->mask & (IN_CREATE | IN_MOVED_TO)) && !add_tree(path)) {
                clear_watches(); next_poll = 0;
            }
            schedule(now);
            if (notify_fd < 0) return;
        } else if ((e->mask & (IN_CLOSE_WRITE | IN_MOVED_TO | IN_MOVED_FROM | IN_DELETE)) && game_name(e->name, f)) schedule(now);
    }
}
static void start_job(bool evict) {
    pid_t parent = getpid();
    child = fork();
    if (!child) {
        child_lifetime(parent);
        if (evict) {
#ifdef PLATFORM_WATCH_HOST_TEST
            execl("/bin/sh", "sh", broker, "unstage", (char *)NULL);
#else
            execl("/system/bin/sh", "sh", broker, "unstage", (char *)NULL);
#endif
        } else execl(helper, helper, "reconcile-platforms", (char *)NULL);
        _exit(1);
    }
    if (child < 0) { child = 0; if (!evict) schedule(now_ms()); }
}
int main(int argc, char **argv) {
#ifdef PLATFORM_WATCH_HOST_TEST
    if (argc != 2) return 2;
    test_root = argv[1];
    const char *speed = getenv("A36_WATCH_TEST_MS");
    if (speed) { int n = atoi(speed); if (n < 10) return 2; poll_ms = n; debounce_ms = n; retry_ms = n * 4; }
#else
    (void)argc; (void)argv;
#endif
    path_init(helper, "/system/bin/gameconsole-setup"); path_init(mounts_file, "/proc/mounts");
    path_init(media_root, "/mnt/media_rw"); path_init(retry_file, "/data/misc/gameconsole/daijishou-platforms.retry");
    path_init(session_mounts, "/data/local/tmp/portmaster/session.mounts"); path_init(broker, "/system/etc/andr36oid-subsystem.sh");
    struct sigaction sa = { .sa_handler = signal_event };
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL); sigaction(SIGINT, &sa, NULL); sigaction(SIGCHLD, &sa, NULL);
    char value[128];
    get_property("persist.andr36oid.ram.native_platform_watch", value, "1");
    if (!strcmp(value, "0") || !load_config()) { if (!stopping) fallback(); return 0; }
    while (!stopping) {
        int64_t now = now_ms();
        if (child) {
            int status;
            pid_t reaped = waitpid(child, &status, WNOHANG);
            if (reaped == child || (reaped < 0 && errno == ECHILD)) child = 0;
        }
        if (now >= next_poll) { refresh(now); next_poll = now + poll_ms; }
        if (!child && eviction_pending) { eviction_pending = false; start_job(true); }
        if (!child && due && now >= due) { due = 0; if (*root) start_job(false); }
        int64_t wake = next_poll;
        if (!child && due && due < wake) wake = due;
        int timeout = (int)(wake > now ? wake - now : 1);
        struct pollfd p = { .fd = notify_fd, .events = POLLIN };
        int count = poll(&p, notify_fd >= 0 ? 1 : 0, timeout);
        if (count > 0 && p.revents) events(now_ms());
    }
    if (child) { kill(child, SIGKILL); while (waitpid(child, NULL, 0) < 0 && errno == EINTR) {} }
    clear_watches();
    while (folders) { struct folder *f = folders; folders = f->next; free(f->name); free(f->extensions); free(f); }
    return 0;
}
