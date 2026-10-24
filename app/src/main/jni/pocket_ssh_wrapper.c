#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

int main(int argc, char **argv) {
    const char *linker = "/system/bin/linker64";
    const char *ssh = getenv("POCKET_SSH_PATH");
    const char *config = getenv("POCKET_SSH_CONFIG");
    const char *known_hosts = getenv("POCKET_SSH_KNOWN_HOSTS");

    if (ssh == NULL || config == NULL || known_hosts == NULL) {
        fprintf(stderr, "Pocket SSH wrapper: missing SSH environment\n");
        return 127;
    }

    char **args = calloc((size_t) argc + 16, sizeof(char *));
    if (args == NULL) {
        perror("Pocket SSH wrapper: calloc");
        return 127;
    }

    int index = 0;
    args[index++] = (char *) linker;
    args[index++] = (char *) ssh;
    args[index++] = "-F";
    args[index++] = (char *) config;
    args[index++] = "-o";
    args[index++] = "BatchMode=yes";
    args[index++] = "-o";
    args[index++] = "IdentitiesOnly=yes";
    args[index++] = "-o";
    args[index++] = "StrictHostKeyChecking=accept-new";
    args[index++] = "-o";
    static char known_hosts_option[4096];
    snprintf(
        known_hosts_option,
        sizeof(known_hosts_option),
        "UserKnownHostsFile=%s",
        known_hosts
    );
    args[index++] = known_hosts_option;
    args[index++] = "-o";
    args[index++] = "ConnectTimeout=20";

    for (int i = 1; i < argc; i++) {
        args[index++] = argv[i];
    }
    args[index] = NULL;

    execv(linker, args);
    perror("Pocket SSH wrapper: execv");
    return 127;
}
