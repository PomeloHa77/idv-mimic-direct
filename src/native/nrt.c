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
 * 为什么要 JNI_OnLoad + RegisterNatives，而不是常规的
 * Java_<包名>_<类名>_<方法名> 导出：
 *   那个符号名本身就把「哪个包、哪个类、哪个方法在读内存」写在了 .so 里，
 *   静态一看就自曝。改成动态注册后，libnrt.so 的动态符号表里只剩一个
 *   人人都有的 JNI_OnLoad，方法名/签名只存在于绑定时用到的字符串里。
 *
 * 为什么写进 ByteBuffer（direct buffer）而不是 byte[]：
 *   用 byte[] 就必须 GetPrimitiveArrayCritical —— 那个区段里 ART 不许 GC，
 *   而我们一次扫描要连着读 3.5 GB（每块 1 MB），等于把**整个游戏进程**的 GC
 *   按住十几秒：游戏那边每次分配都在等 GC 让路，表现就是持续掉帧。
 *   换成 direct buffer 后，native 直接往堆外内存写，完全不经 GC，
 *   游戏进程的 GC 一点都不会被我们拖住。
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

/* 绑定目标：z.a.d 的 static native int a(long, ByteBuffer, int, int)。
 * 类名不写成明文字面量 —— 否则 `strings libnrt.so` 一眼就能看到绑定到哪个类，
 * 改成按位异或过的字节数组，运行时现拼。 */
#define BIND_CLASS_LEN 5
#define MASK 0x5A
static const unsigned char BIND_CLASS_MASKED[BIND_CLASS_LEN] = {
    'z' ^ MASK, '/' ^ MASK, 'a' ^ MASK, '/' ^ MASK, 'd' ^ MASK,
};

/* 掩码放在 volatile 里：不然 clang 会把 xor 直接折叠成明文常量字符串塞进 .rodata，
 * 结果 `strings` 里照样能看到类名（实测过：换成常量会被折出 "z/a/"）。 */
static volatile unsigned char g_mask = MASK;

static void unmask_class(char *out)
{
    int i;
    for (i = 0; i < BIND_CLASS_LEN; i++) {
        out[i] = (char) (BIND_CLASS_MASKED[i] ^ g_mask);
    }
    out[BIND_CLASS_LEN] = '\0';
}

__attribute__((visibility("hidden")))
static jint rd(JNIEnv *env, jclass clazz, jlong addr, jobject dst, jint off, jint len)
{
    if (len <= 0) {
        return 0;
    }
    void *base = (*env)->GetDirectBufferAddress(env, dst);
    if (base == NULL) {
        return -EINVAL;
    }

    struct iovec local;
    struct iovec remote;
    local.iov_base = (char *) base + off;
    local.iov_len = (size_t) len;
    remote.iov_base = (void *) (uintptr_t) addr;
    remote.iov_len = (size_t) len;

    long n = syscall(__NR_process_vm_readv, (long) getpid(),
                     &local, (long) 1, &remote, (long) 1, (long) 0);
    int err = errno;

    if (n < 0) {
        return (jint) -err;
    }
    return (jint) n;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }

    char cls[BIND_CLASS_LEN + 1];
    unmask_class(cls);
    jclass c = (*env)->FindClass(env, cls);
    if (c == NULL) {
        return JNI_ERR;
    }

    JNINativeMethod m;
    m.name = (char *) "a";
    m.signature = (char *) "(JLjava/nio/ByteBuffer;II)I";
    m.fnPtr = (void *) rd;
    if ((*env)->RegisterNatives(env, c, &m, 1) != 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
