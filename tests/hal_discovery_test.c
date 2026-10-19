#define _GNU_SOURCE
#include <assert.h>
#include <sys/stat.h>
#include <unistd.h>
#include "../native/hal_discovery.h"
#define REFERENCE_MODULE "nfc_nci.nqx.default.hw.so"

static const char mapping[]="7a00000000-7a00010000 r--p 00000000 fd:01 123 /odm/lib64/nfc_nci.nqx.default.hw.so\n";
static void fixture(const char *root,int pid,const char *name,const char *maps) {
    char directory[512],path[1024];snprintf(directory,sizeof(directory),"%s/%d",root,pid);assert(!mkdir(directory,0700));
    if(name){snprintf(path,sizeof(path),"%s/cmdline",directory);FILE *f=fopen(path,"we");assert(f);assert(fwrite(name,1,strlen(name)+1,f)==strlen(name)+1);fclose(f);}
    if(maps){snprintf(path,sizeof(path),"%s/maps",directory);FILE *f=fopen(path,"we");assert(f);fputs(maps,f);fclose(f);}
}
static void erase(const char *root,int pid) {
    char path[1024];snprintf(path,sizeof(path),"%s/%d/cmdline",root,pid);unlink(path);
    snprintf(path,sizeof(path),"%s/%d/maps",root,pid);unlink(path);
    snprintf(path,sizeof(path),"%s/%d",root,pid);assert(!rmdir(path));
}
static FILE *contents(const char *value) {FILE *f=tmpfile();assert(f);fputs(value,f);rewind(f);return f;}
int main(void) {
    regex_t pattern;assert(!qpoll_hal_pattern_init(&pattern));
    const char *accepted[]={"nfc_nci.nqx.default.hw.so","/odm/lib64/nfc_nci_nxp_snxxx_v2.so",
        "/vendor/lib64/nfc_nci_nxp_snxxx_v3.so","nfc_nci-nxp-sn220-v12.so","nfc_nci.so"};
    const char *rejected[]={"prefix-nfc_nci.nqx.default.hw.so","libnfc_vendor_extn.so","libsn220u_fw.so",
        "nfc_nci_nxp_snxxx_v2.so.bak","nfc_nci_nxp_snxxx_v2.so (deleted)","nfc_nci_..so","nfc_nci_.so","nfc_nci.so\n",""};
    for(size_t i=0;i<sizeof(accepted)/sizeof(*accepted);++i)assert(qpoll_hal_name_matches(&pattern,accepted[i]));
    for(size_t i=0;i<sizeof(rejected)/sizeof(*rejected);++i)assert(!qpoll_hal_name_matches(&pattern,rejected[i]));
    assert(!qpoll_hal_name_matches(&pattern,NULL));
    uint64_t segment=0;char selection[512];
    FILE *segments=contents("7a00000000-7a00010000 r--p 0 fd:01 123 /odm/lib64/nfc_nci_nxp_snxxx_v2.so\n"
        "7a00011000-7a00020000 r-xp 1000 fd:01 123 /odm/lib64/nfc_nci_nxp_snxxx_v2.so\n");
    assert(qpoll_select_hal_mapping(segments,&pattern,selection,sizeof(selection),&segment)==1);fclose(segments);
    segments=contents("7a00000000-7a00010000 r--p 0 fd:01 123 /odm/lib64/nfc_nci_nxp_snxxx_v2.so\n"
        "7b00000000-7b00010000 r--p 0 fd:01 124 /odm/lib64/nfc_nci.nqx.default.hw.so\n");
    assert(qpoll_select_hal_mapping(segments,&pattern,selection,sizeof(selection),&segment)==-1);fclose(segments);
    regfree(&pattern);
    char resolved[512];FILE *f=contents(mapping);
    assert(qpoll_mapping(f,REFERENCE_MODULE,resolved,sizeof(resolved))==0x7a00000000ULL);
    assert(!strcmp(resolved,"/odm/lib64/nfc_nci.nqx.default.hw.so"));fclose(f);
    f=contents("7a00000000-7a00010000 r--p 00000000 fd:01 123 /odm/lib64/prefix-nfc_nci.nqx.default.hw.so\n");
    assert(!qpoll_mapping(f,REFERENCE_MODULE,NULL,0));fclose(f);
    f=contents("7a00000000-7a00010000 r--p 00001000 fd:01 123 /odm/lib64/nfc_nci.nqx.default.hw.so\n");
    assert(!qpoll_mapping(f,REFERENCE_MODULE,NULL,0));fclose(f);
    f=contents(mapping);assert(!qpoll_mapping(f,REFERENCE_MODULE,resolved,2));fclose(f);
    f=contents("malformed maps line\n");assert(!qpoll_mapping(f,REFERENCE_MODULE,NULL,0));fclose(f);
    f=contents("7a00001000-7a00020000 r-xp 00001000 fd:01 123   /odm/lib64/nfc_nci.nqx.default.hw.so\n");
    assert(qpoll_mapping_ex(f,REFERENCE_MODULE,NULL,0,0)==0x7a00001000ULL);fclose(f);
    char tabbed[]="7a00000000-7a00010000\tr--p\t00000000\tfd:01\t123\t/odm/lib64/nfc_nci.nqx.default.hw.so\n";
    struct qpoll_map parsed;assert(qpoll_parse_map(tabbed,&parsed));assert(parsed.offset==0 && parsed.start==0x7a00000000ULL);
    char overflow[]="fffffffffffffffffff-7a00010000 r--p 0 fd:01 1 /odm/lib64/nfc_nci.nqx.default.hw.so\n";
    assert(!qpoll_parse_map(overflow,&parsed));

    char root[]="build/hal-scan-XXXXXX";assert(mkdtemp(root));FILE *log=tmpfile();assert(log);
    // The spelling differs from test1's hard-coded nqnfc service, but the loaded HAL matches.
    fixture(root,101,"/vendor/bin/hw/android.hardware.nfc-service.nxp",mapping);
    fixture(root,102,"android.hardware.nqnfc-service.nxp","1000-2000 r--p 00000000 00:00 1 /vendor/lib64/other.so\n");
    fixture(root,103,"vendor.nfc-service",NULL);
    assert(qpoll_find_hal(root,log)==101);
    // Even a process name without NFC is valid if it uniquely maps the exact HAL module.
    erase(root,101);fixture(root,101,"vendor.custom.hardware-service",mapping);
    assert(qpoll_find_hal(root,log)==101);
    erase(root,101);fixture(root,101,NULL,mapping);assert(qpoll_find_hal(root,log)==101);
    fixture(root,104,"vendor.nfc-service-2",mapping);assert(qpoll_find_hal(root,log)==-1);
    erase(root,104);erase(root,101);assert(qpoll_find_hal(root,log)==-1);
    rewind(log);char output[8192]={0};assert(fread(output,1,sizeof(output)-1,log)>0);
    assert(strstr(output,"HAL_MATCH pid=101")&&strstr(output,"HAL_CANDIDATE pid=103")&&strstr(output,"HAL selection ambiguous"));
    erase(root,102);erase(root,103);
    fixture(root,105,"/odm/bin/hw/android.hardware.nqnfc-service.nxp",
            "7a00001000-7a00020000 r-xp 00001000 fd:01 123 /odm/lib64/nfc_nci.nqx.default.hw.so\n");
    assert(qpoll_find_hal(root,log)==105);erase(root,105);
    fixture(root,106,"/odm/bin/hw/android.hardware.nqnfc-service.nxp",
            "7a00001000-7a00020000 r-xp 00001000 fd:01 123 /vendor/lib64/libnfc_vendor_extn.so\n");
    assert(qpoll_find_hal(root,log)==-1);
    rewind(log);memset(output,0,sizeof(output));assert(fread(output,1,sizeof(output)-1,log)>0);
    assert(strstr(output,"HAL_MAP pid=106 raw=")&&strstr(output,"/vendor/lib64/libnfc_vendor_extn.so"));
    erase(root,106);
    fixture(root,107,"/odm/bin/hw/android.hardware.nqnfc-service.nxp",
        "7a00000000-7a00010000 r--p 0 fd:01 123 /odm/lib64/nfc_nci_nxp_snxxx_v2.so\n");
    assert(qpoll_find_hal(root,log)==107);
    fixture(root,108,"other-nfc-service",mapping);assert(qpoll_find_hal(root,log)==-1);erase(root,108);erase(root,107);
    fixture(root,109,"vendor.nfc-service",
        "7a00000000-7a00010000 r--p 0 fd:01 123 /odm/lib64/nfc_nci_nxp_snxxx_v2.so\n"
        "7b00000000-7b00010000 r--p 0 fd:01 124 /odm/lib64/nfc_nci.nqx.default.hw.so\n");
    assert(qpoll_find_hal(root,log)==-1);erase(root,109);
    assert(!rmdir(root));fclose(log);
    puts("HAL regex discovery: naming families, strict boundaries, segment deduplication, process/module ambiguity and diagnostics passed");
}
