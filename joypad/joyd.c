/*
 * joyd - driver de controle USB (joystick/gamepad HID) -> teclado virtual.
 *
 * Feito para o iChroma II reaproveitado: Android 2.3.3 + kernel 2.6.35.
 * Esse kernel nao tem driver para controles "DragonRise" (0079:0006) e o
 * Android 2.3 nao entende joystick (so a partir do 3.1). Entao este programa
 * le o controle direto pelo usbfs (/dev/bus/usb) e cria um teclado virtual
 * via /dev/uinput: direcional -> setas, botoes -> teclas.
 *
 * Sem libc: syscalls diretas (ARM EABI), binario estatico nao-PIE, para rodar
 * no kernel antigo sem depender de nada do sistema.
 *
 * Controles aceitos: HID generico (ex.: DragonRise 0079:0006) e X-input
 * (protocolo do Xbox 360, ex.: dongle EasySMX X05 = 045e:028e; o kernel nao
 * tem o driver xpad).
 *
 * Uso:  joyd list             lista dispositivos/interfaces USB
 *       joyd dump [segundos]  mostra o que o controle manda (diagnostico)
 *       joyd run              roda em primeiro plano
 *       joyd -d               roda como daemon (log em /data/local/tmp/joyd.log)
 * Mapa opcional: /data/local/joyd.map (HID) ou /data/local/joyd-xbox.map
 *   (X-input, ordem A B X Y LB RB LT RT BACK START L3 R3 GUIDE)
 *   = codigos de tecla Linux p/ botao 1,2,3...
 *   (numeros separados por espaco/linha; 0 = botao sem funcao; '#' comenta)
 *   O mapa e relido toda vez que o controle e conectado.
 * Nunca registra a tecla Q (16): assim o Android 2.3 nao acha que e um teclado
 * alfabetico e o teclado da tela continua aparecendo.
 */

typedef unsigned char u8;
typedef signed char s8;
typedef unsigned short u16;
typedef short s16;
typedef unsigned int u32;
typedef int s32;

/* ---------------- syscalls (ARM EABI) ---------------- */
static long sc(long n, long a, long b, long c)
{
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    register long r7 __asm__("r7") = n;
    __asm__ volatile("svc #0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory");
    return r0;
}
static long sc4(long n, long a, long b, long c, long d)
{
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    register long r3 __asm__("r3") = d;
    register long r7 __asm__("r7") = n;
    __asm__ volatile("svc #0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r3), "r"(r7) : "memory");
    return r0;
}
static long sc6(long n, long a, long b, long c, long d, long e, long f)
{
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    register long r3 __asm__("r3") = d;
    register long r4 __asm__("r4") = e;
    register long r5 __asm__("r5") = f;
    register long r7 __asm__("r7") = n;
    __asm__ volatile("svc #0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5), "r"(r7) : "memory");
    return r0;
}
#define NR_unlink 10
#define NR_execve 11
#define NR_mmap2 192
#define NR_wait4 114
#define NR_exit 1
#define NR_fork 2
#define NR_read 3
#define NR_write 4
#define NR_open 5
#define NR_close 6
#define NR_ioctl 54
#define NR_dup2 63
#define NR_setsid 66
#define NR_nanosleep 162
#define NR_clock_gettime 263
#define NR_socket 281
#define NR_bind 282

#define O_RDONLY 0
#define O_WRONLY 1
#define O_RDWR 2
#define O_CREAT 0100
#define O_TRUNC 01000

static void sys_exit(int c) { for (;;) sc(NR_exit, c, 0, 0); }
static int sys_open(const char *p, int f, int m) { return (int)sc(NR_open, (long)p, f, m); }
static int sys_read(int fd, void *b, int n) { return (int)sc(NR_read, fd, (long)b, n); }
static int sys_write(int fd, const void *b, int n) { return (int)sc(NR_write, fd, (long)b, n); }
static void sys_close(int fd) { sc(NR_close, fd, 0, 0); }
static int sys_ioctl(int fd, unsigned long req, long arg) { return (int)sc(NR_ioctl, fd, (long)req, arg); }

/* ---------------- mem* (o compilador pode chamar) ---------------- */
void *memset(void *d, int c, unsigned long n)
{ volatile u8 *p = d; while (n--) *p++ = (u8)c; return d; }
void *memcpy(void *d, const void *s, unsigned long n)
{ volatile u8 *p = d; const u8 *q = s; while (n--) *p++ = *q++; return d; }
void *memmove(void *d, const void *s, unsigned long n)
{
    volatile u8 *p = d; const u8 *q = s;
    if (p < q) { while (n--) *p++ = *q++; }
    else { p += n; q += n; while (n--) *--p = *--q; }
    return d;
}
void __aeabi_memcpy(void *d, const void *s, unsigned long n) { memcpy(d, s, n); }
void __aeabi_memcpy4(void *d, const void *s, unsigned long n) { memcpy(d, s, n); }
void __aeabi_memcpy8(void *d, const void *s, unsigned long n) { memcpy(d, s, n); }
void __aeabi_memmove(void *d, const void *s, unsigned long n) { memmove(d, s, n); }
void __aeabi_memmove4(void *d, const void *s, unsigned long n) { memmove(d, s, n); }
void __aeabi_memmove8(void *d, const void *s, unsigned long n) { memmove(d, s, n); }
void __aeabi_memset(void *d, unsigned long n, int c) { memset(d, c, n); }
void __aeabi_memset4(void *d, unsigned long n, int c) { memset(d, c, n); }
void __aeabi_memset8(void *d, unsigned long n, int c) { memset(d, c, n); }
void __aeabi_memclr(void *d, unsigned long n) { memset(d, 0, n); }
void __aeabi_memclr4(void *d, unsigned long n) { memset(d, 0, n); }
void __aeabi_memclr8(void *d, unsigned long n) { memset(d, 0, n); }

/* ---------------- saida de texto (sem divisao) ---------------- */
static int slen(const char *s) { int n = 0; while (s[n]) n++; return n; }
static void put(const char *s) { sys_write(1, s, slen(s)); }
static void puthex(u32 v, int dg)
{
    char b[9]; int i;
    for (i = dg - 1; i >= 0; i--) { int d = v & 15; b[i] = (char)(d < 10 ? '0' + d : 'a' + d - 10); v >>= 4; }
    b[dg] = 0; put(b);
}
static void putdec(int v)
{
    static const int p[10] = {1000000000, 100000000, 10000000, 1000000, 100000, 10000, 1000, 100, 10, 1};
    char b[12]; int n = 0, i, st = 0;
    if (v < 0) { put("-"); v = -v; }
    for (i = 0; i < 10; i++) {
        int d = 0;
        while (v >= p[i]) { v -= p[i]; d++; }
        if (d || st || i == 9) { b[n++] = (char)('0' + d); st = 1; }
    }
    b[n] = 0; put(b);
}
static void fmt3(char *o, int v)  /* 3 digitos decimais */
{
    int c = 0, d = 0;
    while (v >= 100) { v -= 100; c++; }
    while (v >= 10) { v -= 10; d++; }
    o[0] = (char)('0' + c); o[1] = (char)('0' + d); o[2] = (char)('0' + v);
}
static void sleep_ms(int ms)
{
    long ts[2]; ts[0] = 0;
    while (ms >= 1000) { ms -= 1000; ts[0]++; }
    ts[1] = (long)ms * 1000000L;
    sc(NR_nanosleep, (long)ts, 0, 0);
}
static long now_s(void) { long ts[2] = {0, 0}; sc(NR_clock_gettime, 1 /*MONOTONIC*/, (long)ts, 0); return ts[0]; }

/* ---------------- usbfs ---------------- */
#define USBDEVFS_CONTROL          0xC0105500UL
#define USBDEVFS_BULK             0xC0105502UL
#define USBDEVFS_CLAIMINTERFACE   0x8004550FUL
#define USBDEVFS_RELEASEINTERFACE 0x80045510UL
#define USBDEVFS_IOCTL            0xC00C5512UL
#define USBDEVFS_DISCONNECT       0x5516
#define USBDEVFS_CONNECT          0x5517

struct usb_ioctl { int ifno; int code; void *data; };
struct usb_ctrl { u8 rt, req; u16 val, idx, len; u32 timeout; void *data; };
struct usb_bulk { u32 ep, len, timeout; void *data; };

/* type: 0 = HID generico, 1 = X-input (protocolo do controle de Xbox 360) */
#define T_HID 0
#define T_XINPUT 1
struct dev { int fd, bus, addr, ifno, ep, ep_out, maxp, rdlen, type; u16 vid, pid; };

static int usb_ctrl(int fd, int rt, int req, int val, int idx, void *data, int len)
{
    struct usb_ctrl c;
    c.rt = (u8)rt; c.req = (u8)req; c.val = (u16)val; c.idx = (u16)idx; c.len = (u16)len;
    c.timeout = 1000; c.data = data;
    return sys_ioctl(fd, USBDEVFS_CONTROL, (long)&c);
}
static int usb_drv(int fd, int ifno, int code)
{
    struct usb_ioctl i; i.ifno = ifno; i.code = code; i.data = 0;
    return sys_ioctl(fd, USBDEVFS_IOCTL, (long)&i);
}

/* Abre /dev/bus/usb/BBB/DDD e procura interface de controle:
 *  - HID nao-boot (classe 3, protocolo 0), ou
 *  - X-input (classe FF, subclasse 5D, protocolo 01 = controle de Xbox 360 com fio / dongle)
 * com endpoint IN de interrupcao (e o OUT, se houver, p/ acender o LED do X-input). */
static int probe(int bus, int addr, struct dev *d)
{
    char path[] = "/dev/bus/usb/000/000";
    u8 b[1024];
    int fd, n, i, in_if = 0;
    fmt3(path + 13, bus); fmt3(path + 17, addr);
    fd = sys_open(path, O_RDWR, 0);
    if (fd < 0) return 0;
    n = sys_read(fd, b, sizeof b);
    if (n < 18 || b[0] != 18 || b[1] != 1) { sys_close(fd); return 0; }
    d->fd = fd; d->bus = bus; d->addr = addr;
    d->vid = (u16)(b[8] | b[9] << 8); d->pid = (u16)(b[10] | b[11] << 8);
    d->ifno = -1; d->ep = 0; d->ep_out = 0; d->maxp = 0; d->rdlen = 0; d->type = T_HID;
    for (i = 18; i + 2 <= n; ) {
        int L = b[i], T = b[i + 1];
        if (L < 2 || i + L > n) break;
        if (T == 4 && L >= 9) {
            int cls = b[i + 5], sub = b[i + 6], pro = b[i + 7];
            if (d->ep) break;              /* ja achamos uma interface boa */
            in_if = 0;
            if (cls == 3 && pro == 0) { in_if = 1; d->type = T_HID; }
            else if (cls == 0xFF && sub == 0x5D && pro == 0x01) { in_if = 1; d->type = T_XINPUT; }
            if (in_if) { d->ifno = b[i + 2]; d->rdlen = 0; d->ep_out = 0; }
        } else if (T == 0x21 && in_if && L >= 9) {
            d->rdlen = b[i + 7] | b[i + 8] << 8;
        } else if (T == 5 && in_if && L >= 7 && (b[i + 3] & 3) == 3) {
            if ((b[i + 2] & 0x80) && !d->ep) { d->ep = b[i + 2]; d->maxp = b[i + 4] | b[i + 5] << 8; }
            else if (!(b[i + 2] & 0x80) && !d->ep_out) d->ep_out = b[i + 2];
        }
        i += L;
    }
    if (!d->ep) { sys_close(fd); return 0; }
    if (d->maxp <= 0 || d->maxp > 64) d->maxp = 64;
    return 1;
}

/* Lista todos os dispositivos USB e interfaces (diagnostico). */
static void list_usb(void)
{
    int bus, a, fd, n, i;
    u8 b[1024];
    for (bus = 1; bus <= 2; bus++)
        for (a = 1; a < 128; a++) {
            char path[] = "/dev/bus/usb/000/000";
            fmt3(path + 13, bus); fmt3(path + 17, a);
            fd = sys_open(path, O_RDONLY, 0);
            if (fd < 0) continue;
            n = sys_read(fd, b, sizeof b);
            sys_close(fd);
            if (n < 18) continue;
            put(path); put("  id "); puthex((u32)(b[8] | b[9] << 8), 4); put(":"); puthex((u32)(b[10] | b[11] << 8), 4);
            put("  classe "); puthex(b[4], 2); put("\n");
            for (i = 18; i + 2 <= n; ) {
                int L = b[i], T = b[i + 1];
                if (L < 2 || i + L > n) break;
                if (T == 4 && L >= 9) {
                    put("   if "); putdec(b[i + 2]); put(" classe "); puthex(b[i + 5], 2); put("/"); puthex(b[i + 6], 2);
                    put("/"); puthex(b[i + 7], 2); put(" endpoints "); putdec(b[i + 4]); put("\n");
                } else if (T == 5 && L >= 7) {
                    put("     ep "); puthex(b[i + 2], 2); put(" tipo "); putdec(b[i + 3] & 3);
                    put(" pacote "); putdec(b[i + 4] | b[i + 5] << 8); put("\n");
                }
                i += L;
            }
        }
}

/* ---------------- parser de report descriptor HID ---------------- */
#define MAXBTN 32
struct field { int present, id, off, size, min, max; };
struct joy {
    struct field x, y, hat, dp[4], btn[MAXBTN];
    struct field z, rx, ry, rz;     /* candidatos a analogico direito (ver look_fields) */
    int nbtn, is_joy, uses_ids;
};

static u32 getu(const u8 *p, int sz) { u32 v = 0; int i; for (i = 0; i < sz; i++) v |= (u32)p[i] << (8 * i); return v; }
static s32 gets(const u8 *p, int sz)
{
    u32 v = getu(p, sz);
    if (sz == 1) return (s8)v;
    if (sz == 2) return (s16)v;
    return (s32)v;
}
static void setf(struct field *f, int id, int off, int size, int min, int max)
{
    if (f->present) return;
    f->present = 1; f->id = id; f->off = off; f->size = size;
    if (max < min) { min = 0; max = size >= 31 ? 0x7fffffff : (1 << size) - 1; }
    f->min = min; f->max = max;
}
static void assign(struct joy *j, u32 u, int id, int off, int size, int min, int max)
{
    u32 page = u >> 16, us = u & 0xffff;
    if (page == 1) {
        if (us == 0x30) setf(&j->x, id, off, size, min, max);
        else if (us == 0x31) setf(&j->y, id, off, size, min, max);
        /* O DragonRise declara Z duas vezes: o primeiro fica parado, o ultimo e o analogico. Vale o ultimo. */
        else if (us == 0x32) { j->z.present = 0; setf(&j->z, id, off, size, min, max); }
        else if (us == 0x33) setf(&j->rx, id, off, size, min, max);
        else if (us == 0x34) setf(&j->ry, id, off, size, min, max);
        else if (us == 0x35) setf(&j->rz, id, off, size, min, max);
        else if (us == 0x39) setf(&j->hat, id, off, size, min, max);
        else if (us >= 0x90 && us <= 0x93) setf(&j->dp[us - 0x90], id, off, size, min, max);
    } else if (page == 9 && us >= 1 && us <= MAXBTN) {
        setf(&j->btn[us - 1], id, off, size, min, max);
        if ((int)us > j->nbtn) j->nbtn = (int)us;
    }
}
static void hid_parse(const u8 *d, int n, struct joy *j)
{
    u32 page = 0, usages[16], umin = 0, umax = 0;
    int lmin = 0, lmax = 0, rsize = 0, rcount = 0, rid = 0, nus = 0, rng = 0, i = 0, k;
    int offs[16];
    memset(j, 0, sizeof *j);
    memset(offs, 0, sizeof offs);
    while (i < n) {
        u8 b = d[i++];
        int sz, type, tag; u32 uv; s32 sv;
        if (b == 0xFE) { if (i + 1 >= n) break; i += 2 + d[i]; continue; }
        sz = b & 3; if (sz == 3) sz = 4;
        type = (b >> 2) & 3; tag = b >> 4;
        if (i + sz > n) break;
        uv = getu(d + i, sz); sv = gets(d + i, sz); i += sz;
        if (type == 1) {               /* global */
            if (tag == 0) page = uv;
            else if (tag == 1) lmin = sv;
            else if (tag == 2) lmax = sv;
            else if (tag == 7) rsize = (int)uv;
            else if (tag == 8) { rid = (int)uv; j->uses_ids = 1; }
            else if (tag == 9) rcount = (int)uv;
        } else if (type == 2) {        /* local */
            u32 full = sz == 4 ? uv : (page << 16) | uv;
            if (tag == 0) { if (nus < 16) usages[nus++] = full; }
            else if (tag == 1) { umin = full; rng = 1; }
            else if (tag == 2) { umax = full; }
        } else if (type == 0) {        /* main */
            if (tag == 8) {            /* Input */
                int *po = &offs[rid & 15];
                if (!(uv & 1) && (uv & 2)) {   /* dado variavel (nao constante, nao array) */
                    for (k = 0; k < rcount; k++) {
                        u32 u = 0;
                        if (nus) u = usages[k < nus ? k : nus - 1];
                        else if (rng) { u = umin + (u32)k; if (u > umax) break; }
                        if (u) assign(j, u, rid, *po + k * rsize, rsize, lmin, lmax);
                    }
                }
                *po += rsize * rcount;
            } else if (tag == 0xA) {   /* Collection */
                if (uv == 1 && nus && (usages[0] == 0x10004 || usages[0] == 0x10005)) j->is_joy = 1;
            }
            nus = 0; rng = 0;
        }
    }
}
static int rdf(const u8 *r, int len, const struct field *f)
{
    u32 v = 0; int b;
    for (b = 0; b < f->size && b < 32; b++) {
        int bit = f->off + b, byte = bit >> 3;
        if (byte >= len) break;
        if ((r[byte] >> (bit & 7)) & 1) v |= 1u << b;
    }
    if (f->min < 0 && f->size < 32 && ((v >> (f->size - 1)) & 1)) v |= ~0u << f->size;
    return (int)v;
}

/* ---------------- uinput (teclado virtual) ---------------- */
#define UI_SET_EVBIT  0x40045564UL
#define UI_SET_KEYBIT 0x40045565UL
#define UI_SET_RELBIT 0x40045566UL
#define UI_DEV_CREATE 0x5501UL
struct uidev {
    char name[80];
    u16 bustype, vendor, product, version;
    int ff_effects_max;
    int absmax[64], absmin[64], absfuzz[64], absflat[64];
};
static int ui_fd = -1, uim_fd = -1;
static int ui_create(int fd, const char *nm, int product)
{
    static struct uidev u;
    int k, r;
    memset(&u, 0, sizeof u);
    for (k = 0; nm[k] && k < 79; k++) u.name[k] = nm[k];
    u.bustype = 0x06 /* BUS_VIRTUAL */; u.vendor = 0x1209; u.product = (u16)product; u.version = 1;
    if (sys_write(fd, &u, sizeof u) != (int)sizeof u) { sys_close(fd); return -1; }
    r = sys_ioctl(fd, UI_DEV_CREATE, 0);
    if (r < 0) { sys_close(fd); return r; }
    return fd;
}
static int ui_open(void)
{
    int fd, k;
    fd = sys_open("/dev/uinput", O_WRONLY, 0);
    if (fd < 0) return fd;
    sys_ioctl(fd, UI_SET_EVBIT, 1 /* EV_KEY */);
    for (k = 1; k < 249; k++) if (k != 16 /* KEY_Q */) sys_ioctl(fd, UI_SET_KEYBIT, k);
    for (k = 0x130; k <= 0x13e; k++) sys_ioctl(fd, UI_SET_KEYBIT, k);   /* BTN_A .. BTN_THUMBR */
    return ui_create(fd, "joyd USB gamepad", 1);
}
/* Mouse virtual: o analogico direito vira movimento relativo, lido direto pelo Xash3D (evdev).
 * So tem eixos, sem botao: assim o Android 2.3 o ignora, em vez de trata-lo como trackball. */
static int uim_open(void)
{
    int fd = sys_open("/dev/uinput", O_WRONLY, 0);
    if (fd < 0) return fd;
    sys_ioctl(fd, UI_SET_EVBIT, 2 /* EV_REL */);
    sys_ioctl(fd, UI_SET_RELBIT, 0 /* REL_X */);
    sys_ioctl(fd, UI_SET_RELBIT, 1 /* REL_Y */);
    return ui_create(fd, "joyd mouse", 2);
}
static void emit_to(int fd, int type, int code, int val)
{
    struct { u32 s, us; u16 t, c; s32 v; } e;
    if (fd < 0) return;
    e.s = 0; e.us = 0; e.t = (u16)type; e.c = (u16)code; e.v = val;
    sys_write(fd, &e, sizeof e);
}
static void emit(int type, int code, int val) { emit_to(ui_fd, type, code, val); }

/* ---------------- estado e mapa ---------------- */
#define KEY_UP 103
#define KEY_DOWN 108
#define KEY_LEFT 105
#define KEY_RIGHT 106
#define NKEYS 512
static u8 keydown[NKEYS];
static int keymap[MAXBTN];
static u8 d_axis[4], d_hat[4], d_dp[4], bstate[MAXBTN];   /* dirs: 0 cima,1 baixo,2 esq,3 dir */

/* Codigos Linux. Os BTN_* viram BUTTON_* do Android pelo arquivo
 * /system/usr/keylayout/joyd_USB_gamepad.kl (o qwerty.kl deste aparelho nao tem gamepad).
 * O My Boy! ja vem mapeado para BUTTON_A/B/L1/R1/SELECT/START + setas + MENU. */
#define BTN_A 304
#define BTN_B 305
#define BTN_X 307
#define BTN_Y 308
#define BTN_Z 309
#define BTN_TL 310
#define BTN_TR 311
#define BTN_TL2 312
#define BTN_TR2 313
#define BTN_SELECT 314
#define BTN_START 315
#define BTN_THUMBL 317
#define BTN_THUMBR 318
#define KEY_BACK 158
#define KEY_MENU 139
#define KEY_HOME 102
/* No .kl: BTN_A -> DPAD_CENTER (clica em qualquer tela do Android) e BTN_B -> BACK.
 * HID generico (DragonRise estilo PlayStation, palpite): 1 cima, 2 direita, 3 baixo, 4 esquerda,
 * 5 L1, 6 R1, 7 L2, 8 R2, 9 select, 10 start, 11 L3, 12 R3 */
static const int defmap_hid[MAXBTN] = {BTN_Y, BTN_B, BTN_A, BTN_X, BTN_TL, BTN_TR, KEY_MENU, BTN_TR2,
                                       BTN_SELECT, BTN_START, KEY_HOME, BTN_THUMBR};
/* X-input, na ordem:  A   B   X   Y   LB  RB  LT  RT  BACK  START L3  R3  GUIDE(->HOME = volta ao fliperama) */
#define NXBTN 13
static const char *const xnames[NXBTN] = {"A", "B", "X", "Y", "LB", "RB", "LT", "RT", "BACK", "START", "L3", "R3", "GUIDE"};
static const int defmap_x[MAXBTN] = {BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR, KEY_MENU, BTN_TR2,
                                     BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR, KEY_HOME};

static void load_map(int type)
{
    const char *fn = type == T_XINPUT ? "/data/local/joyd-xbox.map" : "/data/local/joyd.map";
    const int *def = type == T_XINPUT ? defmap_x : defmap_hid;
    char b[1024]; int fd, n, i, idx = 0, cur = -1, comment = 0;
    for (i = 0; i < MAXBTN; i++) keymap[i] = def[i];
    fd = sys_open(fn, O_RDONLY, 0);
    if (fd < 0) return;
    n = sys_read(fd, b, sizeof b - 1);
    sys_close(fd);
    if (n <= 0) return;
    b[n] = 0;
    for (i = 0; i <= n && idx < MAXBTN; i++) {
        char c = b[i];
        if (comment) { if (c == '\n') comment = 0; continue; }
        if (c == '#') { comment = 1; }
        if (c >= '0' && c <= '9') { if (cur < 0) cur = 0; cur = cur * 10 + (c - '0'); }
        else if (cur >= 0) { keymap[idx++] = cur; cur = -1; }
    }
    put("mapa carregado de "); put(fn); put(" (");
    putdec(idx); put(" botoes)\n");
}

static int fok(const struct field *f, int id) { return f->present && f->id == id; }

/* Converte o estado atual (direcoes + botoes) em teclas apertadas/soltas.
 * Guarda em last_changed se alguma tecla mudou (o serve() usa para economizar CPU). */
static int last_changed;
/* Memoria dividida entre o processo principal e os filhos de cada controle:
 * [0] modo jogo (RetroArch, Xash3D): B vira BUTTON_Z e LT vira L2, em vez de "voltar" e "menu" do Android
 * [1] mira ligada: o analogico direito move o mouse virtual (so no Xash3D)
 * [2] [3] posicao do analogico direito, x e y, de -127 a 127 (positivo = direita, baixo) */
static volatile int *shared;

static void set_look(int x, int y)
{
    if (shared) { shared[2] = x; shared[3] = y; }
}

/* Analogico direito: a maioria dos controles HID usa Z e Rz; quem nao tem Rz costuma usar Rx e Ry. */
static void look_fields(const struct joy *j, const struct field **fx, const struct field **fy)
{
    *fx = j->rz.present ? &j->z : &j->rx;
    *fy = j->rz.present ? &j->rz : &j->ry;
}

/* Valor do eixo para -127..127 em torno do centro, sem divisao (esse binario nao tem libgcc). */
static int axis_norm(const struct field *f, int v)
{
    int rg = f->max - f->min, c = f->min + (rg >> 1), dif = v - c, s = 0;
    while ((rg >> s) > 255) s++;
    dif >>= s;
    rg >>= s;
    while (rg > 0 && rg < 128) { rg <<= 1; dif <<= 1; }
    return dif > 127 ? 127 : (dif < -127 ? -127 : dif);
}

/* Zona morta e curva: bem devagar perto do centro para mirar, rapido no fim do curso. */
static int look_curve(int v)
{
    int neg = v < 0, a = (neg ? -v : v) - 24;
    if (a <= 0) return 0;
    a = 1 + ((a * a) >> 8);
    return neg ? -a : a;
}

/* Processo proprio: enquanto o analogico estiver inclinado, manda movimento ~60 vezes por segundo.
 * O controle so manda pacote quando algo muda, entao nao da para fazer isso no laco de leitura. */
static void look_pump(void)
{
    for (;;) {
        int dx, dy;
        if (!shared[1]) { sleep_ms(400); continue; }
        dx = look_curve(shared[2]);
        dy = look_curve(shared[3]);
        if (!dx && !dy) { sleep_ms(30); continue; }
        if (dx) emit_to(uim_fd, 2, 0, dx);
        if (dy) emit_to(uim_fd, 2, 1, dy);
        emit_to(uim_fd, 0, 0, 0);
        sleep_ms(16);
    }
}

static void apply_keys(int nbtn)
{
    u8 want[NKEYS]; int i, k, ch = 0;
    memset(want, 0, sizeof want);
    want[KEY_UP] = d_axis[0] | d_hat[0] | d_dp[0];
    want[KEY_DOWN] = d_axis[1] | d_hat[1] | d_dp[1];
    want[KEY_LEFT] = d_axis[2] | d_hat[2] | d_dp[2];
    want[KEY_RIGHT] = d_axis[3] | d_hat[3] | d_dp[3];
    for (i = 0; i < nbtn && i < MAXBTN; i++) {
        k = keymap[i];
        if (shared && shared[0]) {
            if (k == BTN_B) k = BTN_Z;
            else if (k == KEY_MENU) k = BTN_TL2;
        }
        /* No Half-Life o Start e o ESC: abre o menu e, dentro dele, volta (menu do motor nao usa os binds) */
        if (shared && shared[1] && k == BTN_START) k = KEY_BACK;
        if (bstate[i] && k > 0 && k < NKEYS) want[k] = 1;
    }
    /* Select + Start (botoes 9 e 10, ou BACK e START no X-input): volta ao fliperama em qualquer controle e emulador */
    if (nbtn > 9 && bstate[8] && bstate[9]) {
        want[KEY_HOME] = 1;
        want[BTN_SELECT] = 0;
        want[BTN_START] = 0;
        if (shared && shared[1]) want[KEY_BACK] = 0;
    }
    for (k = 1; k < NKEYS; k++) if (want[k] != keydown[k]) { keydown[k] = want[k]; emit(1, k, want[k]); ch = 1; }
    if (ch) emit(0, 0, 0);
    last_changed = ch;
}

/* Pacote de estado X-input (Xbox 360): [0]=0x00 tipo, [1]=0x14 tamanho,
 * [2] cima baixo esq dir START BACK L3 R3, [3] LB RB GUIDE - A B X Y,
 * [4] LT, [5] RT, [6..7] LX, [8..9] LY (positivo = cima). */
static void handle_x(const u8 *buf, int n)
{
    s16 lx, ly; u8 b2, b3;
    if (n < 14 || buf[0] != 0x00 || buf[1] < 0x0e) return;   /* outros tipos: LED, rumble... */
    b2 = buf[2]; b3 = buf[3];
    d_dp[0] = b2 & 1; d_dp[1] = (b2 >> 1) & 1; d_dp[2] = (b2 >> 2) & 1; d_dp[3] = (b2 >> 3) & 1;
    lx = (s16)(buf[6] | buf[7] << 8); ly = (s16)(buf[8] | buf[9] << 8);
    d_axis[2] = lx < -16000; d_axis[3] = lx > 16000;
    d_axis[0] = ly > 16000;  d_axis[1] = ly < -16000;
    set_look((s16)(buf[10] | buf[11] << 8) >> 8, -((s16)(buf[12] | buf[13] << 8) >> 8));
    bstate[0] = (b3 >> 4) & 1;  /* A */
    bstate[1] = (b3 >> 5) & 1;  /* B */
    bstate[2] = (b3 >> 6) & 1;  /* X */
    bstate[3] = (b3 >> 7) & 1;  /* Y */
    bstate[4] = b3 & 1;         /* LB */
    bstate[5] = (b3 >> 1) & 1;  /* RB */
    bstate[6] = buf[4] > 100;   /* LT (gatilho analogico) */
    bstate[7] = buf[5] > 100;   /* RT */
    bstate[8] = (b2 >> 5) & 1;  /* BACK */
    bstate[9] = (b2 >> 4) & 1;  /* START */
    bstate[10] = (b2 >> 6) & 1; /* L3 */
    bstate[11] = (b2 >> 7) & 1; /* R3 */
    bstate[12] = (b3 >> 2) & 1; /* GUIDE (botao central) */
    apply_keys(NXBTN);
}

static void handle_hid(const struct joy *j, const u8 *buf, int n)
{
    const u8 *r = buf; int len = n, id = 0, i;
    if (j->uses_ids) { if (n < 1) return; id = buf[0]; r = buf + 1; len = n - 1; }
    if (fok(&j->x, id)) {
        int v = rdf(r, len, &j->x), q = (j->x.max - j->x.min) >> 2;
        d_axis[2] = v <= j->x.min + q; d_axis[3] = v >= j->x.max - q;
    }
    if (fok(&j->y, id)) {
        int v = rdf(r, len, &j->y), q = (j->y.max - j->y.min) >> 2;
        d_axis[0] = v <= j->y.min + q; d_axis[1] = v >= j->y.max - q;
    }
    if (fok(&j->hat, id)) {
        int v = rdf(r, len, &j->hat) - j->hat.min, rg = j->hat.max - j->hat.min;
        d_hat[0] = d_hat[1] = d_hat[2] = d_hat[3] = 0;
        if (rg == 7 && v >= 0 && v <= 7) {
            d_hat[0] = v == 7 || v == 0 || v == 1;
            d_hat[3] = v >= 1 && v <= 3;
            d_hat[1] = v >= 3 && v <= 5;
            d_hat[2] = v >= 5 && v <= 7;
        } else if (rg == 3 && v >= 0 && v <= 3) {
            d_hat[0] = v == 0; d_hat[3] = v == 1; d_hat[1] = v == 2; d_hat[2] = v == 3;
        }
    }
    {
        /* Eixo que nao repousa no centro e gatilho analogico, nao analogico direito: fica sem mira. */
        static int look_state;    /* 0 = ainda nao viu, 1 = serve, 2 = nao serve */
        const struct field *fx, *fy;
        look_fields(j, &fx, &fy);
        if (fok(fx, id) && fok(fy, id)) {
            int x = axis_norm(fx, rdf(r, len, fx)), y = axis_norm(fy, rdf(r, len, fy));
            if (!look_state) look_state = (x > -64 && x < 64 && y > -64 && y < 64) ? 1 : 2;
            if (look_state == 1) set_look(x, y);
        }
    }
    /* usages 0x90..0x93 = cima, baixo, direita, esquerda */
    if (fok(&j->dp[0], id)) d_dp[0] = rdf(r, len, &j->dp[0]) != 0;
    if (fok(&j->dp[1], id)) d_dp[1] = rdf(r, len, &j->dp[1]) != 0;
    if (fok(&j->dp[2], id)) d_dp[3] = rdf(r, len, &j->dp[2]) != 0;
    if (fok(&j->dp[3], id)) d_dp[2] = rdf(r, len, &j->dp[3]) != 0;
    for (i = 0; i < j->nbtn; i++)
        if (fok(&j->btn[i], id)) bstate[i] = rdf(r, len, &j->btn[i]) != 0;
    apply_keys(j->nbtn);
}

static void handle(const struct dev *d, const struct joy *j, const u8 *buf, int n)
{
    if (d->type == T_XINPUT) handle_x(buf, n);
    else handle_hid(j, buf, n);
}
static void release_all(void)
{
    int k, ch = 0;
    for (k = 1; k < NKEYS; k++) if (keydown[k]) { keydown[k] = 0; emit(1, k, 0); ch = 1; }
    if (ch) emit(0, 0, 0);
    memset(d_axis, 0, 4); memset(d_hat, 0, 4); memset(d_dp, 0, 4); memset(bstate, 0, sizeof bstate);
    set_look(0, 0);
}

/* ---------------- conexao com o controle ---------------- */
static u8 rdesc[1024];
static int rdesc_len;

static int usb_write(struct dev *d, const u8 *buf, int n)
{
    struct usb_bulk b;
    b.ep = (u32)d->ep_out; b.len = (u32)n; b.timeout = 500; b.data = (void *)buf;
    return sys_ioctl(d->fd, USBDEVFS_BULK, (long)&b);
}

static int attach(struct dev *d, struct joy *j)
{
    int ifno = d->ifno, len = d->rdlen, r;
    usb_drv(d->fd, ifno, USBDEVFS_DISCONNECT);       /* tira o driver do kernel (pode nao ter) */
    r = sys_ioctl(d->fd, USBDEVFS_CLAIMINTERFACE, (long)&ifno);
    if (r < 0) return r;
    if (d->type == T_XINPUT) {
        static const u8 led[3] = {0x01, 0x03, 0x02};   /* LED "jogador 1" */
        memset(j, 0, sizeof *j); j->nbtn = NXBTN; j->is_joy = 1;
        if (d->ep_out) usb_write(d, led, 3);
        rdesc_len = 0;
        return 0;
    }
    if (len <= 0 || len > (int)sizeof rdesc) len = sizeof rdesc;
    r = usb_ctrl(d->fd, 0x81, 6 /*GET_DESCRIPTOR*/, 0x2200, ifno, rdesc, len);
    if (r <= 0) r = usb_ctrl(d->fd, 0x81, 6, 0x2200, ifno, rdesc, len);
    if (r <= 0) return -1;
    rdesc_len = r;
    hid_parse(rdesc, rdesc_len, j);
    usb_ctrl(d->fd, 0x21, 0x0A /*SET_IDLE*/, 0, ifno, 0, 0);   /* so mandar quando mudar */
    if (!j->is_joy && j->nbtn == 0) return -2;
    return 0;
}
static void detach(struct dev *d, int give_back)
{
    int ifno = d->ifno;
    sys_ioctl(d->fd, USBDEVFS_RELEASEINTERFACE, (long)&ifno);
    if (give_back) usb_drv(d->fd, ifno, USBDEVFS_CONNECT);
    sys_close(d->fd);
}
static int usb_read(struct dev *d, u8 *buf, int timeout_ms)
{
    struct usb_bulk b;
    b.ep = (u32)d->ep; b.len = (u32)d->maxp; b.timeout = (u32)timeout_ms; b.data = buf;
    return sys_ioctl(d->fd, USBDEVFS_BULK, (long)&b);
}

static void put_dev(const struct dev *d)
{
    put("usb "); putdec(d->bus); put("-"); putdec(d->addr);
    put(" id "); puthex(d->vid, 4); put(":"); puthex(d->pid, 4);
    put(" if "); putdec(d->ifno); put(" ep "); puthex((u32)d->ep, 2);
    put(" pacote "); putdec(d->maxp);
    put(d->type == T_XINPUT ? " tipo X-input\n" : " tipo HID\n");
}
static void put_field(const char *nm, const struct field *f)
{
    if (!f->present) return;
    put("  "); put(nm); put(": id "); putdec(f->id); put(" bit "); putdec(f->off);
    put(" tam "); putdec(f->size); put(" faixa "); putdec(f->min); put(".."); putdec(f->max); put("\n");
}

static int rej[64], nrej;
static int rejected(int bus, int addr)
{
    int i, key = bus << 8 | addr;
    for (i = 0; i < nrej; i++) if (rej[i] == key) return 1;
    return 0;
}
static void reject(int bus, int addr)
{
    if (nrej >= 64) nrej = 0;
    rej[nrej++] = bus << 8 | addr;
}
/* controles em uso: cada um e atendido por um processo filho */
#define MAXCTL 4
static int act_key[MAXCTL], act_pid[MAXCTL];
static int active(int bus, int addr)
{
    int i, key = bus << 8 | addr;
    for (i = 0; i < MAXCTL; i++) if (act_pid[i] > 0 && act_key[i] == key) return 1;
    return 0;
}
static int n_active(void)
{
    int i, n = 0;
    for (i = 0; i < MAXCTL; i++) if (act_pid[i] > 0) n++;
    return n;
}
static void add_active(int bus, int addr, int pid)
{
    int i;
    for (i = 0; i < MAXCTL; i++) if (act_pid[i] <= 0) { act_pid[i] = pid; act_key[i] = bus << 8 | addr; return; }
}
static void reap(void)
{
    int st, i; long pid;
    while ((pid = sc4(NR_wait4, -1, (long)&st, 1 /*WNOHANG*/, 0)) > 0)
        for (i = 0; i < MAXCTL; i++) if (act_pid[i] == pid) act_pid[i] = 0;
}

static int want_type = -1;   /* -1 = qualquer; T_HID / T_XINPUT = so esse tipo */
static int find(struct dev *d)
{
    int bus, a;
    for (bus = 1; bus <= 2; bus++)
        for (a = 1; a < 128; a++)
            if (!rejected(bus, a) && !active(bus, a) && probe(bus, a, d)) {
                if (want_type < 0 || d->type == want_type) return 1;
                sys_close(d->fd);
            }
    return 0;
}

/* ---------------- modos ---------------- */
static int do_dump(int secs)
{
    struct dev d; static struct joy j;
    u8 buf[64], last[64]; int lastn = -1, i, r;
    long t0 = now_s();
    int waiting = 0;
    for (;;) {
        if (!find(&d)) {
            if (now_s() - t0 >= secs) { put("nenhum controle encontrado\n"); return 1; }
            if (!waiting) { put("esperando o controle ser conectado...\n"); waiting = 1; }
            sleep_ms(1000);
            continue;
        }
        put("candidato: "); put_dev(&d);
        r = attach(&d, &j);
        if (r == 0) break;
        put("  descartado (r="); putdec(r); put(")\n");
        detach(&d, 1); reject(d.bus, d.addr);
    }
    load_map(d.type);
    if (d.type == T_HID) {
        put("report descriptor ("); putdec(rdesc_len); put(" bytes):\n");
        for (i = 0; i < rdesc_len; i++) { puthex(rdesc[i], 2); put((i & 15) == 15 ? "\n" : " "); }
        put("\njoystick/gamepad: "); put(j.is_joy ? "sim" : "nao"); put(j.uses_ids ? " (com report id)\n" : "\n");
        put_field("X", &j.x); put_field("Y", &j.y); put_field("hat", &j.hat);
        put_field("dpad-cima", &j.dp[0]); put_field("dpad-baixo", &j.dp[1]);
        put_field("dpad-dir", &j.dp[2]); put_field("dpad-esq", &j.dp[3]);
    } else {
        put("X-input: LED de jogador 1 enviado (ep saida "); puthex((u32)d.ep_out, 2); put(")\n");
    }
    put("  botoes: "); putdec(j.nbtn); put("\n");
    put("=== aperte os botoes (");
    putdec(secs); put(" s) ===\n");
    t0 = now_s();
    while (now_s() - t0 < secs) {
        r = usb_read(&d, buf, 500);
        if (r == -110) continue;           /* timeout */
        if (r < 0) { put("erro leitura "); putdec(r); put("\n"); break; }
        if (r == lastn) {
            for (i = 0; i < r && buf[i] == last[i]; i++)
                ;
            if (i == r) continue;
        }
        memcpy(last, buf, r); lastn = r;
        handle(&d, &j, buf, r);
        for (i = 0; i < r && i < 14; i++) { puthex(buf[i], 2); put(" "); }
        put(" |");
        if (keydown[KEY_UP]) put(" CIMA");
        if (keydown[KEY_DOWN]) put(" BAIXO");
        if (keydown[KEY_LEFT]) put(" ESQ");
        if (keydown[KEY_RIGHT]) put(" DIR");
        for (i = 0; i < j.nbtn; i++)
            if (bstate[i]) {
                put(" ");
                if (d.type == T_XINPUT && i < NXBTN) put(xnames[i]);
                else { put("B"); putdec(i + 1); }
            }
        put("\n");
    }
    detach(&d, 1);
    put("fim\n");
    return 0;
}

/* Processo filho: atende um controle ate ele ser desconectado. */
static void serve(struct dev *d, const struct joy *j)
{
    u8 buf[64]; int r;
    load_map(d->type);
    put("controle conectado: "); put_dev(d);
    if (d->type == T_HID) {
        const struct field *fx, *fy;
        look_fields(j, &fx, &fy);
        put_field("analogico direito X", fx);
        put_field("analogico direito Y", fy);
    }
    for (;;) {
        r = usb_read(d, buf, 1000);
        if (r == -110) continue;
        if (r < 0) break;
        last_changed = 0;
        handle(d, j, buf, r);
        /* Muitos controles mandam pacote sem parar (100-250/s, ruido do analogico).
         * Se nada mudou, espera um pouco antes de pedir o proximo: menos CPU,
         * e no maximo ~1 quadro de atraso. Quando muda, le de novo na hora. */
        if (!last_changed) sleep_ms(8);
    }
    release_all();
    detach(d, 0);
    put("controle desconectado: usb "); putdec(d->bus); put("-"); putdec(d->addr);
    put(" (erro "); putdec(r); put(")\n");
}

/* Fecha tudo que foi herdado de quem nos iniciou (adb, init...), menos 0-2. */
static void close_inherited(void)
{
    int fd;
    for (fd = 3; fd < 1024; fd++) sys_close(fd);
}

/* ---------------- comandos do fliperama (o app nao e root; o joyd e) ---------------- */
#define CMD_FILE "/mnt/sdcard/roms/.joyd_cmd"
static char **g_envp;

static void run_sh(const char *cmd)
{
    int pid = (int)sc(NR_fork, 0, 0, 0);
    if (pid == 0) {
        const char *argv[4];
        close_inherited();
        sc(NR_setsid, 0, 0, 0);
        argv[0] = "sh"; argv[1] = "-c"; argv[2] = cmd; argv[3] = 0;
        sc(NR_execve, (long)"/system/bin/sh", (long)argv, (long)g_envp);
        sys_exit(127);
    }
}

static int starts(const char *s, const char *p) { while (*p) if (*s++ != *p++) return 0; return 1; }

static char *cat(char *o, const char *s) { while (*s) *o++ = *s++; *o = 0; return o; }

/* Lista fechada de comandos; caminho de ROM nao pode ter nada que o shell interprete. */
static void check_cmd(void)
{
    static char b[600], c[1000];
    int fd = sys_open(CMD_FILE, O_RDONLY, 0), n, i;
    long r_alarm = 0;
    char *o;
    if (fd < 0) return;
    n = sys_read(fd, b, sizeof b - 1);
    sys_close(fd);
    sc(NR_unlink, (long)CMD_FILE, 0, 0);
    if (n <= 0) return;
    b[n] = 0;
    for (i = 0; i < n; i++) if (b[i] == '\n' || b[i] == '\r') { b[i] = 0; break; }
    put("comando: "); put(b); put("\n");
    if (starts(b, "game ")) {
        if (shared) { shared[0] = b[5] == '1'; shared[1] = 0; }
    } else if (starts(b, "xash")) {
        /* Half-Life: B e LT livres para o jogo, analogico direito mira. Quem abre e /data/local/xash.sh */
        if (shared) { shared[0] = 1; shared[1] = 1; }
        run_sh("sh /data/local/xash.sh");
    } else if (starts(b, "n64 ")) {
        /* n64 <caminho da rom>: Mupen64Plus AE (/data/local/n64.sh). B vira Z e LT vira L2, como no RetroArch */
        char *rom = b + 4;
        if (!rom[0]) { put("comando incompleto\n"); return; }
        for (i = 0; rom[i]; i++)
            if (rom[i] == '"' || rom[i] == '\\' || rom[i] == '$' || rom[i] == '`' || rom[i] == '<' || rom[i] == '>' || rom[i] == '&') {
                put("caminho recusado\n");
                return;
            }
        if (shared) { shared[0] = 1; shared[1] = 0; }
        o = cat(c, "sh /data/local/n64.sh \"");
        o = cat(o, rom);
        cat(o, "\"");
        run_sh(c);
    } else if (starts(b, "gpu ")) {
        int v = 0;
        for (i = 4; b[i] >= '0' && b[i] <= '9'; i++) v = v * 10 + (b[i] - '0');
        if (b[i] == 0 && v >= 3 && v <= 16) {
            o = cat(c, "/system/xbin/oc gpu ");
            o = cat(o, b + 4);
            o = cat(o, "; echo ");
            o = cat(o, b + 4);
            cat(o, " > /data/local/gpu.div");
            run_sh(c);
        }
    } else if (starts(b, "ejeta")) {
        /* desmonta o pen drive pelo vold (o mesmo que o Android usa): sem isso o kernel registra erros FAT ao tirar */
        run_sh("sync; /system/bin/vdc volume unmount /mnt/usb force");
    } else if (starts(b, "reboot")) {
        run_sh("sync; reboot");
    } else if (starts(b, "hora ")) {
        /* acerta o relogio (segundos desde 1970), vindo do fliperama por NTP. /dev/alarm grava tambem o relogio da placa */
        long t = 0, ts[2];
        int al;
        for (i = 5; b[i] >= '0' && b[i] <= '9'; i++) t = t * 10 + (b[i] - '0');
        if (b[i] != 0 || t < 1700000000L) return;
        ts[0] = t; ts[1] = 0;
        al = sys_open("/dev/alarm", 2 /*O_RDWR*/, 0);
        if (al >= 0) {
            r_alarm = sc(NR_ioctl, al, 0x40086105 /*ANDROID_ALARM_SET_RTC*/, (long)ts);
            sys_close(al);
        }
        if (al < 0 || r_alarm < 0) sc(79 /*settimeofday*/, (long)ts, 0, 0);
    } else if (starts(b, "ra ")) {
        /* ra <nucleo> <caminho da rom>. "psx" e o nucleo que vem no RetroArch; os outros ficam em /data/local/cores */
        char *a = b + 3, *rom;
        int nc;
        for (nc = 0; a[nc] && a[nc] != ' '; nc++)
            if (!((a[nc] >= 'a' && a[nc] <= 'z') || (a[nc] >= '0' && a[nc] <= '9') || a[nc] == '_')) { put("nucleo recusado\n"); return; }
        if (nc == 0 || a[nc] != ' ') { put("comando incompleto\n"); return; }
        rom = a + nc + 1;
        for (i = 0; rom[i]; i++)
            if (rom[i] == '"' || rom[i] == '\\' || rom[i] == '$' || rom[i] == '`') { put("caminho recusado\n"); return; }
        if (shared) shared[0] = 1;
        /* Sempre abre do zero, mesmo que seja o jogo que ficou em segundo plano: este RetroArch (0.9.9.6) volta do
           segundo plano sem receber nenhum botao (testado em 10/11/2026). O save do jogo vai para o cartao a cada 10 s
           (autosave_interval), entao so se perde o ponto exato. Sem matar o processo e sem CLEAR_TOP o Android so
           traria de volta o jogo que ja estava aberto (ignora os extras). */
        o = cat(c, "ps | while read u p r; do case \"$r\" in *org.retroarch) kill -9 $p;; esac; done; sleep 1; "
                   "am start -f 0x04000000 -n org.retroarch/.browser.RetroActivity -e ROM \"");
        a[nc] = 0;
        o = cat(o, rom);
        o = cat(o, "\" -e LIBRETRO ");
        if (nc == 3 && a[0] == 'p' && a[1] == 's' && a[2] == 'x') {
            o = cat(o, "/data/data/org.retroarch/lib/libretro_pcsx_rearmed-neon.so");
        } else {
            o = cat(o, "/data/local/cores/");
            o = cat(o, a);
            o = cat(o, ".so");
        }
        cat(o, " -e CONFIGFILE /mnt/sdcard/roms/.retroarch.cfg -e IME com.android.inputmethod.latin/.LatinIME");
        run_sh(c);
    }
}

/* Cabo USB no PC: o cartao vira pen drive; tirou o cabo, volta para o fliperama (/data/local/usb.sh).
 * Com jogo aberto espera: desmontar o cartao derrubaria o jogo. Ver leituras iguais evita agir em falso contato. */
static void check_usb(void)
{
    static int last, same, applied;
    char b[4];
    int fd = sys_open("/sys/class/switch/usb_configuration/state", O_RDONLY, 0), n, on;
    if (fd < 0) return;
    n = sys_read(fd, b, sizeof b);
    sys_close(fd);
    on = n > 0 && b[0] == '1';
    if (on != last) { last = on; same = 0; return; }
    if (++same < 3 || on == applied) return;
    if (on && shared && shared[0]) return;
    applied = on;
    put(on ? "cabo USB ligado\n" : "cabo USB desligado\n");
    run_sh(on ? "sh /data/local/usb.sh liga" : "sh /data/local/usb.sh desliga");
}

static int do_run(void)
{
    struct dev d; static struct joy j;
    int r, pid, tick = 0;
    long m;
    ui_fd = ui_open();
    if (ui_fd < 0) { put("erro abrindo /dev/uinput: "); putdec(ui_fd); put("\n"); return 1; }
    m = sc6(NR_mmap2, 0, 4096, 3 /*READ|WRITE*/, 0x21 /*SHARED|ANON*/, -1, 0);
    if (m < 0 && m > -4096) put("sem memoria dividida\n");
    else shared = (volatile int *)m;
    uim_fd = uim_open();
    if (uim_fd < 0) { put("sem mouse virtual: "); putdec(uim_fd); put("\n"); }
    else if (shared && sc(NR_fork, 0, 0, 0) == 0) { look_pump(); sys_exit(0); }
    put("teclado virtual criado; esperando controles...\n");
    for (;;) {
        reap();
        check_cmd();
        if ((tick & 3) == 0) check_usb();
        /* comandos a cada 300 ms; procura controle novo a cada ~1,2 s */
        if (n_active() >= MAXCTL || (++tick & 3) != 0 || !find(&d)) { sleep_ms(300); continue; }
        r = attach(&d, &j);
        if (r < 0) {
            put("ignorando "); put_dev(&d);
            detach(&d, 1); reject(d.bus, d.addr);
            continue;
        }
        pid = (int)sc(NR_fork, 0, 0, 0);
        if (pid == 0) { serve(&d, &j); sys_exit(0); }
        if (pid < 0) { put("fork falhou\n"); detach(&d, 0); sleep_ms(1000); continue; }
        sys_close(d.fd);              /* o filho segue com a interface reservada */
        add_active(d.bus, d.addr, pid);
    }
}

static int single_instance(void)
{
    struct { u16 fam; char path[6]; } a;
    int fd = (int)sc(NR_socket, 1 /*AF_UNIX*/, 1 /*SOCK_STREAM*/, 0);
    if (fd < 0) return 1;              /* sem socket: segue assim mesmo */
    a.fam = 1; a.path[0] = 0; a.path[1] = 'j'; a.path[2] = 'o'; a.path[3] = 'y'; a.path[4] = 'd'; a.path[5] = 0;
    return sc(NR_bind, fd, (long)&a, 2 + 5) == 0;   /* fd fica aberto de proposito */
}

static int streq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
static int atoi_s(const char *s) { int v = 0; while (*s >= '0' && *s <= '9') v = v * 10 + (*s++ - '0'); return v; }

__attribute__((used)) int cmain(long *sp)
{
    int argc = (int)sp[0];
    char **argv = (char **)(sp + 1);
    g_envp = argv + argc + 1;
    if (argc >= 2 && streq(argv[1], "dump")) {
        int s = argc >= 3 ? atoi_s(argv[2]) : 30;
        if (argc >= 4 && argv[3][0] == 'x') want_type = T_XINPUT;
        if (argc >= 4 && argv[3][0] == 'h') want_type = T_HID;
        sys_exit(do_dump(s > 0 ? s : 30));
    }
    if (argc >= 2 && streq(argv[1], "list")) { list_usb(); sys_exit(0); }
    if (argc >= 2 && (streq(argv[1], "run") || streq(argv[1], "-d"))) {
        int daemon = streq(argv[1], "-d");
        if (daemon) close_inherited();
        if (!single_instance()) { put("joyd ja esta rodando\n"); sys_exit(0); }
        if (daemon) {
            int pid = (int)sc(NR_fork, 0, 0, 0), lf, nf;
            if (pid < 0) sys_exit(1);
            if (pid > 0) sys_exit(0);
            sc(NR_setsid, 0, 0, 0);
            lf = sys_open("/data/local/tmp/joyd.log", O_WRONLY | O_CREAT | O_TRUNC, 0644);
            if (lf >= 0) { sc(NR_dup2, lf, 1, 0); sc(NR_dup2, lf, 2, 0); if (lf > 2) sys_close(lf); }
            nf = sys_open("/dev/null", O_RDWR, 0);
            if (nf >= 0) { sc(NR_dup2, nf, 0, 0); if (nf > 2) sys_close(nf); }
        }
        sys_exit(do_run());
    }
    put("uso: joyd list | joyd dump [segundos] [x|h] | joyd run | joyd -d\n");
    sys_exit(2);
    return 0;
}

__attribute__((naked, noreturn, used)) void _start(void)
{
    __asm__ volatile(
        "mov r0, sp\n"
        "bic sp, sp, #7\n"
        "bl cmain\n"
        "mov r7, #1\n"
        "svc #0\n");
}
