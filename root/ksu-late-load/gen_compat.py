#!/usr/bin/env python3
"""Apply KernelSU 5.4 compat edits on top of the 3-KSU-patch state.
Run from ksu_fne/.build/ksu AFTER: git checkout -- . && apply 3 KSU patches && git add -A
Then: git diff > ../../fne-5.4-compat.patch && git reset -q && git checkout -- .
"""
import sys

def edit(path, old, new, count=1):
    s = open(path).read()
    if old not in s:
        sys.exit(f"anchor missing in {path}: {old[:60]!r}")
    open(path, 'w').write(s.replace(old, new, count))

# sucompat.c: 5.8+ headers/renames
edit('kernel/feature/sucompat.c', '#include <linux/pgtable.h>', '#include <asm/pgtable.h>')
edit('kernel/feature/sucompat.c', '#include "arch.h"',
     '#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 8, 0)\n#define strncpy_from_user_nofault strncpy_from_unsafe_user\n#endif\n\n#include "arch.h"')

# file_wrapper.c: security_inode_init_security_anon is 5.9+
edit('kernel/infra/file_wrapper.c',
     '''    inode->i_flags &= ~S_PRIVATE;
    error = security_inode_init_security_anon(inode, &qname, context_inode);
    if (error) {
        iput(inode);
        return ERR_PTR(error);
    }
''',
     '''    inode->i_flags &= ~S_PRIVATE;
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 9, 0)
    error = security_inode_init_security_anon(inode, &qname, context_inode);
    if (error) {
        iput(inode);
        return ERR_PTR(error);
    }
#else
    // 5.4: LSM hook absent; anon inode keeps default label.
    error = 0;
    (void)qname;
    (void)context_inode;
#endif
''')

# patch_memory.c: copy_to_kernel_nofault -> probe_kernel_write
edit('kernel/hook/arm64/patch_memory.c', '#include "asm-generic/fixmap.h"\n',
     '#include "asm-generic/fixmap.h"\n#include <linux/version.h>\n\n#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 8, 0)\n#define copy_to_kernel_nofault probe_kernel_write\n#endif\n')

# patch_memory.c: kimage_voffset fallback when the init_mm page-table walk
# fails (vendor pgd/mm_struct layout drift): for kernel-image VAs
# (e.g. sys_call_table in .rodata) phys = virt - kimage_voffset.
edit('kernel/hook/arm64/patch_memory.c', '#include "asm/cacheflush.h"\n',
     '#include "asm/cacheflush.h"\n#include "infra/symbol_resolver.h"\n', 1)
edit('kernel/hook/arm64/patch_memory.c', 'static int ksu_patch_text_nosync(void *dst, void *src, size_t len, int flags)\n',
     '''// kimage_voffset fallback: kernel-image VAs satisfy phys = virt - kimage_voffset.
// Cached once; kallsyms resolution is pure memory reads, stop_machine safe.
static unsigned long ksu_kimage_voffset(void)
{
    static unsigned long kvo;
    static bool tried;
    if (!tried) {
        unsigned long *sym = (unsigned long *)ksu_resolve_symbol_for_functable_hook("kimage_voffset");
        tried = true;
        kvo = sym ? *sym : 0;
    }
    return kvo;
}

static int ksu_patch_text_nosync(void *dst, void *src, size_t len, int flags)
''', 1)
edit('kernel/hook/arm64/patch_memory.c',
     '''    int phy_err;
    unsigned long phy = phys_from_virt(p, &phy_err);
    if (phy_err) {
        ret = phy_err;
        pr_err("failed to find phy addr for patch dst addr 0x%lx\\n", p);
        goto err;
    }
''',
     '''    int phy_err;
    unsigned long phy = phys_from_virt(p, &phy_err);
    if (phy_err) {
        /* init_mm walk failed (vendor pgd/mm_struct layout drift). For
         * kernel-image VAs fall back to the linear image mapping:
         * phys = virt - kimage_voffset, bounded by max RAM. */
        unsigned long kvo = ksu_kimage_voffset();
        if (kvo && p > kvo && p - kvo < 0x800000000UL) {
            phy = p - kvo;
            phy_err = 0;
            pr_info("phy walk failed for 0x%lx, kimage_voffset fallback: 0x%lx\\n", p, phy);
        }
    }
    if (phy_err) {
        ret = phy_err;
        pr_err("failed to find phy addr for patch dst addr 0x%lx\\n", p);
        goto err;
    }
''', 1)

# syscall_hook.c: fail-closed — verify the dispatcher slot was really
# patched. If not (patch failure), the sys_enter rewrite would redirect
# hooked syscalls of marked processes to the unpatched ni_syscall slot
# (-ENOSYS system-wide for uid-2000 shells / zygote forks).
edit('kernel/hook/arm64/syscall_hook.c',
     '''    ksu_dispatcher_nr = ni_slot;
    ksu_syscall_table_hook(ksu_dispatcher_nr, (syscall_fn_t)ksu_syscall_dispatcher, NULL);
    pr_info("dispatcher installed at slot %d\\n", ksu_dispatcher_nr);
''',
     '''    ksu_dispatcher_nr = ni_slot;
    ksu_syscall_table_hook(ksu_dispatcher_nr, (syscall_fn_t)ksu_syscall_dispatcher, NULL);
    if (READ_ONCE(ksu_syscall_table[ksu_dispatcher_nr]) != (syscall_fn_t)ksu_syscall_dispatcher) {
        /* Fail-closed: the slot still points at ni_syscall; the rewrite
         * must stay off or marked processes get -ENOSYS. Hooks inert. */
        pr_err("dispatcher patch FAILED at slot %d, syscall rewrite disabled\\n", ksu_dispatcher_nr);
        ksu_dispatcher_nr = -1;
        return;
    }
    pr_info("dispatcher installed at slot %d\\n", ksu_dispatcher_nr);
''', 1)

# syscall_hook_manager.c: fail-closed — with no dispatcher, do not register
# the sys_enter tracepoint / tp_marker kretprobes (marking without dispatch
# is what turned adb shells into ENOSYS). Track registration for exit.
edit('kernel/hook/syscall_hook_manager.c', 'static struct kretprobe *syscall_regfunc_rp = NULL;\n',
     'static bool sys_enter_tp_registered = false;\n\nstatic struct kretprobe *syscall_regfunc_rp = NULL;\n', 1)
edit('kernel/hook/syscall_hook_manager.c',
     '''void __init ksu_syscall_hook_manager_init(void)
{
    int ret;
    pr_info("hook_manager: ksu_hook_manager_init called\\n");
''',
     '''void __init ksu_syscall_hook_manager_init(void)
{
    int ret;
    pr_info("hook_manager: ksu_hook_manager_init called\\n");

    if (ksu_dispatcher_nr < 0) {
        /* Fail-closed: without a working dispatcher the sys_enter rewrite
         * would turn every hooked syscall of marked processes into
         * -ENOSYS. Keep syscall hooks inactive instead. */
        pr_err("hook_manager: dispatcher unavailable, syscall hooks inactive\\n");
        ksu_setuid_hook_init();
        ksu_sucompat_init();
        return;
    }
''', 1)
edit('kernel/hook/syscall_hook_manager.c',
     '''    if (ret) {
        pr_err("hook_manager: failed to register sys_enter tracepoint: %d\\n", ret);
    } else {
        pr_info("hook_manager: sys_enter tracepoint registered\\n");
    }
''',
     '''    if (ret) {
        pr_err("hook_manager: failed to register sys_enter tracepoint: %d\\n", ret);
    } else {
        sys_enter_tp_registered = true;
        pr_info("hook_manager: sys_enter tracepoint registered\\n");
    }
''', 1)
edit('kernel/hook/syscall_hook_manager.c',
     '''#ifdef CONFIG_HAVE_SYSCALL_TRACEPOINTS
    unregister_trace_sys_enter(ksu_sys_enter_handler, NULL);
    tracepoint_synchronize_unregister();
    pr_info("hook_manager: sys_enter tracepoint unregistered\\n");
#endif
''',
     '''#ifdef CONFIG_HAVE_SYSCALL_TRACEPOINTS
    if (sys_enter_tp_registered) {
        unregister_trace_sys_enter(ksu_sys_enter_handler, NULL);
        tracepoint_synchronize_unregister();
        pr_info("hook_manager: sys_enter tracepoint unregistered\\n");
    }
#endif
''', 1)

# seccomp_cache.c: per-syscall action cache is 5.17+
s = open('kernel/infra/seccomp_cache.c').read()
s = s.replace('#include "infra/seccomp_cache.h"\n',
     '''#include "infra/seccomp_cache.h"

/*
 * 5.4: kernel seccomp has no per-syscall action cache (added in 5.17),
 * so there is nothing to clear/allow. Keep no-op stubs for the callers.
 */
#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 17, 0)

void ksu_seccomp_clear_cache(struct seccomp_filter *filter, int nr)
{
}

void ksu_seccomp_allow_cache(struct seccomp_filter *filter, int nr)
{
}

#else /* >= 5.17 */
''', 1)
if '#else /* >= 5.17 */' not in s:
    sys.exit("seccomp_cache anchor")
open('kernel/infra/seccomp_cache.c', 'w').write(s.rstrip('\n') + '\n\n#endif /* < 5.17 */\n')

# pkg_observer.c: handle_inode_event hook is 5.10+
edit('kernel/manager/pkg_observer.c',
     '''static const struct fsnotify_ops ksu_ops = {
    .handle_inode_event = ksu_handle_inode_event,
};
''',
     '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 10, 0)
/* 5.4: no handle_inode_event hook; adapter to the old handle_event signature. */
static int ksu_handle_event(struct fsnotify_group *group, struct inode *inode, u32 mask, const void *data,
                            int data_type, const struct qstr *file_name, u32 cookie,
                            struct fsnotify_iter_info *iter_info)
{
    return ksu_handle_inode_event(NULL, mask, NULL, NULL, file_name, cookie);
}

static const struct fsnotify_ops ksu_ops = {
    .handle_event = ksu_handle_event,
};
#else
static const struct fsnotify_ops ksu_ops = {
    .handle_inode_event = ksu_handle_inode_event,
};
#endif
''')

# allowlist.c: TWA_* enum is 5.12+; put_task_struct lives in sched/task.h
edit('kernel/policy/allowlist.c', '#include "klog.h" // IWYU pragma: keep\n',
     '#include <linux/sched/task.h>\n#include "klog.h" // IWYU pragma: keep\n\n#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 14, 0)\n/* 5.4: task_work_add takes a bool "notify" instead of the TWA_* enum. */\n#define TWA_RESUME true\n#endif\n', 1)

# app_profile.c: seccomp.filter_count is 5.9+
edit('kernel/policy/app_profile.c',
     '''    current->seccomp.mode = 0;
    current->seccomp.filter = NULL;
    atomic_set(&current->seccomp.filter_count, 0);
''',
     '''    current->seccomp.mode = 0;
    current->seccomp.filter = NULL;
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 9, 0)
    atomic_set(&current->seccomp.filter_count, 0);
#endif
''')

# ksud_integration.c: nofault -> probe_* renames
edit('kernel/runtime/ksud_integration.c', '#include "arch.h"\n',
     '#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 8, 0)\n#define strncpy_from_user_nofault strncpy_from_unsafe_user\n#define copy_from_user_nofault probe_user_read\n#define copy_to_user_nofault probe_user_write\n#endif\n\n#include "arch.h"\n', 1)

# dispatch.c: task_session/task_pgrp/init_task/tasklist_lock need sched headers
edit('kernel/supercall/dispatch.c', '#include <linux/thread_info.h>\n',
     '#include <linux/thread_info.h>\n#include <linux/sched/signal.h>\n#include <linux/sched/task.h>\n', 1)

# supercall.c: TWA_RESUME shim
edit('kernel/supercall/supercall.c', '#include <linux/version.h>\n',
     '#include <linux/version.h>\n\n#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 14, 0)\n#define TWA_RESUME true\n#endif\n', 1)

# su_mount_ns.c: path_mount is GKI-5.10+; on 5.4 use ksys_mount with a __user "/"
edit('kernel/infra/su_mount_ns.c', '#include <linux/version.h>\n',
     '#include <linux/version.h>\n#include <linux/ptrace.h>\n#include <linux/uaccess.h>\n', 1)
edit('kernel/infra/su_mount_ns.c',
     '    int pm_ret = path_mount(NULL, &root_path, NULL, MS_PRIVATE | MS_REC, NULL);\n',
     '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 10, 0)
    // 5.4: no path_mount(); ksys_mount() re-resolves the path from a __user
    // dir_name. We run in the caller's user context, so copy "/" below its SP.
    const char root_dir[] = "/";
    char __user *root_user = (char __user *)current_user_stack_pointer() - sizeof(root_dir);
    int pm_ret;
    if (copy_to_user(root_user, root_dir, sizeof(root_dir)))
        pm_ret = -EFAULT;
    else
        pm_ret = ksys_mount(NULL, root_user, NULL, MS_PRIVATE | MS_REC, NULL);
#else
    int pm_ret = path_mount(NULL, &root_path, NULL, MS_PRIVATE | MS_REC, NULL);
#endif
''', 1)

# kernel_umount.c: path_umount is GKI-5.10+; on 5.4 use ksys_umount with a __user string
edit('kernel/feature/kernel_umount.c', '#include <linux/types.h>\n',
     '''#include <linux/types.h>
#include <linux/version.h>
#include <linux/string.h>
#include <linux/syscalls.h>
#include <linux/ptrace.h>
#include <linux/uaccess.h>
''', 1)
edit('kernel/feature/kernel_umount.c',
     '''extern int path_umount(struct path *path, int flags);

static void ksu_umount_mnt(const char *mnt, struct path *path, int flags)
{
    int err = path_umount(path, flags);
    if (err) {
        pr_info("umount %s failed: %d\\n", mnt, err);
    }
}
''',
     '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 10, 0)
/* 5.4: path_umount() does not exist; ksys_umount() re-resolves the path from
 * a __user string. We run in the caller's user context, so copy the mount
 * point below its user stack pointer. */
static void ksu_umount_mnt(const char *mnt, struct path *path, int flags)
{
    size_t len = strlen(mnt) + 1;
    char __user *user_mnt = (char __user *)current_user_stack_pointer() - len;
    int err;

    (void)path;
    if (copy_to_user(user_mnt, mnt, len))
        return;
    err = ksys_umount(user_mnt, flags);
    if (err) {
        pr_info("umount %s failed: %d\\n", mnt, err);
    }
}
#else
extern int path_umount(struct path *path, int flags);

static void ksu_umount_mnt(const char *mnt, struct path *path, int flags)
{
    int err = path_umount(path, flags);
    if (err) {
        pr_info("umount %s failed: %d\\n", mnt, err);
    }
}
#endif
''', 1)

# app_profile.c: seccomp_filter_release is 5.9+; 5.4 equivalent is put_seccomp_filter
edit('kernel/policy/app_profile.c', '    seccomp_filter_release(fake);\n',
     '''#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 9, 0)
    /* 5.4: seccomp_filter_release() is 5.9+; put_seccomp_filter() drops the
     * fake's copied filter reference (kernel/seccomp.c). */
    put_seccomp_filter(fake);
#else
    seccomp_filter_release(fake);
#endif
''', 1)

# rules.c: entire RCU policy-swap engine is 5.11+; stub entries on 5.4
s = open('kernel/selinux/rules.c').read()
s = '#include <linux/version.h>\n#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 11, 0)\n' + s
s = s.rstrip('\n') + '''

#else /* < 5.11: no struct selinux_policy / RCU policy swap; rules are
        * pre-injected via magiskpolicy --live before module load. */

#include <linux/types.h>
#include <linux/compiler_types.h>
#include <linux/errno.h>

int handle_sepolicy(void __user *user_data, u64 data_len)
{
    return -EOPNOTSUPP;
}

void apply_kernelsu_rules()
{
}

int ksu_install_backup_sepolicy(const char *path)
{
    return -EOPNOTSUPP;
}

#endif /* 5.11 */
'''
open('kernel/selinux/rules.c', 'w').write(s)

# sepolicy.c: add_filename_trans legacy layout on <5.9; dup/destroy_sepolicy on 5.11+
edit('kernel/selinux/sepolicy.c',
     '''static bool add_filename_trans(struct policydb *db, const char *s, const char *t, const char *c, const char *d,
                               const char *o)
{
''',
     '''static bool add_filename_trans(struct policydb *db, const char *s, const char *t, const char *c, const char *d,
                               const char *o)
{
#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 9, 0)
    /* 5.4 uses the legacy filename_trans layout; not supported. */
    return false;
#else
''', 1)
edit('kernel/selinux/sepolicy.c',
     '''free_trans:
    kfree(trans);
out:
    return false;
}
''',
     '''free_trans:
    kfree(trans);
out:
    return false;
#endif /* >= 5.9 */
}
''', 1)
edit('kernel/selinux/sepolicy.c', '// ======== sepolicy ========\n',
     '// ======== sepolicy ========\n\n#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 11, 0)\n', 1)
s = open('kernel/selinux/sepolicy.c').read()
open('kernel/selinux/sepolicy.c', 'w').write(s.rstrip('\n') + '\n\n#endif /* >= 5.11 */\n')

# selinux_hide.c: hide relies on 5.11+ policy pointer swap
s = open('kernel/feature/selinux_hide.c').read()
s = '#include <linux/version.h>\n#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 11, 0)\n' + s
s = s.rstrip('\n') + '''

#else /* < 5.11: selinux hide relies on the 5.11+ policy pointer swap. */

void ksu_selinux_hide_init()
{
}

void ksu_selinux_hide_exit()
{
}

void ksu_selinux_hide_drop_backup_if_unused()
{
}

void ksu_selinux_hide_handle_second_stage()
{
}

void ksu_selinux_hide_handle_post_fs_data()
{
}

#endif /* 5.11 */
'''
open('kernel/feature/selinux_hide.c', 'w').write(s)

# sulog/event.c: minmax.h is 5.6+; nofault rename
s = open('kernel/sulog/event.c').read()
s = s.replace('#include <linux/minmax.h>\n', '', 1)
old = '#include <linux/version.h>\n'
if old not in s:
    sys.exit("event.c version.h anchor")
s = s.replace(old, '''#include <linux/version.h>
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 6, 0)
#include <linux/minmax.h>
#else
#include <linux/kernel.h> /* 5.4: min/max live here */
#endif

#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 8, 0)
#define strncpy_from_user_nofault strncpy_from_unsafe_user
#endif
''', 1)
open('kernel/sulog/event.c', 'w').write(s)

print("all edits ok")
