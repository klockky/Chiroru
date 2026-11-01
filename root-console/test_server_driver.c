/* Host transport test: production main/SELinux startup is not invoked. */
#define main unused_production_main
#include "server.c"
#undef main
static int verified(void) { return 0; }
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    signal(SIGPIPE, SIG_IGN);
    return console_server_loop(atoi(argv[1]), verified);
}
