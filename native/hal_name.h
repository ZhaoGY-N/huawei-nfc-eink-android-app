#ifndef QPOLL_HAL_NAME_H
#define QPOLL_HAL_NAME_H
#include <regex.h>
#include <string.h>

/* POSIX ERE, anchored to the whole filename. Both discovery and installation use this. */
#define QPOLL_HAL_NAME_PATTERN "^nfc_nci([._-][[:alnum:]]+)*\\.so$"
static inline int qpoll_hal_pattern_init(regex_t *pattern) {
    return regcomp(pattern,QPOLL_HAL_NAME_PATTERN,REG_EXTENDED|REG_NOSUB);
}
static inline int qpoll_hal_name_matches(const regex_t *pattern,const char *path) {
    if(!path || !*path)return 0;
    const char *filename=strrchr(path,'/');filename=filename?filename+1:path;
    return regexec(pattern,filename,0,NULL,0)==0;
}
#endif
