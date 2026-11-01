"""Execute the production UAPI event dispatcher with host kernel shims."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LifecycleEventsTest(unittest.TestCase):
    def test_bootstrap_exception_requires_captured_domain_and_both_root_uids(self):
        source = (ROOT / "build-txn/selinux/selinux.c").read_text()
        start = source.index("void ksu_capture_bootstrap_domain(void)")
        end = source.index("static int transive_to_domain(", start)
        program = r'''
#include <assert.h>
#include <stdbool.h>
typedef unsigned u32;
struct uid { unsigned val; };
static struct uid uid, euid;
static u32 bootstrap_sid, sid;
static struct uid current_uid(void) { return uid; }
static struct uid current_euid(void) { return euid; }
static u32 current_sid(void) { return sid; }
''' + source[start:end] + r'''
int main(void) {
    sid = 42;
    assert(!ksu_is_bootstrap_root()); /* no captured loader */
    uid.val = euid.val = 10081;
    ksu_capture_bootstrap_domain();
    assert(bootstrap_sid == 0 && !ksu_is_bootstrap_root());
    uid.val = euid.val = 0;
    ksu_capture_bootstrap_domain();
    assert(ksu_is_bootstrap_root());
    uid.val = 10081;
    assert(!ksu_is_bootstrap_root());
    uid.val = 0; euid.val = 10081;
    assert(!ksu_is_bootstrap_root());
    euid.val = 0; sid = 43;
    assert(!ksu_is_bootstrap_root()); /* unrelated UID-0 sandbox */
    sid = 42;
    assert(ksu_is_bootstrap_root());
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix="ksu-bootstrap-", dir="/tmp/opencode") as directory:
            path = Path(directory)
            (path / "test.c").write_text(program)
            subprocess.run([os.environ.get("CC", "cc"), "-std=c11", "-Wall", "-Wextra",
                            "-Werror", "-fsanitize=undefined", str(path / "test.c"),
                            "-o", str(path / "test")], check=True)
            subprocess.run([str(path / "test")], check=True, timeout=10)

    def test_root_domain_handoff_checks_identity_and_commits_only_on_success(self):
        source = (ROOT / "build-txn/policy/app_profile.c").read_text()
        start = source.index("int ksu_adopt_root_domain(void)")
        end = source.index("int escape_with_root_profile(void)", start)
        program = r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <string.h>
#define KERNEL_SU_CONTEXT "u:r:ksu:s0"
#define TIF_KSU_DISABLE_ESCAPE_WITH_ROOT 63
struct uid { unsigned val; };
struct cred { int marker; };
static struct uid uid, euid;
static struct cred candidate;
static bool restricted, in_domain, allocation_fails;
static int transition_error, prepared, committed, aborted;
static struct uid current_uid(void) { return uid; }
static struct uid current_euid(void) { return euid; }
static bool test_thread_flag(int flag) { assert(flag == 63); return restricted; }
static bool is_ksu_domain(void) { return in_domain; }
static struct cred *prepare_creds(void) { ++prepared; return allocation_fails ? NULL : &candidate; }
static int setup_selinux(const char *domain, struct cred *cred) {
    assert(!strcmp(domain, KERNEL_SU_CONTEXT) && cred == &candidate);
    return transition_error;
}
static void abort_creds(struct cred *cred) { assert(cred == &candidate); ++aborted; }
static int commit_creds(struct cred *cred) { assert(cred == &candidate); ++committed; return 0; }
''' + source[start:end] + r'''
int main(void) {
    uid.val = 10081; euid.val = 10081;
    assert(ksu_adopt_root_domain() == -EPERM);
    euid.val = 0;
    assert(ksu_adopt_root_domain() == -EPERM);
    uid.val = 0; euid.val = 10081;
    assert(ksu_adopt_root_domain() == -EPERM);
    euid.val = 0; restricted = true;
    assert(ksu_adopt_root_domain() == -EPERM && prepared == 0);
    restricted = false; in_domain = true;
    assert(ksu_adopt_root_domain() == 0 && prepared == 0);
    in_domain = false; allocation_fails = true;
    assert(ksu_adopt_root_domain() == -ENOMEM && committed == 0);
    allocation_fails = false; transition_error = -EINVAL;
    assert(ksu_adopt_root_domain() == -EINVAL && aborted == 1 && committed == 0);
    transition_error = 0;
    assert(ksu_adopt_root_domain() == 0 && committed == 1 && aborted == 1);
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix="ksu-handoff-", dir="/tmp/opencode") as directory:
            path = Path(directory)
            (path / "test.c").write_text(program)
            subprocess.run([os.environ.get("CC", "cc"), "-std=c11", "-Wall", "-Wextra",
                            "-Werror", "-fsanitize=undefined", str(path / "test.c"),
                            "-o", str(path / "test")], check=True)
            subprocess.run([str(path / "test")], check=True, timeout=10)

    def test_services_handshake_reset_and_concurrent_claim(self):
        source = (ROOT / "build-txn/supercall/dispatch.c").read_text()
        start = source.index("static int do_report_event(")
        end = source.index("static int do_set_sepolicy(", start)
        header = (ROOT / "build-txn/uapi/supercall.h").read_text()
        events = "\n".join(re.findall(r"^#define EVENT_\w+ \d+$", header, re.M))
        program = r'''
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#define __user
#define pr_info(...) ((void)0)
typedef atomic_int atomic_t;
#define ATOMIC_INIT(x) ATOMIC_VAR_INIT(x)
#define atomic_set(p, v) atomic_store(p, v)
static int atomic_cmpxchg(atomic_t *p, int old, int value) {
    atomic_compare_exchange_strong(p, &old, value);
    return old;
}
struct ksu_report_event_cmd { uint32_t event; };
static bool ksu_late_loaded = true;
static unsigned post_calls, boot_calls, mount_calls;
static int ksu_copy_from_user(void *dst, const void *src, size_t size) {
    if (!src) return 1;
    memcpy(dst, src, size);
    return 0;
}
static void on_post_fs_data(void) { ++post_calls; }
static void on_boot_completed(void) { ++boot_calls; }
static void on_module_mounted(void) { ++mount_calls; }
''' + events + "\n" + source[start:end] + r'''
static int event(uint32_t id) {
    struct ksu_report_event_cmd cmd = { id };
    return do_report_event(&cmd);
}
static atomic_int winners;
static void *claim(void *arg) {
    (void)arg;
    int result = event(EVENT_SERVICES);
    assert(result == 0 || result == 1);
    atomic_fetch_add(&winners, result);
    return NULL;
}
int main(void) {
    _Static_assert(EVENT_SERVICES == 4, "ksud UAPI 5 event number");
    assert(do_report_event(NULL) == -EFAULT);
    assert(event(EVENT_POST_FS_DATA) == 0);
    assert(post_calls == 0); /* late load must not re-enter kernel boot hooks */
    assert(event(EVENT_SERVICES) == 1);
    assert(event(EVENT_SERVICES) == 0);
    assert(event(EVENT_BOOT_COMPLETED) == 0);
    assert(boot_calls == 0);
    assert(event(EVENT_SERVICES) == 0); /* boot-completed is not a reset */
    assert(event(EVENT_MODULE_MOUNTED) == 0 && mount_calls == 1);
    assert(event(EVENT_SERVICES) == 0);
    assert(do_report_event(NULL) == -EFAULT);
    assert(event(EVENT_SERVICES) == 0);
    for (int cycle = 0; cycle < 25; ++cycle) {
        assert(event(EVENT_POST_FS_DATA) == 0); /* emulated soft reboot */
        atomic_store(&winners, 0);
        pthread_t threads[16];
        for (int i = 0; i < 16; ++i) assert(!pthread_create(&threads[i], NULL, claim, NULL));
        for (int i = 0; i < 16; ++i) assert(!pthread_join(threads[i], NULL));
        assert(atomic_load(&winners) == 1);
    }
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix="ksu-events-", dir="/tmp/opencode") as directory:
            path = Path(directory)
            (path / "test.c").write_text(program)
            subprocess.run([os.environ.get("CC", "cc"), "-std=c11", "-Wall", "-Wextra",
                            "-Werror", "-pthread", "-fsanitize=undefined", str(path / "test.c"),
                            "-o", str(path / "test")], check=True)
            subprocess.run([str(path / "test")], check=True, timeout=20)


if __name__ == "__main__":
    unittest.main()
