#ifndef QPOLL_HAL_DISCOVERY_H
#define QPOLL_HAL_DISCOVERY_H
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include "hal_name.h"

struct qpoll_map {uint64_t start,end,offset;char *path;};
/* Parse tokens directly: no libc-specific scanf conversion/count behaviour. */
static int qpoll_parse_map(char *line,struct qpoll_map *map) {
    char *cursor=line,*end;
    if(!isxdigit((unsigned char)*cursor))return 0;
    errno=0;map->start=strtoull(cursor,&end,16);if(errno || end==cursor || *end!='-')return 0;
    cursor=end+1;if(!isxdigit((unsigned char)*cursor))return 0;
    map->end=strtoull(cursor,&end,16);if(errno || end==cursor || !isspace((unsigned char)*end) || map->start>=map->end)return 0;
    cursor=end+strspn(end," \t");
    size_t permissions=strcspn(cursor," \t\r\n");if(permissions!=4)return 0;
    cursor+=permissions;cursor+=strspn(cursor," \t");
    if(!isxdigit((unsigned char)*cursor))return 0;
    map->offset=strtoull(cursor,&end,16);if(errno || end==cursor || !isspace((unsigned char)*end))return 0;
    cursor=end;
    for(int token=0;token<2;++token) {
        cursor+=strspn(cursor," \t");size_t size=strcspn(cursor," \t\r\n");if(!size)return 0;cursor+=size;
    }
    cursor+=strspn(cursor," \t");cursor[strcspn(cursor,"\r\n")]=0;
    if(*cursor!='/')return 0;
    map->path=cursor;return 1;
}
/* Module membership may use any segment; symbol relocation requires offset zero. */
static uint64_t qpoll_mapping_ex(FILE *input,const char *wanted,char *resolved,size_t capacity,int zero_offset) {
    char line[4096];
    const char *filename=strrchr(wanted,'/');filename=filename?filename+1:wanted;
    while(fgets(line,sizeof(line),input)) {
        struct qpoll_map map;
        if(!qpoll_parse_map(line,&map) || (zero_offset && map.offset))continue;
        char *path=map.path;
        const char *basename=strrchr(path,'/');
        if(!basename || strcmp(basename+1,filename))continue;
        if(resolved) {
            if(strlen(path)>=capacity)return 0;
            memcpy(resolved,path,strlen(path)+1);
        }
        return map.start;
    }
    return 0;
}
static uint64_t qpoll_mapping(FILE *input,const char *wanted,char *resolved,size_t capacity) {
    return qpoll_mapping_ex(input,wanted,resolved,capacity,1);
}

/* Count distinct matching paths, not ELF segments. More than one is ambiguous. */
static int qpoll_select_hal_mapping(FILE *input,const regex_t *pattern,char *resolved,size_t capacity,uint64_t *segment) {
    char line[4096];int found=0;
    while(fgets(line,sizeof(line),input)) {
        struct qpoll_map map;
        if(!qpoll_parse_map(line,&map) || !qpoll_hal_name_matches(pattern,map.path))continue;
        if(strlen(map.path)>=capacity)return -1;
        if(found && strcmp(resolved,map.path))return -1;
        if(!found){memcpy(resolved,map.path,strlen(map.path)+1);*segment=map.start;found=1;}
    }
    return found;
}

static void qpoll_log_maps(const char *path,int pid,FILE *diagnostic) {
    FILE *maps=fopen(path,"re");if(!maps)return;
    char line[4096];int count=0;
    while(count<16 && fgets(line,sizeof(line),maps)) {
        if(!strstr(line,"nfc")&&!strstr(line,"nxp")&&!strstr(line,"nqx")&&!strstr(line,"sn220"))continue;
        line[strcspn(line,"\r\n")]=0;
        fprintf(diagnostic,"HAL_MAP pid=%d raw=%s\n",pid,line);++count;
    }
    if(!count)fprintf(diagnostic,"HAL_MAP pid=%d no NFC-related paths in readable maps\n",pid);
    fclose(maps);
}

/* Process names are diagnostic only. The loaded HAL module is the selection criterion. */
static int qpoll_find_hal(const char *proc_root,FILE *diagnostic) {
    regex_t pattern;
    int regex_error=qpoll_hal_pattern_init(&pattern);
    if(regex_error){fprintf(diagnostic,"HAL_SCAN regex compilation failed (%d)\n",regex_error);return -1;}
    DIR *proc=opendir(proc_root);
    if(!proc){fprintf(diagnostic,"HAL_SCAN cannot open proc: %s\n",strerror(errno));regfree(&pattern);return -1;}
    int selected=-1,matches=0,conflicts=0,scanned=0,denied=0,unavailable=0,logged=0;
    struct dirent *entry;
    while((entry=readdir(proc))) {
        char *end;errno=0;long pid=strtol(entry->d_name,&end,10);
        if(errno || *end || pid<=0 || pid>INT_MAX)continue;
        char path[PATH_MAX],name[256]="<unreadable>",module[PATH_MAX];
        int n=snprintf(path,sizeof(path),"%s/%ld/cmdline",proc_root,pid);
        if(n<0 || (size_t)n>=sizeof(path))continue;
        FILE *cmdline=fopen(path,"re");
        if(cmdline) {
            size_t size=fread(name,1,sizeof(name)-1,cmdline);fclose(cmdline);
            name[size]=0;if(!size)snprintf(name,sizeof(name),"<empty>");
        }
        // Keep diagnostics on one line and exclude argv[1..].
        for(char *c=name;*c;++c)if((unsigned char)*c<32)*c='?';
        snprintf(path,sizeof(path),"%s/%ld/maps",proc_root,pid);
        FILE *maps=fopen(path,"re");uint64_t mapped=0;int error=0,selection=0;
        if(!maps)error=errno;
        else {
            ++scanned;errno=0;selection=qpoll_select_hal_mapping(maps,&pattern,module,sizeof(module),&mapped);
            if(ferror(maps))error=errno?errno:EIO;
            fclose(maps);
        }
        if(error==EACCES || error==EPERM)++denied;else if(error)++unavailable;
        if(selection<0) {
            ++conflicts;
            fprintf(diagnostic,"HAL_AMBIGUOUS pid=%ld name=%s multiple matching library paths; refusing injection\n",pid,name);
        } else if(selection>0 && !error) {
            ++matches;selected=(int)pid;
            fprintf(diagnostic,"HAL_MATCH pid=%ld name=%s module=%s segment=0x%llx\n",pid,name,module,(unsigned long long)mapped);
        } else if(logged<12 && (strstr(name,"nfc") || strstr(name,"Nfc") || strstr(name,"NFC"))) {
            fprintf(diagnostic,"HAL_CANDIDATE pid=%ld name=%s maps=%s; required-regex=%s\n",pid,name,
                    error?strerror(error):"readable, no matching HAL module",QPOLL_HAL_NAME_PATTERN);++logged;
            if(!error && (strstr(name,"/hw/") || strstr(name,"hardware.") || strstr(name,"vendor.")))qpoll_log_maps(path,(int)pid,diagnostic);
        }
    }
    closedir(proc);
    regfree(&pattern);
    fprintf(diagnostic,"HAL_SCAN scanned=%d denied=%d unavailable=%d matches=%d conflicts=%d\n",scanned,denied,unavailable,matches,conflicts);
    if(matches!=1 || conflicts) {
        fprintf(diagnostic,(matches>1 || conflicts)?"HAL selection ambiguous; refusing injection\n":"No readable process maps contain a matching HAL module\n");
        return -1;
    }
    return selected;
}
#endif
