#ifndef QPOLL_OWNED_HOOK_H
#define QPOLL_OWNED_HOOK_H
#include <string.h>
/* Content-addressed app libraries only; metadata is checked separately at install time. */
static inline int qpoll_owned_hook_path(const char *path) {
    static const char prefix[]="/data/vendor/nfc/qpolllab/";
    if(!path||strncmp(path,prefix,sizeof(prefix)-1))return 0;
    const char *name=path+sizeof(prefix)-1;
    if(strlen(name)!=64+strlen("-libqpollhook.so"))return 0;
    for(int i=0;i<64;++i)if(!((name[i]>='0'&&name[i]<='9')||(name[i]>='a'&&name[i]<='f')))return 0;
    return !strcmp(name+64,"-libqpollhook.so");
}
#endif
