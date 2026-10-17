// SPDX-License-Identifier: GPL-2.0
/*
 * FNE offsets probe: compiles the 5.4.147 MagicUI 6.1 task_struct/cred/etc.
 * layouts under the device's MagicOS 8.0 config (/proc/config.gz of the
 * running 5.4.289) and embeds every offset in .rodata (probe_vals[] +
 * probe_names[]) so the host can extract them statically from the built .ko
 * WITHOUT loading it. init() also prints the table to kmsg for the
 * golden-window runtime confirmation.
 *
 * Compared against the gold standard: src_ghostlock/src/devices/fne/offsets.h
 * (disasm-verified against the running kernel image).
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/cred.h>
#include <linux/mm_types.h>
#include <linux/fs.h>
#include <linux/seccomp.h>

#define TV(field) ((u64)offsetof(struct task_struct, field))
#define CV(field) ((u64)offsetof(struct cred, field))
#define FV(field) ((u64)offsetof(struct file_operations, field))

#ifdef CONFIG_CGROUP_SCHED
# define V_SCHED_TASK_GROUP TV(sched_task_group)
#else
# define V_SCHED_TASK_GROUP 0ULL
#endif
#ifdef CONFIG_RT_MUTEXES
# define V_PI_WAITERS   TV(pi_waiters)
# define V_PI_TOP_TASK  TV(pi_top_task)
#else
# define V_PI_WAITERS   0ULL
# define V_PI_TOP_TASK  0ULL
#endif
#ifdef CONFIG_SECCOMP
# define V_TASK_SECCOMP TV(seccomp)
# define V_SECCOMP_MODE  ((u64)offsetof(struct seccomp, mode))
# define V_SECCOMP_FILTER ((u64)offsetof(struct seccomp, filter))
# define V_SIZEOF_SECCOMP ((u64)sizeof(struct seccomp))
#else
# define V_TASK_SECCOMP 0ULL
# define V_SECCOMP_MODE 0ULL
# define V_SECCOMP_FILTER 0ULL
# define V_SIZEOF_SECCOMP 0ULL
#endif
#ifdef CONFIG_THREAD_INFO_IN_TASK
# define V_THREAD_INFO TV(thread_info)
#else
# define V_THREAD_INFO 0ULL
#endif

enum {
	P_MAGIC, P_SIZEOF_TASK, P_SIZEOF_CRED, P_SIZEOF_SECCOMP, P_SIZEOF_FOPS,
	P_TASK_THREAD_INFO, P_TASK_STATE, P_TASK_STACK, P_TASK_USAGE,
	P_TASK_FLAGS, P_TASK_PRIO, P_TASK_NORMAL_PRIO, P_TASK_RT_PRIORITY,
	P_TASK_PID, P_TASK_TGID, P_TASK_REAL_PARENT, P_TASK_PARENT,
	P_TASK_GROUP_LEADER, P_TASK_REAL_CRED, P_TASK_CRED, P_TASK_COMM,
	P_TASK_MM, P_TASK_ACTIVE_MM, P_TASK_FILES, P_TASK_FS, P_TASK_NSPROXY,
	P_TASK_SIGNAL, P_TASK_SIGHAND, P_TASK_SECCOMP, P_TASK_ATOMIC_FLAGS,
	P_TASK_TASKS, P_TASK_PI_LOCK, P_TASK_PI_WAITERS, P_TASK_PI_TOP_TASK,
	P_TASK_SCHED_TASK_GROUP,
	P_TASK_ON_RQ, P_TASK_STATIC_PRIO, P_TASK_SCHED_CLASS, P_TASK_POLICY,
	P_TASK_PTRACE, P_TASK_PTRACED, P_TASK_PTRACE_ENTRY, P_TASK_THREAD_PID,
	P_TASK_PID_LINKS, P_TASK_THREAD_GROUP, P_TASK_THREAD_NODE,
	P_SIGNAL_THREAD_HEAD, P_SIGNAL_NR_THREADS, P_SIGNAL_SIGCNT,
	P_SIGNAL_LIVE,
	P_CRED_UID, P_CRED_SECUREBITS, P_CRED_CAP_INHERITABLE,
	P_CRED_CAP_PERMITTED, P_CRED_CAP_EFFECTIVE, P_CRED_CAP_BSET,
	P_CRED_CAP_AMBIENT, P_CRED_SECURITY,
	P_SECCOMP_MODE, P_SECCOMP_FILTER,
	P_MM_OWNER,
	P_FOPS_LLSEEK, P_FOPS_READ, P_FOPS_WRITE, P_FOPS_READ_ITER,
	P_FOPS_WRITE_ITER, P_FOPS_UNLOCKED_IOCTL, P_FOPS_COMPAT_IOCTL,
	P_FOPS_MMAP, P_FOPS_OPEN, P_FOPS_RELEASE, P_FOPS_SPLICE_READ,
	P_FOPS_SHOW_FDINFO,
	P_COUNT
};

static const u64 probe_vals[P_COUNT] = {
	[P_MAGIC] = 0x0000393832454E46ULL, /* "FNE289" */
	[P_SIZEOF_TASK] = sizeof(struct task_struct),
	[P_SIZEOF_CRED] = sizeof(struct cred),
	[P_SIZEOF_SECCOMP] = V_SIZEOF_SECCOMP,
	[P_SIZEOF_FOPS] = sizeof(struct file_operations),
	[P_TASK_THREAD_INFO] = V_THREAD_INFO,
	[P_TASK_STATE] = TV(state),
	[P_TASK_STACK] = TV(stack),
	[P_TASK_USAGE] = TV(usage),
	[P_TASK_FLAGS] = TV(flags),
	[P_TASK_PRIO] = TV(prio),
	[P_TASK_NORMAL_PRIO] = TV(normal_prio),
	[P_TASK_RT_PRIORITY] = TV(rt_priority),
	[P_TASK_PID] = TV(pid),
	[P_TASK_TGID] = TV(tgid),
	[P_TASK_REAL_PARENT] = TV(real_parent),
	[P_TASK_PARENT] = TV(parent),
	[P_TASK_GROUP_LEADER] = TV(group_leader),
	[P_TASK_REAL_CRED] = TV(real_cred),
	[P_TASK_CRED] = TV(cred),
	[P_TASK_COMM] = TV(comm),
	[P_TASK_MM] = TV(mm),
	[P_TASK_ACTIVE_MM] = TV(active_mm),
	[P_TASK_FILES] = TV(files),
	[P_TASK_FS] = TV(fs),
	[P_TASK_NSPROXY] = TV(nsproxy),
	[P_TASK_SIGNAL] = TV(signal),
	[P_TASK_SIGHAND] = TV(sighand),
	[P_TASK_SECCOMP] = V_TASK_SECCOMP,
	[P_TASK_ATOMIC_FLAGS] = TV(atomic_flags),
	[P_TASK_TASKS] = TV(tasks),
	[P_TASK_PI_LOCK] = TV(pi_lock),
	[P_TASK_PI_WAITERS] = V_PI_WAITERS,
	[P_TASK_PI_TOP_TASK] = V_PI_TOP_TASK,
	[P_TASK_SCHED_TASK_GROUP] = V_SCHED_TASK_GROUP,
	[P_TASK_ON_RQ] = TV(on_rq),
	[P_TASK_STATIC_PRIO] = TV(static_prio),
	[P_TASK_SCHED_CLASS] = TV(sched_class),
	[P_TASK_POLICY] = TV(policy),
	[P_TASK_PTRACE] = TV(ptrace),
	[P_TASK_PTRACED] = TV(ptraced),
	[P_TASK_PTRACE_ENTRY] = TV(ptrace_entry),
	[P_TASK_THREAD_PID] = TV(thread_pid),
	[P_TASK_PID_LINKS] = TV(pid_links),
	[P_TASK_THREAD_GROUP] = TV(thread_group),
	[P_TASK_THREAD_NODE] = TV(thread_node),
	[P_SIGNAL_THREAD_HEAD] =
		((u64)offsetof(struct signal_struct, thread_head)),
	[P_SIGNAL_NR_THREADS] =
		((u64)offsetof(struct signal_struct, nr_threads)),
	[P_SIGNAL_SIGCNT] =
		((u64)offsetof(struct signal_struct, sigcnt)),
	[P_SIGNAL_LIVE] = ((u64)offsetof(struct signal_struct, live)),
	[P_CRED_UID] = CV(uid),
	[P_CRED_SECUREBITS] = CV(securebits),
	[P_CRED_CAP_INHERITABLE] = CV(cap_inheritable),
	[P_CRED_CAP_PERMITTED] = CV(cap_permitted),
	[P_CRED_CAP_EFFECTIVE] = CV(cap_effective),
	[P_CRED_CAP_BSET] = CV(cap_bset),
	[P_CRED_CAP_AMBIENT] = CV(cap_ambient),
	[P_CRED_SECURITY] = CV(security),
	[P_SECCOMP_MODE] = V_SECCOMP_MODE,
	[P_SECCOMP_FILTER] = V_SECCOMP_FILTER,
	[P_MM_OWNER] = ((u64)offsetof(struct mm_struct, owner)),
	[P_FOPS_LLSEEK] = FV(llseek),
	[P_FOPS_READ] = FV(read),
	[P_FOPS_WRITE] = FV(write),
	[P_FOPS_READ_ITER] = FV(read_iter),
	[P_FOPS_WRITE_ITER] = FV(write_iter),
	[P_FOPS_UNLOCKED_IOCTL] = FV(unlocked_ioctl),
	[P_FOPS_COMPAT_IOCTL] = FV(compat_ioctl),
	[P_FOPS_MMAP] = FV(mmap),
	[P_FOPS_OPEN] = FV(open),
	[P_FOPS_RELEASE] = FV(release),
	[P_FOPS_SPLICE_READ] = FV(splice_read),
	[P_FOPS_SHOW_FDINFO] = FV(show_fdinfo),
};

static const char probe_names[] =
	"magic\0sizeof.task\0sizeof.cred\0sizeof.seccomp\0sizeof.fops\0"
	"task.thread_info\0task.state\0task.stack\0task.usage\0task.flags\0"
	"task.prio\0task.normal_prio\0task.rt_priority\0"
	"task.pid\0task.tgid\0task.real_parent\0task.parent\0"
	"task.group_leader\0task.real_cred\0task.cred\0task.comm\0"
	"task.mm\0task.active_mm\0task.files\0task.fs\0task.nsproxy\0"
	"task.signal\0task.sighand\0task.seccomp\0task.atomic_flags\0"
	"task.tasks\0task.pi_lock\0task.pi_waiters\0task.pi_top_task\0"
	"task.sched_task_group\0"
	"task.on_rq\0task.static_prio\0task.sched_class\0task.policy\0"
	"task.ptrace\0task.ptraced\0task.ptrace_entry\0task.thread_pid\0"
	"task.pid_links\0task.thread_group\0task.thread_node\0"
	"signal.thread_head\0signal.nr_threads\0signal.sigcnt\0signal.live\0"
	"cred.uid\0cred.securebits\0cred.cap_inheritable\0"
	"cred.cap_permitted\0cred.cap_effective\0cred.cap_bset\0"
	"cred.cap_ambient\0cred.security\0"
	"seccomp.mode\0seccomp.filter\0"
	"mm.owner\0"
	"fops.llseek\0fops.read\0fops.write\0fops.read_iter\0fops.write_iter\0"
	"fops.unlocked_ioctl\0fops.compat_ioctl\0fops.mmap\0fops.open\0"
	"fops.release\0fops.splice_read\0fops.show_fdinfo\0";

static int __init probe_init(void)
{
	int i;
	const char *n = probe_names;

	for (i = 0; i < P_COUNT; i++) {
		pr_info("fne_probe %s = 0x%llx\n", n,
			(unsigned long long)probe_vals[i]);
		n += strlen(n) + 1;
	}
	return 0;
}
module_init(probe_init);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("FNE 5.4.147-vs-289 struct offsets probe");
