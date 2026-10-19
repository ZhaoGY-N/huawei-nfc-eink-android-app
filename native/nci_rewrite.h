#ifndef QPOLL_NCI_REWRITE_H
#define QPOLL_NCI_REWRITE_H
#include <stddef.h>
#include <stdint.h>

/* All offsets refer to complete NCI packets, including their three-byte header. */
static inline int qpoll_rewrite(uint8_t *packet, size_t size, int outgoing) {
    if (!packet || size < 3 || size != (size_t)packet[2] + 3) return 0;
    if (outgoing) {
        if (packet[0] != 0x21 || packet[1] != 3 || size < 4 ||
                size != 4u + 2u * packet[3]) return 0;
        int changed = 0;
        for (size_t i = 0; i < packet[3]; ++i) {
            size_t offset = 4 + 2 * i;
            if (packet[offset] == 0) { packet[offset] = 0x71; ++changed; }
        }
        return changed;
    }
    if (packet[0] != 0x61) return 0;
    size_t offset = packet[1] == 3 ? 5 : packet[1] == 5 ? 6 : size;
    if (offset >= size || packet[offset] != 0x71) return 0;
    packet[offset] = 0;
    return 1;
}
#endif
