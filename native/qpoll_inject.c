#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "hal_discovery.h"
#include "hook_debug.h"

/* Narrow arm64 helper: load one bundled adapter, then require its install acknowledgement.
 * No shell commands, firmware writes, or arbitrary target PID argument. */
struct registers {uint64_t x[31],sp,pc,pstate;};
static volatile sig_atomic_t cancelled;
static void on_signal(int sig){(void)sig;cancelled=1;}
static long long milliseconds(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000LL+t.tv_nsec/1000000;}
static int registers(int pid,struct registers *regs,int write){
    struct iovec io={regs,sizeof(*regs)};
    return ptrace(write?PTRACE_SETREGSET:PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io)==0;
}
static int memory(int pid,uint64_t address,void *data,size_t size,int write){
    char path[64];snprintf(path,sizeof(path),"/proc/%d/mem",pid);
    int fd=open(path,(write?O_RDWR:O_RDONLY)|O_CLOEXEC);if(fd<0)return 0;
    ssize_t count=write?pwrite(fd,data,size,(off_t)address):pread(fd,data,size,(off_t)address);
    close(fd);return count==(ssize_t)size;
}
static int wait_stop(int pid,long long deadline,int *status){
    while(milliseconds()<deadline){
        pid_t result=waitpid(pid,status,__WALL|WNOHANG);
        if(result==pid)return WIFSTOPPED(*status);
        if(result<0&&errno!=EINTR)return 0;
        usleep(1000);
    }
    return 0;
}
static uint64_t base(int pid,const char *suffix,char *resolved,size_t length){
    char path[64];snprintf(path,sizeof(path),"/proc/%d/maps",pid);
    FILE *input=fopen(path,"re");if(!input)return 0;
    uint64_t result=qpoll_mapping(input,suffix,resolved,length);
    fclose(input);return result;
}
/* Resolve wrappers only when the target maps the same system library file. */
static uint64_t remote_symbol(int pid,void *symbol){
    Dl_info local;if(!dladdr(symbol,&local)||!local.dli_fbase)return 0;
    const char *filename=strrchr(local.dli_fname,'/');if(!filename)return 0;
    char target[1024];uint64_t mapped=base(pid,filename,target,sizeof(target));
    struct stat a,b;
    if(!mapped||stat(local.dli_fname,&a)||stat(target,&b)||a.st_dev!=b.st_dev||a.st_ino!=b.st_ino)return 0;
    return mapped+(uint64_t)symbol-(uint64_t)local.dli_fbase;
}
static int bounds(size_t size,uint64_t offset,uint64_t bytes){return offset<=size&&bytes<=size-offset;}
static uint64_t elf_symbol(const char *path,const char *wanted){
    int fd=open(path,O_RDONLY|O_CLOEXEC);if(fd<0)return 0;
    struct stat st;if(fstat(fd,&st)||st.st_size<(off_t)sizeof(Elf64_Ehdr)){close(fd);return 0;}
    size_t size=(size_t)st.st_size;
    unsigned char *data=mmap(NULL,size,PROT_READ,MAP_PRIVATE,fd,0);close(fd);if(data==MAP_FAILED)return 0;
    uint64_t result=0;const Elf64_Ehdr *header=(const Elf64_Ehdr *)data;
    if(memcmp(header->e_ident,ELFMAG,SELFMAG)||header->e_ident[EI_CLASS]!=ELFCLASS64||header->e_machine!=EM_AARCH64||
            header->e_shentsize!=sizeof(Elf64_Shdr)||!bounds(size,header->e_shoff,(uint64_t)header->e_shnum*sizeof(Elf64_Shdr)))goto done;
    const Elf64_Shdr *sections=(const Elf64_Shdr *)(data+header->e_shoff);
    for(unsigned i=0;i<header->e_shnum&&!result;++i){
        const Elf64_Shdr *section=&sections[i];if(section->sh_type!=SHT_DYNSYM||section->sh_link>=header->e_shnum||
                !bounds(size,section->sh_offset,section->sh_size))continue;
        const Elf64_Shdr *strings=&sections[section->sh_link];if(!bounds(size,strings->sh_offset,strings->sh_size))continue;
        const Elf64_Sym *symbols=(const Elf64_Sym *)(data+section->sh_offset);
        for(size_t j=0;j<section->sh_size/sizeof(*symbols);++j){
            if(symbols[j].st_name>=strings->sh_size)continue;
            const char *name=(const char *)(data+strings->sh_offset+symbols[j].st_name);
            if(!memchr(name,0,strings->sh_size-symbols[j].st_name))continue;
            if(wanted?!strcmp(name,wanted):(!strcmp(name,"__dl___loader_dlopen")||!strcmp(name,"__loader_dlopen"))){result=symbols[j].st_value;break;}
        }
    }
done:munmap(data,size);return result;
}
static void packet_status(const char *name,const struct qpoll_packet_sample *sample) {
    fprintf(stdout,"HOOK_PACKET %s size=%llu seq=%llu before=",name,(unsigned long long)sample->size,(unsigned long long)sample->sequence);
    size_t size=sample->size>32?32:(size_t)sample->size;
    for(size_t i=0;i<size;++i)fprintf(stdout,"%02X",sample->before[i]);
    fprintf(stdout," after=");for(size_t i=0;i<size;++i)fprintf(stdout,"%02X",sample->after[i]);fprintf(stdout,"\n");
}
/* Read exported counters with /proc/pid/mem. No ptrace, remote calls, loading or writes. */
static int status_only(int pid,const char *path) {
    uint64_t mapped=base(pid,path,NULL,0),symbol=elf_symbol(path,"qpoll_debug");
    if(!mapped||!symbol){fprintf(stderr,"HOOK_STATS library/debug symbol not mapped\n");return 9;}
    struct qpoll_debug_state debug,verify;
    int stable=0;
    for(int retry=0;retry<3;++retry) {
        if(!memory(pid,mapped+symbol,&debug,sizeof(debug),0)||!memory(pid,mapped+symbol,&verify,sizeof(verify),0))return 9;
        if(debug.abi!=QPOLL_DEBUG_ABI||verify.abi!=QPOLL_DEBUG_ABI)return 9;
        if(!(verify.tx.sequence&1)&&!(verify.rx.sequence&1)&&!(verify.response.sequence&1)&&
           debug.tx.sequence==verify.tx.sequence&&debug.rx.sequence==verify.rx.sequence&&debug.response.sequence==verify.response.sequence){debug=verify;stable=1;break;}
        usleep(1000);
    }
    fprintf(stdout,"HOOK_SLOTS internal=%llu unlocked=%llu receive=%llu upgraded=%llu\n",(unsigned long long)debug.slots_internal,
        (unsigned long long)debug.slots_unlocked,(unsigned long long)debug.slots_receive,(unsigned long long)debug.upgraded_slots);
    fprintf(stdout,"HOOK_STATS stable=%d tx_internal=%llu tx_unlocked=%llu rx=%llu discover_internal=%llu discover_unlocked=%llu rewrite_internal=%llu rewrite_unlocked=%llu rewrite_rx=%llu discover_rsp=%llu\n",stable,
        (unsigned long long)debug.tx_internal,(unsigned long long)debug.tx_unlocked,(unsigned long long)debug.rx_calls,
        (unsigned long long)debug.discover_internal,(unsigned long long)debug.discover_unlocked,
        (unsigned long long)debug.rewrites_internal,(unsigned long long)debug.rewrites_unlocked,(unsigned long long)debug.rx_rewrites,(unsigned long long)debug.discover_responses);
    static const char *states[]={"unknown","enabled","stat_error","unsafe_owner_or_mode","clock_error","expired"};
    fprintf(stdout,"HOOK_GATE state=%s checks=%llu enabled=%llu stat_rc=%lld clock_rc=%lld uid=%llu mode=%llo age=%lld\n",
        debug.gate_state<sizeof(states)/sizeof(*states)?states[debug.gate_state]:"invalid",
        (unsigned long long)debug.gate_checks,(unsigned long long)debug.gate_on,(long long)debug.stat_result,(long long)debug.clock_result,
        (unsigned long long)debug.lease_uid,(unsigned long long)debug.lease_mode,(long long)debug.lease_age);
    if(stable){packet_status("TX_DISCOVER",&debug.tx);packet_status("RX_NOTIFICATION",&debug.rx);packet_status("RX_DISCOVER_RSP",&debug.response);}
    return 0;
}

/* Caller is already ptrace-stopped. Restore registers and borrowed stack on every return. */
static int call(int pid,uint64_t function,const uint64_t arguments[6],uint64_t scratch_stack,uint64_t *result){
    struct registers saved,current;if(!function||cancelled||!registers(pid,&saved,0))return 0;
    unsigned char backup[8192];uint64_t borrowed=saved.sp-sizeof(backup);
    if(!scratch_stack&&!memory(pid,borrowed,backup,sizeof(backup),0))return 0;
    current=saved;for(int i=0;i<6;++i)current.x[i]=arguments[i];current.pc=function;current.x[30]=0;
    if(scratch_stack)current.sp=scratch_stack;
    if(!registers(pid,&current,1))return 0;
    int ok=0,stopped=1,status=0;
    if(ptrace(PTRACE_CONT,pid,0,0))goto restore;
    stopped=0;
    long long deadline=milliseconds()+2500;
    while(!cancelled&&milliseconds()<deadline){
        pid_t event=waitpid(pid,&status,__WALL|WNOHANG);
        if(event<0&&errno!=EINTR)break;
        if(!event){usleep(1000);continue;}
        if(!WIFSTOPPED(status))break;
        stopped=1;
        if(!registers(pid,&current,0))break;
        int sig=WSTOPSIG(status);
        if(sig==SIGSEGV&&current.pc==0){*result=current.x[0];ok=1;break;}
        if(sig==SIGSEGV||sig==SIGBUS||sig==SIGILL||sig==SIGABRT)break;
        if(ptrace(PTRACE_CONT,pid,0,sig==SIGTRAP?0:sig))break;
        stopped=0;
    }
    if(!stopped){
        if(ptrace(PTRACE_INTERRUPT,pid,0,0)==0)stopped=wait_stop(pid,milliseconds()+1000,&status);
        ok=0;
    }
restore:
    if(!stopped)return 0;
    if(!scratch_stack&&!memory(pid,borrowed,backup,sizeof(backup),1))ok=0;
    if(!registers(pid,&saved,1))ok=0;
    return ok;
}
int main(int argc,char **argv){
    int status_mode=argc==3&&!strcmp(argv[2],"--status");
    if((argc!=2&&!status_mode)||geteuid()!=0){fprintf(stderr,"Root helper requires one bundled library path [--status]\n");return 2;}
    char resolved[1024];
    static const char directory[]="/data/vendor/nfc/qpolllab/";
    if(!realpath(argv[1],resolved)||strncmp(resolved,directory,sizeof(directory)-1))return 2;
    struct stat asset;if(stat(resolved,&asset)||!S_ISREG(asset.st_mode)||asset.st_uid!=0||(asset.st_mode&022))return 2;
    int pid=qpoll_find_hal("/proc",stderr);if(pid<=0){fprintf(stderr,"Supported NXP HAL process not found; see HAL_SCAN / HAL_CANDIDATE above\n");return 3;}
    if(status_mode)return status_only(pid,resolved);
    char linker[1024];uint64_t link_base=base(pid,"/linker64",linker,sizeof(linker));
    uint64_t loader=link_base?elf_symbol(linker,NULL):0;
    uint64_t mmap_address=remote_symbol(pid,(void *)mmap),dlsym_address=remote_symbol(pid,(void *)dlsym);
    uint64_t munmap_address=remote_symbol(pid,(void *)munmap),caller=remote_symbol(pid,(void *)open);
    if(!loader||!mmap_address||!dlsym_address||!munmap_address||!caller){fprintf(stderr,"Incompatible linker/system libraries\n");return 4;}
    signal(SIGTERM,on_signal);signal(SIGINT,on_signal);signal(SIGHUP,on_signal);
    if(ptrace(PTRACE_SEIZE,pid,0,0)||ptrace(PTRACE_INTERRUPT,pid,0,0)){perror("ptrace");return 5;}
    int status,code=6;
    if(!wait_stop(pid,milliseconds()+1000,&status))goto detach;
    uint64_t arguments[6]={0,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,(uint64_t)-1,0},page=0,value=0;
    if(!call(pid,mmap_address,arguments,0,&page)||!page||(int64_t)page<0)goto detach;
    size_t length=strlen(resolved)+1;const char method[]="qpoll_install";
    if(!memory(pid,page,resolved,length,1)||!memory(pid,page+2048,(void *)method,sizeof(method),1))goto unmap;
    uint64_t stack=page+65536-16;
    arguments[0]=page;arguments[1]=RTLD_NOW|RTLD_LOCAL;arguments[2]=caller;
    if(!call(pid,link_base+loader,arguments,stack,&value)||!value){fprintf(stderr,"HAL rejected adapter loading\n");goto unmap;}
    arguments[0]=value;arguments[1]=page+2048;arguments[2]=0;
    if(!call(pid,dlsym_address,arguments,stack,&value)||!value)goto unmap;
    memset(arguments,0,sizeof(arguments));
    if(!call(pid,value,arguments,stack,&value)||(int64_t)value!=1){fprintf(stderr,"GOT install not acknowledged (%lld); restart NFC before retrying another build\n",(long long)value);goto unmap;}
    fprintf(stdout,"HAL adapter verified: both directions installed (pid=%d)\n",pid);code=0;
unmap:
    arguments[0]=page;arguments[1]=65536;
    if(!cancelled&&!call(pid,munmap_address,arguments,0,&value))code=7;
detach:
    if(ptrace(PTRACE_DETACH,pid,0,0)){perror("detach");code=8;}
    return code;
}
