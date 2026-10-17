/* OnePlus 15 (PLK110 / CPH2745 / CPH2747 / CPH2749) — SM8850 / canoe
 * (Snapdragon 8 Elite Gen 5). NOT SM8845; that is the Ace 6T / 15R.
 * Symbol offsets verified 16/16 against a live CPH2749 running this exact
 * kernel build, and against boot.img from CPH2745_16.0.9.400 (boot/init_boot
 * are byte-identical across the regional SKUs). */

OFFSETS_ENTRY("6.12.23-android16-5-gb2a876903b49-ab14541642-4k",  /* verified working on CPH2749 16.0.9.400 */
  .kernel_phys_load=0xc7800000, STRUCT_OFFSETS_6_12, .off_init_task=0x023ECF00, .off_init_cred=0x02402A68, .off_init_uts_ns=0x02574650,
  .off_empty_zero_page=0x02614000, .off_root_task_group=0x0261C580,
  .off_selinux_enforcing=0x026684F0, .off_kptr_restrict=0x023EB638,
  .off_selinux_blob_sizes=0x018304E8, .off_security_hook_heads=0,
  .off_kmalloc_caches=0x0182A4C0, .off_anon_pipe_buf_ops=0x0120ED88,
  .off_ashmem_misc_fops=0, .off_ashmem_fops=0x02695A88,
  .off_ashmem_ioctl=0x00D7E6E4, .off_ashmem_compat_ioctl=0x00D7ECCC,
  .off_ashmem_mmap=0x00D7ED48, .off_ashmem_open=0x00D7EDA4,
  .off_ashmem_release=0x00D7E7A4, .off_ashmem_show_fdinfo=0x00D7ECA4,
  .off_configfs_read_iter=0x00511F50, .off_configfs_bin_write_iter=0x00512184,
  .off_copy_splice_read=0x0048E190, .off_noop_llseek=0x0043B7D4,
  .off_cap_capable_active=0x02662B50,
  .off_slide_nfulnl_logger=0x023E2198, .off_slide_loggers_0_1=0x023E20E8,
  .off_slide_boot_id=0x02689868,
  .off_system_unbound_wq=0x01829250, .off_call_usermodehelper_exec_work=0x000F6744,
),

/* OnePlus 15 Android 17 Beta 3 (PLK110) — SM8850, android16-6 branch
 * Same waiter_word=2 as android16-5. sys_futex/sys_pselect6 each +0x10 but cancel out.
 * Rust ashmem, static-call security hooks (no cap_capable_active). */
OFFSETS_ENTRY("6.12.58-android16-6-g376529f3e739-ab15072794-4k",
  .kernel_phys_load=0, STRUCT_OFFSETS_6_12,
  .off_init_task=0x0252DAC0, .off_init_cred=0x02543830, .off_init_uts_ns=0x026B8B88,
  .off_empty_zero_page=0x0275D000, .off_root_task_group=0x027657C0,
  .off_selinux_enforcing=0x027B4848, .off_kptr_restrict=0x0252C218,
  .off_selinux_blob_sizes=0x01932868, .off_security_hook_heads=0,
  .off_kmalloc_caches=0x019284C0, .off_anon_pipe_buf_ops=0x012EB608,
  .off_ashmem_misc_fops=0, .off_ashmem_fops=0x027E2E38,
  .off_ashmem_ioctl=0x00EFCE04, .off_ashmem_compat_ioctl=0x00EFCE50,
  .off_ashmem_mmap=0x00EFCECC, .off_ashmem_open=0x00EFCEF0,
  .off_ashmem_release=0x00EFCFC8, .off_ashmem_show_fdinfo=0x00EFD190,
  .off_configfs_read_iter=0x00739B80, .off_configfs_bin_write_iter=0x0073A12C,
  .off_copy_splice_read=0x006E7C24, .off_noop_llseek=0x006BACEC,
  .off_cap_capable_active=0,
  .off_slide_nfulnl_logger=0x025221B0, .off_slide_loggers_0_1=0x025220F0,
  .off_slide_boot_id=0x027D6C08,
  .off_system_unbound_wq=0x01928250, .off_call_usermodehelper_exec_work=0x004790F0,
),
