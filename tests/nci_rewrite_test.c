#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../native/nci_rewrite.h"
int main(void) {
    uint8_t discover[]={0x21,3,7,3,0,1,1,1,0x80,1};
    assert(qpoll_rewrite(discover,sizeof(discover),1)==1);
    assert(discover[4]==0x71 && discover[5]==1 && discover[6]==1 && discover[8]==0x80);
    assert(qpoll_rewrite(discover,sizeof(discover),1)==0);
    uint8_t notification[]={0x61,3,4,1,4,0x71,0};
    assert(qpoll_rewrite(notification,sizeof(notification),0)==1 && notification[5]==0);
    uint8_t activation[]={0x61,5,5,1,2,4,0x71,0xff};
    assert(qpoll_rewrite(activation,sizeof(activation),0)==1 && activation[6]==0);
    uint8_t short_packet[]={0x61,5,3,0,0,0x71};
    assert(!qpoll_rewrite(short_packet,sizeof(short_packet),0));
    uint8_t truncated[]={0x21,3,3,1,0,1};
    assert(!qpoll_rewrite(truncated,sizeof(truncated)-1,1));
    uint8_t malformed[]={0x21,3,3,2,0,1};
    assert(!qpoll_rewrite(malformed,sizeof(malformed),1));
    assert(!qpoll_rewrite(NULL,256,0));
    // Exhaust all payload lengths with guards, including deliberately malformed entry counts.
    for(int size=0;size<=258;++size)for(int count=0;count<256;++count) {
        uint8_t guarded[260];memset(guarded,0xa5,sizeof(guarded));uint8_t *packet=guarded+1;
        if(size>=1)packet[0]=0x21;
        if(size>=2)packet[1]=3;
        if(size>=3)packet[2]=(uint8_t)(size-3);
        if(size>=4)packet[3]=(uint8_t)count;
        qpoll_rewrite(packet,(size_t)size,1);
        assert(guarded[0]==0xa5 && guarded[size+1]==0xa5);
    }
    puts("NCI packet transforms and malformed-length guards passed");
}
