/*
 * Functions PRoot and Termux's static libtalloc expect from Android 7.0's libc, for the older
 * Android versions droid_codology supports (built at API 16/21). Linked into libproot.so only.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/types.h>

struct ifaddrs;

/* getifaddrs/freeifaddrs are in bionic since API 24: use them there, else report "not supported"
 * (PRoot needs them only to fake a netlink answer). */
int getifaddrs(struct ifaddrs **out) {
    int (*real)(struct ifaddrs **) = (int (*)(struct ifaddrs **)) dlsym(RTLD_NEXT, "getifaddrs");
    if (real) return real(out);
    if (out) *out = NULL;
    errno = ENOSYS;
    return -1;
}

void freeifaddrs(struct ifaddrs *ifa) {
    void (*real)(struct ifaddrs *) = (void (*)(struct ifaddrs *)) dlsym(RTLD_NEXT, "freeifaddrs");
    if (real && ifa) real(ifa);
}

/* Termux builds talloc for API 24, where stderr is a variable; older headers make it a macro. */
#undef stderr
FILE *stderr;

__attribute__((constructor)) static void dc_open_stderr(void) {
    if (!stderr) stderr = fdopen(2, "w");
}

#if __ANDROID_API__ < 21
/* Android 4.x (32-bit builds at API 16): what PRoot, libtalloc and libandroid-shmem need from
 * newer bionic. The kernel has had these calls for long; the C library just lacked wrappers. */
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    return (ssize_t) syscall(__NR_readlinkat, dirfd, path, buf, size);
}

int symlinkat(const char *target, int newdirfd, const char *linkpath) {
    return (int) syscall(__NR_symlinkat, target, newdirfd, linkpath);
}

/* The old headers make these two static inline; Termux's libraries call real ones. Different C
 * names, the real symbol names through asm labels. */
int dc_getpagesize(void) __asm__("getpagesize");
int dc_getpagesize(void) {
    return (int) sysconf(_SC_PAGESIZE);
}

int dc_rand(void) __asm__("rand");
int dc_rand(void) {
    return (int) (lrand48() & 0x7fffffff);
}

int __vsnprintf_chk(char *dest, size_t supplied_size, int flags, size_t dest_len_from_compiler,
                    const char *format, va_list va) {
    (void) flags;
    (void) dest_len_from_compiler;
    return vsnprintf(dest, supplied_size, format, va);
}

/* prlimit64 (API 21): straight to the kernel. */
struct rlimit64;
int prlimit64(pid_t pid, int resource, const struct rlimit64 *new_limit, struct rlimit64 *old_limit) {
    return (int) syscall(__NR_prlimit64, pid, resource, new_limit, old_limit);
}

#include <sys/vfs.h>
/* statfs64 (API 19 on 32-bit): the kernel call takes the struct size. */
int statfs64(const char *path, struct statfs64 *buf) {
    return (int) syscall(__NR_statfs64, path, sizeof(struct statfs64), buf);
}

#if __ANDROID_API__ < 18
/* getline (API 18), on top of getc. */
ssize_t getline(char **line, size_t *cap, FILE *f) {
    if (!line || !cap || !f) {
        errno = EINVAL;
        return -1;
    }
    size_t n = 0;
    int c;
    if (!*line || *cap == 0) {
        *cap = 128;
        *line = (char *) malloc(*cap);
        if (!*line) return -1;
    }
    while ((c = getc(f)) != EOF) {
        if (n + 2 > *cap) {
            size_t bigger = *cap * 2;
            char *p = (char *) realloc(*line, bigger);
            if (!p) return -1;
            *line = p;
            *cap = bigger;
        }
        (*line)[n++] = (char) c;
        if (c == '\n') break;
    }
    if (n == 0) return -1;
    (*line)[n] = '\0';
    return (ssize_t) n;
}
#endif

#if __ANDROID_API__ < 18
/* getauxval (API 18): read the auxiliary vector the kernel gave this process. */
unsigned long getauxval(unsigned long type) {
    unsigned long pair[2];
    unsigned long result = 0;
    int fd = open("/proc/self/auxv", O_RDONLY);
    if (fd < 0) return 0;
    while (read(fd, pair, sizeof(pair)) == (ssize_t) sizeof(pair)) {
        if (pair[0] == 0) break;
        if (pair[0] == type) {
            result = pair[1];
            break;
        }
    }
    close(fd);
    return result;
}
#endif
#endif

/* From Samba's lib/replace (Termux's libtalloc.a leaves it to libreplace): a memset the compiler
 * may not drop. */
void *rep_memset_explicit(void *block, int c, size_t size) {
    volatile unsigned char *p = (volatile unsigned char *) block;
    while (size--) *p++ = (unsigned char) c;
    return block;
}
