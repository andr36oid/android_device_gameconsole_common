/* Host fixture: exercise the production suffix filter without duplicating it. */
#define PLATFORM_WATCH_HOST_TEST
#define main platform_watch_main
#include "platform-watch.c"
#undef main

int main(void) {
    char *line = NULL; size_t n = 0;
    while (getline(&line, &n, stdin) >= 0) {
        line[strcspn(line, "\r\n")] = 0;
        char *tab = strchr(line, '\t');
        if (!tab) return 2;
        *tab++ = 0;
        struct folder f = { .name = "fixture", .extensions = line };
        puts(game_name(tab, &f) ? "1" : "0");
    }
    free(line);
    /* Explicit overflow/ignored-watch recovery uses the actual event parser. */
    int fds[2];
    if (pipe(fds)) return 2;
    struct inotify_event event = { .wd = -1, .mask = IN_Q_OVERFLOW };
    notify_fd = fds[0];
    if (write(fds[1], &event, sizeof(event)) != (ssize_t)sizeof(event)) return 2;
    events(now_ms());
    close(fds[1]);
    if (notify_fd != -1 || next_poll != 0 || !due) return 1;
    return 0;
}
