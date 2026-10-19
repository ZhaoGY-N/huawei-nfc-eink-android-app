#define _GNU_SOURCE
#include <elf.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include "nci_rewrite.h"
#include "hal_name.h"
#include "hook_debug.h"
#include "owned_hook.h"

/* Experimental SN220 HAL adapter. Install is explicit and acknowledged by the helper.
 * No constructor and no firmware/system-file modification. Requires a root-owned lease. */
#define LEASE "/data/vendor/nfc/qpolllab/enable"
#define RECEIVE "_Z27phNxpNciHal_process_ext_rspPhPt"
#define SEND "_Z26phNxpNciHal_write_internaltPKh"
#define SEND_UNLOCKED "_Z26phNxpNciHal_write_unlockedtPKhi"
typedef int (*receive_fn)(uint8_t *, uint16_t *);
typedef int (*send_fn)(unsigned short, const uint8_t *);
typedef int (*send_unlocked_fn)(unsigned short,const uint8_t *,int);
static receive_fn receive_original;
static send_fn send_original;
static send_unlocked_fn send_unlocked_original;
__attribute__((visibility("default"))) struct qpoll_debug_state qpoll_debug={.abi=QPOLL_DEBUG_ABI};

/* Avoid resolving libc PLT entries from a vendor HAL callback. */
static long syscall4(long nr, long a, long b, long c, long d) {
    register long x8 __asm__("x8")=nr;
    register long x0 __asm__("x0")=a;
    register long x1 __asm__("x1")=b;
    register long x2 __asm__("x2")=c;
    register long x3 __asm__("x3")=d;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8),"r"(x1),"r"(x2),"r"(x3) : "memory","cc");
    return x0;
}
static int enabled(void) {
    QP_ADD(qpoll_debug.gate_checks);
    struct stat info={0};
    long rc=syscall4(SYS_newfstatat,AT_FDCWD,(long)LEASE,(long)&info,AT_SYMLINK_NOFOLLOW);
    QP_SET(qpoll_debug.stat_result,rc);
    if(rc){QP_SET(qpoll_debug.gate_state,QP_GATE_STAT_ERROR);return 0;}
    QP_SET(qpoll_debug.lease_uid,info.st_uid);QP_SET(qpoll_debug.lease_mode,info.st_mode);
    if (!S_ISREG(info.st_mode) || info.st_uid != 0 || (info.st_mode & 022)) {QP_SET(qpoll_debug.gate_state,QP_GATE_UNSAFE);return 0;}
    struct timespec now={0};
    rc=syscall4(SYS_clock_gettime,CLOCK_REALTIME,(long)&now,0,0);QP_SET(qpoll_debug.clock_result,rc);
    if(rc){QP_SET(qpoll_debug.gate_state,QP_GATE_CLOCK_ERROR);return 0;}
    int64_t age=now.tv_sec-info.st_mtime;QP_SET(qpoll_debug.lease_age,age);
    if(age<0||age>360){QP_SET(qpoll_debug.gate_state,QP_GATE_EXPIRED);return 0;}
    QP_SET(qpoll_debug.gate_state,QP_GATE_ON);QP_ADD(qpoll_debug.gate_on);return 1;
}
static int receive_hook(uint8_t *packet, uint16_t *size) {
    QP_ADD(qpoll_debug.rx_calls);
    if(size){int gate=packet&&*size>=3&&packet[0]==0x61&&(packet[1]==3||packet[1]==5)?enabled():0;qpoll_debug_rx(&qpoll_debug,packet,*size,gate);}
    return receive_original(packet,size);
}
static int send_hook(unsigned short size, const uint8_t *packet) {
    QP_ADD(qpoll_debug.tx_internal);
    if (qpoll_is_discover(packet,size) && size<=258) {
        uint8_t copy[258];
        if(qpoll_debug_tx(&qpoll_debug,packet,copy,size,0,enabled()))return send_original(size,copy);
    }
    return send_original(size,packet);
}
static int send_unlocked_hook(unsigned short size,const uint8_t *packet,int mode) {
    QP_ADD(qpoll_debug.tx_unlocked);
    if(qpoll_is_discover(packet,size)&&size<=258){uint8_t copy[258];if(qpoll_debug_tx(&qpoll_debug,packet,copy,size,1,enabled()))return send_unlocked_original(size,copy,mode);}
    return send_unlocked_original(size,packet,mode);
}

struct module {uintptr_t base,end;const Elf64_Phdr *headers;size_t count;const regex_t *pattern;int matches;};
static int locate(struct dl_phdr_info *info,size_t bytes,void *context) {
    (void)bytes;
    struct module *m=context;
    if(!qpoll_hal_name_matches(m->pattern,info->dlpi_name))return 0;
    if(++m->matches>1)return 0;
    m->base=info->dlpi_addr;m->headers=info->dlpi_phdr;m->count=info->dlpi_phnum;
    for (size_t i=0;i<m->count;++i) if (m->headers[i].p_type==PT_LOAD) {
        uintptr_t end=m->base+m->headers[i].p_vaddr+m->headers[i].p_memsz;
        if (end>m->end) m->end=end;
    }
    return 0; // Traverse the entire module list to detect ambiguity before any GOT write.
}
static uintptr_t address(struct module *m,uintptr_t value) {
    return value>=m->base && value<m->end ? value : m->base+value;
}
struct slot {uintptr_t *pointer,original,replacement,previous;};
static int previous_app_hook(uintptr_t value) {
    Dl_info info;struct stat file;
    return dladdr((void *)value,&info)&&qpoll_owned_hook_path(info.dli_fname)&&!stat(info.dli_fname,&file)&&
        S_ISREG(file.st_mode)&&file.st_uid==0&&!(file.st_mode&022);
}
static int collect(struct module *m,const Elf64_Rela *relocations,size_t bytes,
        const Elf64_Sym *symbols,const char *names,struct slot *slots,int count) {
    if (!relocations) return count;
    for (size_t i=0;i<bytes/sizeof(*relocations);++i) {
        unsigned type=ELF64_R_TYPE(relocations[i].r_info),index=ELF64_R_SYM(relocations[i].r_info);
        if (!index || (type!=R_AARCH64_JUMP_SLOT && type!=R_AARCH64_GLOB_DAT)) continue;
        const Elf64_Sym *sym=&symbols[index];const char *name=names+sym->st_name;
        uintptr_t replacement=!strcmp(name,RECEIVE)?(uintptr_t)receive_hook:!strcmp(name,SEND)?(uintptr_t)send_hook:!strcmp(name,SEND_UNLOCKED)?(uintptr_t)send_unlocked_hook:0;
        if (!replacement) continue;
        if (!sym->st_value || sym->st_shndx==SHN_UNDEF || ELF64_ST_TYPE(sym->st_info)!=STT_FUNC) return -1;
        uintptr_t *pointer=(uintptr_t *)(m->base+relocations[i].r_offset);
        if ((uintptr_t)pointer<m->base || (uintptr_t)pointer+sizeof(*pointer)>m->end) return -1;
        int duplicate=0;for(int j=0;j<count;++j)duplicate|=slots[j].pointer==pointer;
        if (duplicate) continue;
        if (count==16) return -1;
        uintptr_t original=m->base+sym->st_value;
        // Upgrade only this app's root-owned, content-addressed resident library. Never chain it.
        if (*pointer!=original && *pointer!=replacement && !previous_app_hook(*pointer)) return -1;
        slots[count++]=(struct slot){pointer,original,replacement,*pointer};
    }
    return count;
}
static int protection(uintptr_t address) {
    FILE *maps=fopen("/proc/self/maps","re");if(!maps)return -1;
    char line[1024],flags[5];unsigned long start,end;int result=-1;
    while(fgets(line,sizeof(line),maps))if(sscanf(line,"%lx-%lx %4s",&start,&end,flags)==3 && address>=start && address<end) {
        result=(flags[0]=='r'?PROT_READ:0)|(flags[1]=='w'?PROT_WRITE:0)|(flags[2]=='x'?PROT_EXEC:0);break;
    }
    fclose(maps);return result;
}
static int store(struct slot *slot,uintptr_t value) {
    long size=sysconf(_SC_PAGESIZE);if(size<=0)return 0;
    uintptr_t page=(uintptr_t)slot->pointer & ~((uintptr_t)size-1);
    int prot=protection(page);if(prot<0 || (prot&PROT_EXEC))return 0;
    if(mprotect((void *)page,(size_t)size,prot|PROT_WRITE))return 0;
    __atomic_store_n(slot->pointer,value,__ATOMIC_RELEASE);
    return mprotect((void *)page,(size_t)size,prot)==0;
}

/* 1 = both directions installed, negative = no usable adapter; no lease is enabled here. */
__attribute__((visibility("default"))) int qpoll_install(void) {
    regex_t pattern;if(qpoll_hal_pattern_init(&pattern))return -8;
    struct module m={0};m.pattern=&pattern;dl_iterate_phdr(locate,&m);regfree(&pattern);
    if(m.matches!=1 || !m.headers)return -1;
    const Elf64_Dyn *dynamic=NULL;
    for(size_t i=0;i<m.count;++i)if(m.headers[i].p_type==PT_DYNAMIC)dynamic=(const Elf64_Dyn *)(m.base+m.headers[i].p_vaddr);
    if(!dynamic)return -2;
    const Elf64_Sym *symbols=NULL;const char *names=NULL;
    const Elf64_Rela *plt=NULL,*rela=NULL;size_t plt_bytes=0,rela_bytes=0;long format=DT_RELA;
    for(const Elf64_Dyn *d=dynamic;d->d_tag!=DT_NULL;++d)switch(d->d_tag) {
        case DT_SYMTAB:symbols=(const Elf64_Sym *)address(&m,d->d_un.d_ptr);break;
        case DT_STRTAB:names=(const char *)address(&m,d->d_un.d_ptr);break;
        case DT_JMPREL:plt=(const Elf64_Rela *)address(&m,d->d_un.d_ptr);break;
        case DT_RELA:rela=(const Elf64_Rela *)address(&m,d->d_un.d_ptr);break;
        case DT_PLTRELSZ:plt_bytes=d->d_un.d_val;break;
        case DT_RELASZ:rela_bytes=d->d_un.d_val;break;
        case DT_PLTREL:format=d->d_un.d_val;break;
    }
    if(!symbols||!names||format!=DT_RELA)return -3;
    struct slot slots[16];int count=collect(&m,plt,plt_bytes,symbols,names,slots,0);
    if(count>=0)count=collect(&m,rela,rela_bytes,symbols,names,slots,count);
    if(count<=0)return -4;
    int send=0,receive=0;
    for(int i=0;i<count;++i) {
        if(slots[i].replacement==(uintptr_t)send_hook){
            if(send_original && (uintptr_t)send_original!=slots[i].original)return -5;
            if(!send_original)send_original=(send_fn)slots[i].original;
            send=1;
        } else if(slots[i].replacement==(uintptr_t)send_unlocked_hook){
            if(send_unlocked_original && (uintptr_t)send_unlocked_original!=slots[i].original)return -5;
            if(!send_unlocked_original)send_unlocked_original=(send_unlocked_fn)slots[i].original;
            send=1;
        } else {
            if(receive_original && (uintptr_t)receive_original!=slots[i].original)return -5;
            if(!receive_original)receive_original=(receive_fn)slots[i].original;
            receive=1;
        }
    }
    if(!send||!receive)return -5;
    for(int i=0;i<count;++i)if(!store(&slots[i],slots[i].replacement)) {
        for(int j=0;j<=i;++j)store(&slots[j],slots[j].previous);
        return -6;
    }
    for(int i=0;i<count;++i)if(*slots[i].pointer!=slots[i].replacement){
        for(int j=0;j<count;++j)store(&slots[j],slots[j].previous);
        return -7;
    }
    uint64_t internal=0,unlocked=0,incoming=0,upgraded=0;
    for(int i=0;i<count;++i){
        if(slots[i].replacement==(uintptr_t)send_hook)++internal;
        else if(slots[i].replacement==(uintptr_t)send_unlocked_hook)++unlocked;
        else ++incoming;
        if(slots[i].previous!=slots[i].original&&slots[i].previous!=slots[i].replacement)++upgraded;
    }
    QP_SET(qpoll_debug.slots_internal,internal);QP_SET(qpoll_debug.slots_unlocked,unlocked);
    QP_SET(qpoll_debug.slots_receive,incoming);QP_SET(qpoll_debug.upgraded_slots,upgraded);
    return 1;
}
