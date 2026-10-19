#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../native/hook_debug.h"
#include "../native/owned_hook.h"
int main(void) {
    struct qpoll_debug_state debug={.abi=QPOLL_DEBUG_ABI};uint8_t copy[258];
    uint8_t discover[]={0x21,3,3,1,0,1};
    assert(!qpoll_debug_tx(&debug,discover,copy,sizeof(discover),0,0));
    assert(!memcmp(discover,copy,sizeof(discover))&&debug.discover_internal==1&&debug.rewrites_internal==0);
    assert(qpoll_debug_tx(&debug,discover,copy,sizeof(discover),1,1)==1);
    assert(discover[4]==0&&copy[4]==0x71&&debug.discover_unlocked==1&&debug.rewrites_unlocked==1);
    assert(debug.tx.before[4]==0&&debug.tx.after[4]==0x71&&!(debug.tx.sequence&1));
    // A lower layer seeing an already rewritten command must not rewrite it a second time.
    uint8_t again[258];assert(!qpoll_debug_tx(&debug,copy,again,sizeof(discover),1,1));
    assert(debug.rewrites_unlocked==1);
    uint64_t sequence=debug.tx.sequence;
    uint8_t apdu[]={0x00,0x20,0x00,0x01,0x05,0xaa};
    assert(!qpoll_debug_tx(&debug,apdu,copy,sizeof(apdu),0,1)&&debug.tx.sequence==sequence);
    uint8_t notification[]={0x61,3,4,1,4,0x71,0};
    qpoll_debug_rx(&debug,notification,sizeof(notification),0);assert(notification[5]==0x71&&!debug.rx_rewrites);
    qpoll_debug_rx(&debug,notification,sizeof(notification),1);assert(notification[5]==0&&debug.rx_rewrites==1);
    assert(debug.rx.before[5]==0x71&&debug.rx.after[5]==0);
    uint8_t response[]={0x41,3,1,0};qpoll_debug_rx(&debug,response,sizeof(response),0);
    assert(debug.discover_responses==1&&debug.response.before[3]==0);
    sequence=debug.rx.sequence;qpoll_debug_rx(&debug,apdu,sizeof(apdu),1);assert(debug.rx.sequence==sequence);
    // Busy snapshot writers are skipped, not waited on by the HAL callback.
    debug.tx.sequence=1;qpoll_sample(&debug.tx,discover,discover,sizeof(discover));assert(debug.tx.sequence==1);
    char owned[160]="/data/vendor/nfc/qpolllab/";
    size_t prefix=strlen(owned);memset(owned+prefix,'a',64);strcpy(owned+prefix+64,"-libqpollhook.so");
    assert(qpoll_owned_hook_path(owned));owned[prefix]='g';assert(!qpoll_owned_hook_path(owned));
    assert(!qpoll_owned_hook_path("/data/local/tmp/libqpollhook.so"));
    assert(!qpoll_owned_hook_path("/data/vendor/nfc/qpolllab/libqpollhook.so"));
    assert(!qpoll_owned_hook_path(NULL));
    puts("Hook diagnostics: both TX layers, gate-off passthrough, RX rewrite, controller response, APDU exclusion, nonblocking samples and owned-library paths passed");
}
