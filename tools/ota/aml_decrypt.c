/* SPDX-License-Identifier: MIT
 * Decrypt boot-container bytes using the station's existing hardware KTE.
 * stdin -> AF_ALG -> stdout. No partition access or key programming.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/if_alg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define CHUNK 65536u

static int transfer(int fd, unsigned char *p, size_t n, int writing)
{
    while (n) {
        ssize_t r = writing ? write(fd, p, n) : read(fd, p, n);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

int main(int argc, char **argv)
{
    char *end;
    if (argc != 2 || !argv[1][0] || argv[1][0] == '-') return 2;
    errno = 0;
    unsigned long expected = strtoul(argv[1], &end, 0);
    if (errno || *end || !expected || expected % 16 || expected > 64u * 1024u * 1024u)
        return 2;

    unsigned char *in = malloc(CHUNK), *out = malloc(CHUNK);
    /* This is the driver's key-table-entry selector, NOT AES key bytes. */
    unsigned char selector[32] = {0x86};
    unsigned char iv[16] = {0};
    struct sockaddr_alg sa = {.salg_family = AF_ALG};
    int tfm = -1, op = -1, rc = 1;
    if (!in || !out) goto done;
    strcpy((char *)sa.salg_type, "skcipher");
    strcpy((char *)sa.salg_name, "cbc(aes-kl-aml)");
    tfm = socket(AF_ALG, SOCK_SEQPACKET, 0);
    if (tfm < 0 || bind(tfm, (struct sockaddr *)&sa, sizeof(sa)) < 0 ||
        setsockopt(tfm, SOL_ALG, ALG_SET_KEY, selector, sizeof(selector)) < 0) {
        perror("AF_ALG setup");
        goto done;
    }
    op = accept(tfm, NULL, 0);
    if (op < 0) { perror("AF_ALG accept"); goto done; }

    for (size_t total = 0; total < expected;) {
        size_t amount = expected - total;
        if (amount > CHUNK) amount = CHUNK;
        if (transfer(STDIN_FILENO, in, amount, 0)) {
            fprintf(stderr, "Short ciphertext input\n");
            goto done;
        }
        struct iovec iov = {.iov_base = in, .iov_len = amount};
        _Alignas(struct cmsghdr) char control[CMSG_SPACE(sizeof(uint32_t)) +
            CMSG_SPACE(sizeof(struct af_alg_iv) + sizeof(iv))];
        struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1};
        if (!total) {
            memset(control, 0, sizeof(control));
            msg.msg_control = control;
            msg.msg_controllen = sizeof(control);
            struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
            c->cmsg_level = SOL_ALG;
            c->cmsg_type = ALG_SET_OP;
            c->cmsg_len = CMSG_LEN(sizeof(uint32_t));
            *(uint32_t *)CMSG_DATA(c) = ALG_OP_DECRYPT;
            c = CMSG_NXTHDR(&msg, c);
            c->cmsg_level = SOL_ALG;
            c->cmsg_type = ALG_SET_IV;
            c->cmsg_len = CMSG_LEN(sizeof(struct af_alg_iv) + sizeof(iv));
            struct af_alg_iv *aiv = (struct af_alg_iv *)CMSG_DATA(c);
            aiv->ivlen = sizeof(iv);
            memcpy(aiv->iv, iv, sizeof(iv));
        }
        /* Drain each chunk before sending the next: filling AF_ALG's send
         * buffer with the whole image first can deadlock. MSG_MORE retains
         * CBC state until this bounded stream is closed. */
        ssize_t sent;
        do { sent = sendmsg(op, &msg, MSG_MORE); } while (sent < 0 && errno == EINTR);
        if (sent < 0 || (size_t)sent != amount || transfer(op, out, amount, 0)) {
            fprintf(stderr, "AF_ALG decrypt failed at byte %zu: %s\n", total, strerror(errno));
            goto done;
        }
        if (transfer(STDOUT_FILENO, out, amount, 1)) { perror("stdout"); goto done; }
        total += amount;
    }
    rc = 0;
done:
    if (op >= 0) close(op);
    if (tfm >= 0) close(tfm);
    free(in);
    free(out);
    return rc;
}
