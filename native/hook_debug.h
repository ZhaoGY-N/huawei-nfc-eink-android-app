#ifndef QPOLL_HOOK_DEBUG_H
#define QPOLL_HOOK_DEBUG_H
#include <stdint.h>
#include <stddef.h>
#include "nci_rewrite.h"

#define QPOLL_DEBUG_ABI 1
enum qpoll_gate {QP_GATE_UNKNOWN,QP_GATE_ON,QP_GATE_STAT_ERROR,QP_GATE_UNSAFE,QP_GATE_CLOCK_ERROR,QP_GATE_EXPIRED};
struct qpoll_packet_sample {uint64_t sequence,size;uint8_t before[32],after[32];};
struct qpoll_debug_state {
    uint64_t abi,tx_internal,tx_unlocked,rx_calls,discover_internal,discover_unlocked;
    uint64_t rewrites_internal,rewrites_unlocked,rx_rewrites,discover_responses,gate_checks,gate_on;
    uint64_t gate_state,lease_uid,lease_mode;
    int64_t stat_result,clock_result,lease_age;
    uint64_t slots_internal,slots_unlocked,slots_receive,upgraded_slots;
    struct qpoll_packet_sample tx,rx,response;
};
#define QP_ADD(value) __atomic_fetch_add(&(value),1,__ATOMIC_RELAXED)
#define QP_SET(value,data) __atomic_store_n(&(value),(data),__ATOMIC_RELAXED)
static inline void qpoll_sample(struct qpoll_packet_sample *sample,const uint8_t *before,const uint8_t *after,size_t size) {
    uint64_t sequence=__atomic_load_n(&sample->sequence,__ATOMIC_RELAXED);
    if((sequence&1)||!__atomic_compare_exchange_n(&sample->sequence,&sequence,sequence+1,0,__ATOMIC_ACQUIRE,__ATOMIC_RELAXED))return;
    for(size_t i=0;i<32;++i){sample->before[i]=i<size?before[i]:0;sample->after[i]=i<size?after[i]:0;}
    sample->size=size<32?size:32;
    __atomic_store_n(&sample->sequence,sequence+2,__ATOMIC_RELEASE);
}
static inline int qpoll_is_discover(const uint8_t *packet,size_t size) {
    return packet && size>=3 && packet[0]==0x21 && packet[1]==3;
}
/* Capture only discovery/activation traffic, never APDU/PIN/image data. */
static inline int qpoll_debug_tx(struct qpoll_debug_state *debug,const uint8_t *packet,uint8_t *copy,size_t size,int unlocked,int gate) {
    if(!qpoll_is_discover(packet,size)||size>258)return 0;
    if(unlocked)QP_ADD(debug->discover_unlocked);else QP_ADD(debug->discover_internal);
    for(size_t i=0;i<size;++i)copy[i]=packet[i];
    int changed=gate?qpoll_rewrite(copy,size,1):0;
    if(changed){if(unlocked)QP_ADD(debug->rewrites_unlocked);else QP_ADD(debug->rewrites_internal);}
    qpoll_sample(&debug->tx,packet,copy,size);return changed;
}
static inline void qpoll_debug_rx(struct qpoll_debug_state *debug,uint8_t *packet,size_t size,int gate) {
    if(!packet||size<3)return;
    if(packet[0]==0x41&&packet[1]==3){QP_ADD(debug->discover_responses);qpoll_sample(&debug->response,packet,packet,size);return;}
    if(packet[0]!=0x61||(packet[1]!=3&&packet[1]!=5))return;
    uint8_t before[32];for(size_t i=0;i<32;++i)before[i]=i<size?packet[i]:0;
    if(gate&&qpoll_rewrite(packet,size,0))QP_ADD(debug->rx_rewrites);
    qpoll_sample(&debug->rx,before,packet,size<32?size:32);
}
#endif
