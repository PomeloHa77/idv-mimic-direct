/*
 * 进程内自读内存：process_vm_readv(getpid(), ...)。
 *
 * 为什么不用 /proc/self/mem：Android 10+ 起，SELinux 把 untrusted_app 对
 * proc 文件 /proc/<pid>/mem 的 open 直接拒掉（真机实测抛
 * java.io.FileNotFoundException: /proc/self/mem: open failed: EACCES），
 * 所以进程内读自己内存的「文件路径」走不通。
 *
 * process_vm_readv 走的是内核的
 *   process_vm_rw() -> mm_access() -> ptrace_may_access() -> __ptrace_may_access()
 * 而 __ptrace_may_access() 第一句就是：
 *   if (same_thread_group(task, current)) return 0;   // 不经过任何 LSM
 * 也就是说「读自己」在 LSM 之前就直接放行了，因此免 root 可用。
 *
 * 这里用 syscall(__NR_process_vm_readv) 而不是链接 libc 的 process_vm_readv：
 * bionic 从 API 23 才导出该符号，而我们 minSdk 21，直接走 syscall 更省事。
 *
 * 返回：>=0 实际读到的字节数；<0 表示 -errno（如 -EFAULT 未映射页）。
 */
#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

#ifndef __NR_process_vm_readv
#define __NR_process_vm_readv 270
#endif

JNIEXPORT jint JNICALL
Java_com_fj_direct_MemReader_readSelf(JNIEnv *env, jclass clazz,
                                      jlong addr, jbyteArray dst, jint off, jint len)
{
    if (len <= 0) {
        return 0;
    }
    jbyte *buf = (*env)->GetPrimitiveArrayCritical(env, dst, NULL);
    if (buf == NULL) {
        return -ENOMEM;
    }

    struct iovec local;
    struct iovec remote;
    local.iov_base = buf + off;
    local.iov_len = (size_t) len;
    remote.iov_base = (void *) (uintptr_t) addr;
    remote.iov_len = (size_t) len;

    long n = syscall(__NR_process_vm_readv, (long) getpid(),
                     &local, (long) 1, &remote, (long) 1, (long) 0);
    int err = errno;

    (*env)->ReleasePrimitiveArrayCritical(env, dst, buf, 0);

    if (n < 0) {
        return (jint) -err;
    }
    return (jint) n;
}
