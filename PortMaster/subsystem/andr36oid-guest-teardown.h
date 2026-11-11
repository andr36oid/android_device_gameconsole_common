/* Host-side teardown. /proc/.../children is disabled on the shipped kernel.
 * Stop one generation at a time, then discover its children with one proc scan.
 * Wait for a whole generation together so sibling count does not add sleeps. */
struct teardown_process {
    pid_t pid, parent;
    unsigned long long start;
    char state, comm[64];
    size_t depth;
};

static bool teardown_stat(pid_t pid, struct teardown_process *process) {
    char path[64], data[1024];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t length = read(fd, data, sizeof(data) - 1);
    close(fd);
    if (length <= 0) return false;
    data[length] = 0;

    const char *begin = strchr(data, '(');
    const char *end = strrchr(data, ')');
    if (!begin || !end || end <= begin) return false;
    int threads;
    if (sscanf(end + 1, " %c %d"                  /* state and PPid (3–4) */
               " %*s %*s %*s %*s %*s"             /* 5–9 */
               " %*s %*s %*s %*s %*s"             /* 10–14 */
               " %*s %*s %*s %*s %*s"             /* 15–19 */
               " %d %*s %llu", &process->state,    /* threads and start (20, 22) */
               &process->parent, &threads, &process->start) != 4) return false;
    if (process->state == 'X' || (process->state == 'Z' && threads <= 1)) return false;
    process->pid = pid;
    size_t i = 0;
    for (const char *p = begin + 1; p < end && i + 1 < sizeof(process->comm); ++p) {
        unsigned char c = *p;
        process->comm[i++] = c > ' ' && c < 127 && c != '[' && c != ']' ? c : '_';
    }
    process->comm[i] = 0;
    return true;
}

static bool teardown_same(const struct teardown_process *process, struct teardown_process *current) {
    return teardown_stat(process->pid, current) && current->start == process->start;
}

static void teardown_print(const struct teardown_process *process) {
    printf(" [pid=%d comm=%s state=%c ppid=%d]", process->pid, process->comm,
           process->state, process->parent);
}

/* A stopped leader can still have threads completing the group stop. */
static bool teardown_stopped(pid_t pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *tasks = opendir(path);
    if (!tasks) return false;
    struct dirent *entry;
    bool stopped = true;
    while ((entry = readdir(tasks))) {
        pid_t tid = atoi(entry->d_name);
        struct teardown_process thread;
        if (tid > 0 && teardown_stat(tid, &thread) &&
            thread.state != 'T' && thread.state != 't' && thread.state != 'Z') {
            stopped = false;
            break;
        }
    }
    closedir(tasks);
    return stopped;
}

static bool teardown_wait(const struct teardown_process *nodes, size_t begin, size_t end,
                          bool freezing, long long deadline) {
    for (;;) {
        bool pending = false;
        for (size_t i = begin; i < end; ++i) {
            struct teardown_process current;
            if (teardown_same(&nodes[i], &current) &&
                (!freezing || !teardown_stopped(current.pid))) {
                pending = true;
                break;
            }
        }
        if (!pending) return true;
        if (now_ms() >= deadline) return false;
        usleep(10000);
    }
}

static int teardown_discover(struct teardown_process **nodes, size_t *count, size_t *capacity,
                             size_t begin, size_t end, long long deadline) {
    DIR *proc = opendir("/proc");
    if (!proc) return -1;
    struct dirent *entry;
    int result = 0;
    while ((entry = readdir(proc))) {
        if (now_ms() >= deadline) {
            errno = ETIMEDOUT;
            result = -1;
            break;
        }
        pid_t pid = atoi(entry->d_name);
        struct teardown_process child;
        if (pid <= 1 || !teardown_stat(pid, &child)) continue;
        for (size_t i = begin; i < end; ++i) {
            struct teardown_process parent;
            if (child.parent != (*nodes)[i].pid || !teardown_same(&(*nodes)[i], &parent))
                continue;
            if (*count == *capacity) {
                struct teardown_process *grown = realloc(*nodes, 2 * *capacity * sizeof(**nodes));
                if (!grown) {
                    result = -1;
                    goto done;
                }
                *nodes = grown;
                *capacity *= 2;
            }
            child.depth = (*nodes)[i].depth + 1;
            (*nodes)[(*count)++] = child;
            break;
        }
    }
done:
    closedir(proc);
    return result;
}

static int stop_worker_tree(const char *pid_arg, const char *start_arg, const char *boot_arg) {
    char boot[64], *end;
    FILE *file = fopen("/proc/sys/kernel/random/boot_id", "re");
    if (!file) { perror("worker boot ID"); return 1; }
    bool have_boot = fgets(boot, sizeof(boot), file) != NULL;
    fclose(file);
    if (!have_boot) { puts("cannot read worker boot ID"); return 1; }
    boot[strcspn(boot, "\n")] = 0;
    if (strcmp(boot_arg, boot)) return 0;
    long pid = strtol(pid_arg, &end, 10);
    if (*end || pid <= 1 || pid > INT_MAX || pid == getpid() || pid == getppid()) {
        puts("invalid worker PID");
        return 1;
    }
    unsigned long long start = strtoull(start_arg, &end, 10);
    if (*end || !*start_arg || *start_arg == '-') {
        puts("invalid worker start time");
        return 1;
    }
    struct teardown_process root = {0};
    if (!teardown_stat(pid, &root) || root.start != start) return 0;

    size_t capacity = 16, count = 1;
    struct teardown_process *nodes = malloc(capacity * sizeof(*nodes));
    if (!nodes) { perror("worker tree"); return 1; }
    nodes[0] = root;
    const char *phase = "freeze";
    long long deadline = now_ms() + 4000;
    for (size_t begin = 0, end = 1; begin < end; begin = end, end = count) {
        phase = "freeze";
        for (size_t i = begin; i < end; ++i) {
            struct teardown_process current;
            if (teardown_same(&nodes[i], &current)) kill(current.pid, SIGSTOP);
        }
        if (!teardown_wait(nodes, begin, end, true, deadline)) goto failed;
        phase = "discover";
        if (teardown_discover(&nodes, &count, &capacity, begin, end, deadline) < 0) goto failed;
    }
    phase = "reap";
    for (size_t end = count; end > 0;) {
        size_t begin = end - 1;
        while (begin && nodes[begin - 1].depth == nodes[end - 1].depth) --begin;
        for (size_t i = begin; i < end; ++i) {
            struct teardown_process current;
            if (teardown_same(&nodes[i], &current)) kill(current.pid, SIGKILL);
        }
        /* Keep earlier generations frozen if any child still owns resources.
         * Recovery can find them again under their original journaled parent. */
        if (!teardown_wait(nodes, begin, end, false, deadline)) goto failed;
        end = begin;
    }
    free(nodes);
    return 0;

failed:
    printf("phase=%s", phase);
    for (size_t i = 0; i < count; ++i) {
        struct teardown_process current;
        if (teardown_same(&nodes[i], &current)) teardown_print(&current);
    }
    putchar('\n');
    free(nodes);
    return 1;
}

/* The host worker tree and the guest root are separate ownership checks. The
 * latter also catches orphans reparented outside the journaled runner tree. */
static int stop_guest_root(const char *root, bool wait) {
    size_t length = strlen(root);
    if (length < 2 || root[0] != '/' || root[length - 1] == '/') {
        puts("invalid guest root");
        return 1;
    }
    DIR *proc = opendir("/proc");
    if (!proc) { perror("guest proc scan"); return 1; }
    long long deadline = now_ms() + 3000;
    for (;;) {
        bool alive = false, expired = wait && now_ms() >= deadline;
        struct dirent *entry;
        while ((entry = readdir(proc))) {
            pid_t pid = atoi(entry->d_name);
            if (pid <= 1 || pid == getpid()) continue;
            struct teardown_process process, current;
            if (!teardown_stat(pid, &process)) continue;
            char path[64], target[4096];
            snprintf(path, sizeof(path), "/proc/%d/root", pid);
            ssize_t n = readlink(path, target, sizeof(target) - 1);
            if (n < 0) continue;
            target[n] = 0;
            if (strncmp(target, root, length) || (target[length] && target[length] != '/'))
                continue;
            if (!teardown_same(&process, &current)) continue;
            alive = true;
            if (expired) teardown_print(&current);
            else kill(pid, SIGKILL);
        }
        if (!wait || !alive || expired) {
            closedir(proc);
            if (alive && expired) putchar('\n');
            return wait && alive;
        }
        usleep(50000);
        rewinddir(proc);
    }
}
