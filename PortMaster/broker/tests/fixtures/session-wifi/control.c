#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/reboot.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static void require(bool ok, const char *detail) {
    if (ok) return;
    fprintf(stderr, "SESSION_WIFI_CONTROL_FAIL: %s (%s)\n", detail, strerror(errno));
    reboot(RB_POWER_OFF);
    _exit(1);
}

static long long millis(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return (long long)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

static void request(int server, const char *operation, const char *command,
                    const char *reply, const char *expected_output, int expected_exit) {
    int output[2];
    require(pipe(output) == 0, "create output pipe");
    long long start = millis();
    pid_t child = fork();
    require(child >= 0, "fork helper");
    if (!child) {
        close(output[0]);
        close(server);
        require(dup2(output[1], STDOUT_FILENO) == STDOUT_FILENO, "redirect helper stdout");
        close(output[1]);
        execl("/guest", "guest", "--session-wifi", operation, NULL);
        require(false, "exec packaged guest helper");
    }
    close(output[1]);
    struct pollfd control = {.fd = server, .events = POLLIN};
    require(poll(&control, 1, 2500) == 1, "wait for supplicant request");
    struct sockaddr_un client;
    socklen_t size = sizeof(client);
    char buffer[1024];
    ssize_t length = recvfrom(server, buffer, sizeof(buffer) - 1, 0,
                              (struct sockaddr *)&client, &size);
    require(length == (ssize_t)strlen(command) && !memcmp(buffer, command, length),
            "validate global IFNAME command");
    require(size > sizeof(client.sun_family) && !client.sun_path[0], "client is abstract");
    if (reply)
        require(sendto(server, reply, strlen(reply), 0, (struct sockaddr *)&client, size)
                == (ssize_t)strlen(reply), "reply to abstract address");
    struct pollfd response = {.fd = output[0], .events = POLLIN};
    require(poll(&response, 1, 2500) == 1, "wait for helper reply or exit");
    int status;
    require(waitpid(child, &status, 0) == child && WIFEXITED(status) &&
            WEXITSTATUS(status) == expected_exit, "helper exit status");
    length = read(output[0], buffer, sizeof(buffer) - 1);
    require(length >= 0, "read helper output");
    close(output[0]);
    buffer[length] = 0;
    require(!strcmp(buffer, expected_output), "validate filtered output");
    long long duration = millis() - start;
    if (!reply) require(duration >= 700 && duration < 2200, "bounded no-reply timeout");
    printf("control=%s code=%d elapsed_ms=%lld\n", operation, expected_exit, duration);
}

int main(void) {
    setbuf(stdout, NULL);
    require(mkdir("/dev/socket", 0755) == 0, "create private socket directory");
    int server = socket(AF_UNIX, SOCK_DGRAM, 0);
    require(server >= 0, "create supplicant socket");
    struct sockaddr_un addr = {.sun_family = AF_UNIX, .sun_path = "/dev/socket/wpa_wlan0"};
    require(bind(server, (struct sockaddr *)&addr, sizeof(addr)) == 0, "bind init-style socket");
    request(server, "status", "IFNAME=wlan0 STATUS",
            "wpa_state=COMPLETED\nid=5\nbssid=11:22:33:44:55:66\nssid=fixture\n",
            "wpa_state=COMPLETED\nid=5\nbssid=11:22:33:44:55:66\n", 0);
    request(server, "reassociate", "IFNAME=wlan0 REASSOCIATE", "OK\n", "", 0);
    request(server, "reassociate", "IFNAME=wlan0 REASSOCIATE", "FAIL\n", "", 1);
    request(server, "status", "IFNAME=wlan0 STATUS", NULL, "", 1);
    close(server);
    puts("SESSION_WIFI_CONTROL_PASS");
    reboot(RB_POWER_OFF);
    return 0;
}
