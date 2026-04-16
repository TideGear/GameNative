/* NOTE: This class is compiled but unused here. Though it can be used for arm64ec based containers, it is now included in the imagefs.txz */
/* You can choose to preload this from the native lib dir in the apk, via BionicProgramLauncherComponent. But if you do, the architecture */
/* may not work with x86_64 Wine based containers without additional patches to link related binaries to the imageFs libSDL2 and it's symlinks */
/* ─────────── evshim.c (Multi-Controller & Dynamic SDL) ─────────── */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include <SDL2/SDL.h>
#include <stdarg.h>
#include <stdatomic.h>

static int g_debug_enabled = 0;

#define LOGI(...) dprintf(STDOUT_FILENO, __VA_ARGS__)
#define LOGE(...) dprintf(STDERR_FILENO, __VA_ARGS__)
#define LOGD(...) do { if (g_debug_enabled) dprintf(STDOUT_FILENO, __VA_ARGS__); } while (0)

#define MAX_GAMEPADS 4
static int vjoy_ids[MAX_GAMEPADS] = {-1};
static int read_fd  [MAX_GAMEPADS] = {-1};
static int rumble_fd[MAX_GAMEPADS] = {-1};
static void *handle = NULL;
static pthread_mutex_t shm_mutex = PTHREAD_MUTEX_INITIALIZER;

/* Set to 1 on the player index being refreshed by the SDL keepalive so that
 * the resulting re-entrant OnRumble() call does not overwrite shared memory
 * (which may have been zeroed by a stop command that raced the keepalive). */
static atomic_int g_keepalive_active[MAX_GAMEPADS];

struct gamepad_io {
    int16_t lx, ly, rx, ry, lt, rt;
    uint8_t btn[15];
    uint8_t hat;
    uint8_t _padding[4];
    uint16_t low_freq_rumble;
    uint16_t high_freq_rumble;
};

static int (*p_SDL_Init)(uint32_t flags);
static const char * (*p_SDL_GetError)(void);
static SDL_Joystick * (*p_SDL_JoystickOpen)(int device_index);
static int (*p_SDL_JoystickAttachVirtualEx)(const SDL_VirtualJoystickDesc *desc);
static int (*p_SDL_JoystickSetVirtualAxis)(SDL_Joystick *joystick, int axis, int16_t value);
static int (*p_SDL_JoystickSetVirtualButton)(SDL_Joystick *joystick, int button, uint8_t value);
static int (*p_SDL_JoystickSetVirtualHat)(SDL_Joystick *joystick, int hat, uint8_t value);
static void (*p_SDL_PumpEvents)(void);
static void (*p_SDL_Delay)(uint32_t ms);
static void (*p_SDL_GetVersion)(SDL_version *);
static int (*p_SDL_JoystickRumble)(SDL_Joystick *, uint16_t, uint16_t, uint32_t);

/* Per-player rumble state for SDL keepalive.
 * Wine/XInput "set and forget" semantics expect motors to stay on until
 * explicitly stopped, but SDL's internal timer auto-expires rumble after
 * the duration Wine passes (~1 s).  We periodically re-send the last
 * non-zero values to reset that timer so the auto-expiry never fires. */
static uint16_t last_rumble_low [MAX_GAMEPADS];
static uint16_t last_rumble_high[MAX_GAMEPADS];

#define RUMBLE_KEEPALIVE_TICKS  100   /* 100 × 5 ms = 500 ms */
#define RUMBLE_KEEPALIVE_DUR_MS 2000  /* SDL expiry reset window */


#define GETFUNCPTR(name)\
do {\
	if (!(p_##name = (typeof(p_##name))dlsym(handle, #name))) {\
        LOGE("Failed to load SDL symbol, %s\n", #name);\
    }\
} while (0)

static int OnRumble(void *userdata,
                    uint16_t low_frequency_rumble,
                    uint16_t high_frequency_rumble)
{
    int idx = (int)(intptr_t)userdata;
    if (idx < 0 || idx >= MAX_GAMEPADS || rumble_fd[idx] < 0) return -1;

    /* When the SDL keepalive re-invokes SDL_JoystickRumble to reset SDL's
     * internal expiry timer, it synchronously calls back into OnRumble.
     * We must NOT update last_rumble or pwrite in that case: the game may
     * have already sent OnRumble(0,0) to stop vibration, and re-writing
     * the old non-zero values would restart rumble on the Android side. */
    if (atomic_load(&g_keepalive_active[idx])) {
        LOGD("Rumble P%d  low=%u  high=%u [keepalive noop]\n", idx,
             low_frequency_rumble, high_frequency_rumble);
        return 0;
    }

    pthread_mutex_lock(&shm_mutex);

    last_rumble_low [idx] = low_frequency_rumble;
    last_rumble_high[idx] = high_frequency_rumble;

    uint16_t vals[2] = { low_frequency_rumble, high_frequency_rumble };
    ssize_t w = pwrite(rumble_fd[idx], vals, sizeof(vals), 32);

    pthread_mutex_unlock(&shm_mutex);

    if (w != (ssize_t)sizeof(vals))
        LOGE("Rumble write failed (P%d): %s\n", idx, strerror(errno));

    LOGD("Rumble P%d  low=%u  high=%u\n", idx,
         low_frequency_rumble, high_frequency_rumble);
    return 0;
}



static void *event_pump_thread(void *arg) {
    for (;;) {
        p_SDL_PumpEvents();
        p_SDL_Delay(5);
    }
    return NULL;
}

static void *vjoy_updater(void *arg)
{
    int idx = (int)(intptr_t)arg;

    int fd = read_fd[idx];
    if (fd < 0) {
        LOGE("P%d: read_fd not initialised – aborting thread\n", idx);
        return NULL;
    }

    SDL_Joystick *js = p_SDL_JoystickOpen(vjoy_ids[idx]);
    if (!js) {
        LOGE("P%d: SDL_JoystickOpen failed\n", idx);
        return NULL;
    }

    struct gamepad_io cur, last_state = {0};
    int keepalive_ctr = 0;

    LOGI("VJOY UPDATER P%d running (PID %d)\n", idx, getpid());

    for (;;) {
        pthread_mutex_lock(&shm_mutex);
        ssize_t n = pread(fd, &cur, sizeof cur, 0);
        pthread_mutex_unlock(&shm_mutex);

        if (n == sizeof cur && memcmp(&cur, &last_state, sizeof cur) != 0) {

            p_SDL_JoystickSetVirtualAxis (js, 0, cur.lx);
            p_SDL_JoystickSetVirtualAxis (js, 1, cur.ly);
            p_SDL_JoystickSetVirtualAxis (js, 2, cur.rx);
            p_SDL_JoystickSetVirtualAxis (js, 3, cur.ry);
            p_SDL_JoystickSetVirtualAxis (js, 4, cur.lt);
            p_SDL_JoystickSetVirtualAxis (js, 5, cur.rt);

            for (int i = 0; i < 15; ++i)
                p_SDL_JoystickSetVirtualButton(js, i, cur.btn[i]);

            p_SDL_JoystickSetVirtualHat(js, 0, cur.hat);

            last_state = cur;
        }
        else if (n < 0) {
            LOGE("P%d: read error: %s\n", idx, strerror(errno));
        }

        /* Re-send last non-zero rumble to SDL periodically so its internal
         * expiry timer never fires the false OnRumble(0,0).  This preserves
         * XInput "set and forget" semantics through the SDL translation. */
        if (p_SDL_JoystickRumble && ++keepalive_ctr >= RUMBLE_KEEPALIVE_TICKS) {
            keepalive_ctr = 0;
            uint16_t kl, kh;
            pthread_mutex_lock(&shm_mutex);
            kl = last_rumble_low[idx];
            kh = last_rumble_high[idx];
            pthread_mutex_unlock(&shm_mutex);
            if (kl != 0 || kh != 0) {
                /* Flag this player's slot before calling SDL so that the
                 * synchronous OnRumble() re-entry is recognised as a
                 * keepalive and does not overwrite shared memory. */
                atomic_store(&g_keepalive_active[idx], 1);
                p_SDL_JoystickRumble(js, kl, kh, RUMBLE_KEEPALIVE_DUR_MS);
                atomic_store(&g_keepalive_active[idx], 0);
                LOGD("Rumble keepalive P%d  low=%u  high=%u\n", idx, kl, kh);
            }
        }

        p_SDL_Delay(5);
    }

    return NULL;
}

__attribute__((constructor))
static void initialize_all_pads(void)
{
    const char *dbg = getenv("EVSHIM_DEBUG");
    g_debug_enabled = dbg && strchr("1yY", *dbg);

    LOGI("EVSHIM initializing…\n");

    handle = dlopen("libSDL2-2.0.so.0", RTLD_LAZY | RTLD_GLOBAL);
    if (!handle) { LOGE("dlopen SDL failed: %s\n", dlerror()); return; }

    GETFUNCPTR(SDL_Init);  GETFUNCPTR(SDL_GetError);
    GETFUNCPTR(SDL_JoystickOpen);  GETFUNCPTR(SDL_JoystickAttachVirtualEx);
    GETFUNCPTR(SDL_JoystickSetVirtualAxis);  GETFUNCPTR(SDL_JoystickSetVirtualButton);
    GETFUNCPTR(SDL_JoystickSetVirtualHat);   GETFUNCPTR(SDL_PumpEvents);
    GETFUNCPTR(SDL_Delay);  GETFUNCPTR(SDL_GetVersion);
    GETFUNCPTR(SDL_JoystickRumble);

    p_SDL_Init(SDL_INIT_JOYSTICK);

    SDL_version v; p_SDL_GetVersion(&v);
    LOGI("SDL %d.%d.%d bound\n", v.major, v.minor, v.patch);

    int players = getenv("EVSHIM_MAX_PLAYERS") ? atoi(getenv("EVSHIM_MAX_PLAYERS")) : 1;
    if (players > MAX_GAMEPADS) players = MAX_GAMEPADS;


    /* per-player setup */
    for (int i = 0; i < players; ++i) {

        char path[256];
        snprintf(path, sizeof path,
                 "/data/data/app.gamenative/files/imagefs/tmp/gamepad%s.mem",
                 (i == 0) ? "" : (char[2]){'0' + i, '\0'});

        /* open once – store for reader + writer */
        read_fd  [i] = open(path, O_RDONLY);
        rumble_fd[i] = open(path, O_WRONLY);

        if (read_fd[i]  < 0 || rumble_fd[i] < 0) {
            LOGE("P%d: failed to open shared file '%s': %s\n", i, path, strerror(errno));
            if (read_fd[i]  >= 0) close(read_fd[i]);
            if (rumble_fd[i] >= 0) close(rumble_fd[i]);
            read_fd[i] = rumble_fd[i] = -1;
            continue;
        }

        /* SDL virtual device */
        SDL_VirtualJoystickDesc d = {0};
        d.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
        d.type    = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
        d.naxes   = 6; d.nbuttons = 15; d.nhats = 1;
        d.Rumble  = &OnRumble;  d.userdata = (void*)(intptr_t)i;

        char name[64];
        snprintf(name, sizeof name, (i < 2) ? "B (Player %d)" : "A (Player %d)", i + 1);
        d.name = strdup(name);

        vjoy_ids[i] = p_SDL_JoystickAttachVirtualEx(&d);
        if (vjoy_ids[i] < 0) {
            LOGE("P%d: SDL attach failed: %s\n", i, p_SDL_GetError());
            close(read_fd[i]);   read_fd[i]   = -1;
            close(rumble_fd[i]); rumble_fd[i] = -1;
            continue;
        }
        LOGD("P%d: virtual joystick id=%d ready\n", i, vjoy_ids[i]);

        pthread_t up_tid;
        pthread_create(&up_tid, NULL, vjoy_updater, (void*)(intptr_t)i);
        pthread_detach(up_tid);
    }
}

/* ------------  “hide /dev/input/event*” hooks  -------------------- */
#undef ioctl

static inline int is_event_node(const char *p)
{ return p && !strncmp(p, "/dev/input/event", 16); }

typedef int (*open_f)(const char *, int, ...);
static open_f real_open;

static int open_common(const char *path, int flags, va_list ap)
{
    if (is_event_node(path)) { errno = ENOENT; return -1; }
    if (!real_open) real_open = (open_f)dlsym(RTLD_NEXT, "open");
    mode_t mode = 0;
    if (flags & O_CREAT) mode = va_arg(ap, mode_t);
    return real_open(path, flags, mode);
}

int open(const char *path, int flags, ...) __attribute__((visibility("default")));
int open(const char *path, int flags, ...)
{
    va_list ap;
    va_start(ap, flags);
    int r = open_common(path, flags, ap);
    va_end(ap);
    return r;
}

int open64(const char *path, int flags, ...) __attribute__((visibility("default")));
int open64(const char *path, int flags, ...)
{
    va_list ap;
    va_start(ap, flags);
    int r = open_common(path, flags, ap);
    va_end(ap);
    return r;
}

typedef int (*ioctl_f)(int, int, ...);
static ioctl_f real_ioctl;

int ioctl(int fd, int req, ...) __attribute__((visibility("default")));
int ioctl(int fd, int req, ...)
{
    if (!real_ioctl) real_ioctl = (ioctl_f)dlsym(RTLD_NEXT, "ioctl");
    char linkbuf[64], path[64];
    snprintf(linkbuf, sizeof linkbuf, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(linkbuf, path, sizeof path - 1);
    if (n > 0) {
        path[n] = 0;
        if (is_event_node(path)) { errno = ENOTTY; return -1; }
    }
    va_list ap;
    va_start(ap, req);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return real_ioctl(fd, req, arg);
}

typedef ssize_t (*read_f)(int, void *, size_t);
static read_f real_read;

ssize_t read(int fd, void *buf, size_t count) __attribute__((visibility("default")));
ssize_t read(int fd, void *buf, size_t count)
{
    if (!real_read) real_read = (read_f)dlsym(RTLD_NEXT, "read");
    char linkbuf[64], path[64];
    snprintf(linkbuf, sizeof(linkbuf), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(linkbuf, path, sizeof(path) - 1);
    if (n > 0) {
        path[n] = 0;
        if (is_event_node(path)) { errno = EAGAIN; return -1; }
    }
    return real_read(fd, buf, count);
}
/* --------------------------------------------------------------------- */
