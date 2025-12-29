#include <dlfcn.h>
#include <unistd.h>
#include <stdbool.h>
#include <assert.h>

#include "ushark_dll.h"
#include "../common/utils.h"

static void *sk_dll;
static void (*sk_init)();
static void (*sk_cleanup)();
static ushark_t* (*sk_new)(int, const char *);
static void (*sk_set_pref)(const char *, const char *);
static void (*sk_set_callbacks)(ushark_t *, const ushark_data_callbacks_t *);
static void (*sk_destroy)(ushark_t*);
static const char* (*sk_dissect)(ushark_t*, const unsigned char *, const struct pcap_pkthdr *);

bool ushark_init(pcapdroid_t *pd) {
    assert(!sk_dll);

    if (!pd->cb.get_libprog_path) {
        log_e("get_libprog_path not defined");
        return false;
    }

    char ushark_lib[PATH_MAX];
    ushark_lib[0] = '\0';
    pd->cb.get_libprog_path(pd, "ushark", ushark_lib, sizeof(ushark_lib));

    if (!ushark_lib[0]) {
        log_e("cannot find libushark.so");
        return false;
    }

    log_d("libushark found: %s", ushark_lib);

    sk_dll = dlopen(ushark_lib, RTLD_NOW | RTLD_LOCAL);
    if (!sk_dll) {
        log_d("loading libushark.so failed: %s", dlerror());
        return false;
    }

    sk_init = dlsym(sk_dll, "ushark_init");
    sk_cleanup = dlsym(sk_dll, "ushark_cleanup");
    sk_new = dlsym(sk_dll, "ushark_new");
    sk_set_pref = dlsym(sk_dll, "ushark_set_pref");
    sk_set_callbacks = dlsym(sk_dll, "ushark_set_callbacks");
    sk_destroy = dlsym(sk_dll, "ushark_destroy");
    sk_dissect = dlsym(sk_dll, "ushark_dissect");

    if (!sk_init || !sk_cleanup || !sk_new || !sk_set_pref || !sk_set_callbacks || !sk_destroy || !sk_dissect) {
        dlclose(sk_dll);
        sk_dll = NULL;
        log_e("libushark.so misses some required symbols");
        return false;
    }

    sk_init();
    return true;
}

void ushark_cleanup() {
    assert(sk_dll);

    sk_cleanup();

    sk_init = NULL;
    sk_cleanup = NULL;
    sk_new = NULL;
    sk_set_pref = NULL;
    sk_set_callbacks = NULL;
    sk_destroy = NULL;
    sk_dissect = NULL;

    // deallocates the static variables in wireshark, necessary to run cleanly again
    dlclose(sk_dll);
    sk_dll = NULL;
}

ushark_t* ushark_new(int pcap_encap, const char *dfilter) {
    assert(sk_new);

    return sk_new(pcap_encap, dfilter);
}

void ushark_destroy(ushark_t *sk) {
    assert(sk_destroy);

    sk_destroy(sk);
}

void ushark_set_pref(const char *name, const char *val) {
    assert(sk_set_pref);

    sk_set_pref(name, val);
}

void ushark_set_callbacks(ushark_t *sk, const ushark_data_callbacks_t *cbs) {
    assert(sk_set_callbacks);

    sk_set_callbacks(sk, cbs);
}

const char* ushark_dissect(ushark_t *sk, const unsigned char *buf, const struct pcap_pkthdr *hdr) {
    assert(sk_dissect);

    return sk_dissect(sk, buf, hdr);
}
