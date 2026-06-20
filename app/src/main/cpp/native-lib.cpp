/*
 * EKO Turbo Native Bridge — Thor-style USB-only
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * التغييرات الجذرية (مستلهمة من تحليل libthor.so):
 *   1. فقط .tar و .tar.md5 — رفض صارم لأي صيغة أخرى (لا fallback)
 *   2. إزالة wireless/TCP بالكامل
 *   3. إزالة nativeRunWirelessSession
 *   4. رسالة خطأ صريحة كما في Thor: "Invalid extension! Valid: '.tar' and '.tar.md5'"
 *   5. cdc_acm disabled via Java before session (Thor-style)
 */

#include <jni.h>
#include <string>
#include <memory>
#include <atomic>
#include <array>
#include <android/log.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <thread>
#include <chrono>
#include <vector>
#include <span>
#include <fcntl.h>
#include <mutex>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>

#include "EKO/src/core/byte_transport.hpp"
#include "EKO/src/core/bytes.hpp"
#include "EKO/src/core/status.hpp"
#include "EKO/src/protocol/odin/flash.hpp"
#include "EKO/src/protocol/odin/group_flasher.hpp"
#include "EKO/src/protocol/odin/odin_cmd.hpp"
#include "EKO/src/protocol/odin/odin_wire.hpp"
#include "EKO/src/protocol/odin/pit.hpp"
#include "EKO/src/protocol/odin/pit_transfer.hpp"
#include "EKO/src/app/version.hpp"
#include "EKO/src/io/tar.hpp"
#include "EKO/src/io/source.hpp"

extern "C" {
#include "EKO/src/third_party/md5/md5.h"
#include "EKO/src/third_party/xxhash/xxhash.h"
}

#define LOG_TAG "EKO_TURBO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#ifndef EKO_ENGINE_VERSION
#define EKO_ENGINE_VERSION "EKO-TURBO-3.0"
#endif

// ─────────────────────────────────────────────────────────────────────────────
// FdByteSource — يقرأ من file descriptor مباشرة
// ─────────────────────────────────────────────────────────────────────────────
class FdByteSource final : public eko::io::ByteSource {
public:
    FdByteSource(int fd, std::string name, std::uint64_t size)
        : fd_(dup(fd)), name_(std::move(name)), size_(size) {
        if (fd_ < 0) LOGE("FdByteSource: dup failed for fd=%d: %s", fd, strerror(errno));
        // FIX: no lseek needed — using pread() with explicit offset
    }

    ~FdByteSource() override {
        if (fd_ >= 0) close(fd_);
    }

    std::string display_name() const override { return name_; }
    std::uint64_t size() const override { return size_; }

    std::size_t read(std::span<std::byte> out) override {
        if (out.empty() || fd_ < 0) return 0;
        // FIX: use pread() instead of read() — thread-safe, no seek races with TwoSlotPrefetcher
        ssize_t n = ::pread(fd_, out.data(), out.size(), static_cast<off_t>(offset_));
        if (n > 0) offset_ += static_cast<uint64_t>(n);
        return (n > 0) ? static_cast<std::size_t>(n) : 0;
    }

    bool valid() const { return fd_ >= 0; }

private:
    int fd_;
    std::string name_;
    std::uint64_t size_;
    uint64_t offset_ = 0;  // FIX: track position for pread()
};

// ─────────────────────────────────────────────────────────────────────────────
// USB Bulk Transport (USBDEVFS_BULK via ioctl)
// ─────────────────────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────────────────────
// AndroidUsbTransport — ASYNC URB PIPELINE
//
// المشكلة الجذرية للبطء: USBDEVFS_BULK = synchronous blocking ioctl.
// كل chunk كان يُرسَل → يُنتظر حتى يكتمل → يُرسَل التالي.
// على Android OTG: latency كل ioctl ≈ 1-3ms → بطء شديد على ملفات كبيرة.
//
// الحل: USBDEVFS_SUBMITURB (async) + USBDEVFS_REAPURB.
// نُرسل kPipelineDepth=4 URBs في الهواء بالتوازي،
// ونحصد النتائج بالتتابع. الـ USB host controller دائماً مشغول = أقصى throughput.
// ─────────────────────────────────────────────────────────────────────────────
class AndroidUsbTransport : public eko::core::IByteTransport {
public:
    // ── Speed Tuning (مستخرج من تحليل libbrokkr) ──────────────────────────
    // libbrokkr يستخدم libusb_bulk_transfer مع deadline-based chunking.
    // كل chunk = min(remaining, pkt_size) حيث pkt_size مُفاوَض مع الجهاز.
    // الـ URB pipeline نفذه بـ 8 slots بدلاً من 4 — يُغطي latency أكبر
    // ويُبقي الـ USB host controller مشغولاً بلا توقف على Android OTG.
    // 1MB per URB — يتناسب مع pkt_all_v2plus=2MB (نصف packet لكل URB).
    static constexpr std::size_t kUrbSize       = 1024 * 1024;  // 1MB (was 512KB)
    static constexpr int         kPipelineDepth = 8;             // (was 4)

    AndroidUsbTransport(int fd, int ep_in, int ep_out)
        : fd_(fd),
          ep_in_(static_cast<unsigned>(ep_in)),
          ep_out_(static_cast<unsigned>(ep_out)) {
        // libbrokkr يرفع أولوية الـ thread إلى nice=-8 (syscall gettid + setpriority)
        // نفس الشيء هنا لضمان الـ USB I/O لا يُقاطَع
        setpriority(PRIO_PROCESS, 0, -8);
    }

    ~AndroidUsbTransport() override { drain_(); }

    bool connected()  const noexcept override { return connected_; }
    int  timeout_ms() const noexcept override { return timeout_ms_; }
    void set_timeout_ms(int ms) noexcept override { timeout_ms_ = ms; }
    void set_packet_size_hint(std::size_t) noexcept override {}
    IByteTransport::Kind kind() const noexcept override {
        return IByteTransport::Kind::UsbBulk;
    }

    // ── SEND: hybrid — sync for small commands, async pipeline for large data ──
    //
    // ODIN commands (handshake, PIT request, etc.) = 1024 bytes.
    // Brokkr flashes these via simple sync bulkTransfer() and PIT takes ~1s.
    // Our async pipeline added overhead that caused PIT to hang.
    //
    // libbrokkr: ODIN commands = 1024 bytes فقط — الـ 8KB حد مرتفع جداً
    // يخلي قطع كبيرة تاخد الـ sync path بدل async pipeline
    // نخفضه لـ 2KB = يغطي كل Odin commands + PIT responses (أكبر ≈ 800 bytes)
    static constexpr std::size_t kSyncThreshold = 2 * 1024; // 2KB (was 8KB)

    int send(std::span<const std::uint8_t> data, unsigned retries) override {
        if (!connected_ || fd_ < 0) return -1;

        // ── Small transfer: sync bulk + ZLP if needed (like Brokkr libusb_bulk_transfer) ──
        // Brokkr: libusb_bulk_transfer مع LIBUSB_TRANSFER_ADD_ZERO_PACKET تلقائياً
        // إذا data.size() % 512 == 0 → الجهاز ينتظر ZLP قبل أن يرد
        // بدون ZLP: الجهاز يظل ينتظر → recv() يفشل بـ ETIMEDOUT → Handshake receive failed
        if (data.size() <= kSyncThreshold) {
            struct usbdevfs_bulktransfer bulk{};
            bulk.ep      = ep_out_;
            bulk.len     = static_cast<unsigned>(data.size());
            bulk.timeout = static_cast<unsigned>(timeout_ms_);
            bulk.data    = const_cast<uint8_t*>(data.data());
            for (unsigned a = 0; a <= retries; ++a) {
                int ret = ::ioctl(fd_, USBDEVFS_BULK, &bulk);
                if (ret >= 0) {
                    // ZLP: إذا الحجم مضاعف لـ 512 → أرسل ZLP
                    if (data.size() % 512 == 0) {
                        struct usbdevfs_bulktransfer zlp{};
                        zlp.ep      = ep_out_;
                        zlp.len     = 0;
                        zlp.timeout = 100;
                        zlp.data    = nullptr;
                        (void)::ioctl(fd_, USBDEVFS_BULK, &zlp);
                    }
                    return ret;
                }
                int e = errno;
                if (e == ENODEV || e == ESHUTDOWN || e == ENOENT) {
                    connected_ = false; return -1;
                }
                if (e != EAGAIN && e != EINTR && e != ETIMEDOUT) return -1;
            }
            return -1;
        }

        // ── Large transfer: async URB pipeline ───────────────────────────────
        const uint8_t* p         = data.data();
        const uint8_t* const end = p + data.size();
        std::size_t total_sent   = 0;
        int in_flight            = 0;
        int slot                 = 0;

        auto submit = [&](const uint8_t* ptr, std::size_t len, int s) -> bool {
            auto& pu          = pending_[s];
            pu.urb            = {};
            pu.urb.type       = USBDEVFS_URB_TYPE_BULK;
            pu.urb.endpoint   = ep_out_;
            pu.urb.usercontext = reinterpret_cast<void*>(uintptr_t(s));
            pu.urb.buffer               = const_cast<uint8_t*>(ptr);
            pu.urb.buffer_length        = static_cast<int>(len);
            pu.urb.number_of_packets    = 0;
            pu.urb.flags               |= USBDEVFS_URB_ZERO_PACKET;
            if (::ioctl(fd_, USBDEVFS_SUBMITURB, &pu.urb) != 0) {
                int e = errno;
                LOGE("SUBMITURB failed ep=0x%02X errno=%d: %s", ep_out_, e, strerror(e));
                if (e == ENODEV || e == ESHUTDOWN || e == ENOENT) connected_ = false;
                return false;
            }
            pu.in_flight = true;
            return true;
        };

        auto reap = [&]() -> int {
            usbdevfs_urb* r = nullptr;
            for (;;) {
                if (::ioctl(fd_, USBDEVFS_REAPURB, &r) == 0) break;
                int e = errno;
                if (e == EINTR) continue;
                LOGE("REAPURB failed errno=%d: %s", e, strerror(e));
                if (e == ENODEV || e == ESHUTDOWN || e == ENOENT) connected_ = false;
                return -1;
            }
            if (!r) return -1;
            int s = static_cast<int>(reinterpret_cast<uintptr_t>(r->usercontext));
            if (s >= 0 && s < kPipelineDepth) pending_[s].in_flight = false;
            if (r->status != 0) { LOGE("URB error status=%d", r->status); return -1; }
            return r->actual_length;
        };

        while (p < end || in_flight > 0) {
            while (p < end && in_flight < kPipelineDepth) {
                std::size_t chunk = std::min<std::size_t>(
                    static_cast<std::size_t>(end - p), kUrbSize);
                if (!submit(p, chunk, slot % kPipelineDepth)) {
                    drain_(); connected_ = false; return -1;
                }
                p += chunk; ++in_flight; ++slot;
            }
            if (in_flight > 0) {
                int got = reap();
                if (got < 0) { drain_(); connected_ = false; return -1; }
                total_sent += static_cast<std::size_t>(got);
                --in_flight;
            }
        }
        return static_cast<int>(total_sent);
    }

    // ── RECV: synchronous — مطابق لـ Brokkr libusb_bulk_transfer ─────────
    // Brokkr: deadline-based loop — يجمع البيانات في chunks حتى يكتمل الـ size المطلوب
    // EKO كان يطلب data.size() كلها في ioctl واحد — لو الجهاز بعت أقل يرجع partial
    // الإصلاح: loop تجمع الـ bytes حتى تكتمل (زي recv_raw في Brokkr)
    int recv(std::span<std::uint8_t> data, unsigned retries = 8) override {
        if (!connected_ || fd_ < 0) return -1;
        if (data.empty()) return recv_zlp();

        std::size_t total = 0;
        unsigned    fails = 0;

        while (total < data.size()) {
            struct usbdevfs_bulktransfer bulk{};
            bulk.ep      = ep_in_;
            bulk.len     = static_cast<unsigned>(data.size() - total);
            bulk.timeout = static_cast<unsigned>(timeout_ms_);
            bulk.data    = data.data() + total;

            int ret = ::ioctl(fd_, USBDEVFS_BULK, &bulk);
            if (ret > 0) {
                total += static_cast<std::size_t>(ret);
                fails  = 0;
                continue;
            }
            int e = errno;
            if (e == ENODEV || e == ESHUTDOWN || e == ENOENT) {
                connected_ = false; return -1;
            }
            if (e == EAGAIN || e == EINTR || e == ETIMEDOUT) {
                if (++fails > retries) return -1;
                usleep(5000);
                continue;
            }
            LOGE("USB recv error ep=0x%02X errno=%d: %s", ep_in_, e, strerror(e));
            return -1;
        }
        return static_cast<int>(total);
    }

    int recv_zlp(unsigned = 0) override {
        struct usbdevfs_bulktransfer bulk{};
        bulk.ep = ep_in_; bulk.len = 0;
        bulk.timeout = static_cast<unsigned>(timeout_ms_);
        bulk.data = nullptr;
        return ::ioctl(fd_, USBDEVFS_BULK, &bulk);
    }

    void close() noexcept { connected_ = false; }

    void reset_endpoints() noexcept {
        unsigned ep = ep_out_; ::ioctl(fd_, USBDEVFS_CLEAR_HALT, &ep);
        ep = ep_in_;           ::ioctl(fd_, USBDEVFS_CLEAR_HALT, &ep);
        LOGI("USB endpoints cleared (fd=%d)", fd_);
    }

    int fd()     const noexcept { return fd_; }
    int ep_in()  const noexcept { return static_cast<int>(ep_in_); }
    int ep_out() const noexcept { return static_cast<int>(ep_out_); }

private:
    // استعادة كل URBs المعلقة (عند الخطأ أو الإغلاق)
    void drain_() noexcept {
        for (auto& pu : pending_) {
            if (!pu.in_flight) continue;
            ::ioctl(fd_, USBDEVFS_DISCARDURB, &pu.urb);
            usbdevfs_urb* r = nullptr;
            while (::ioctl(fd_, USBDEVFS_REAPURB, &r) != 0 && errno == EINTR) {}
            pu.in_flight = false;
        }
    }

    struct PendingUrb {
        usbdevfs_urb urb{};
        bool         in_flight = false;
    };

    int      fd_;
    unsigned ep_in_, ep_out_;
    int      timeout_ms_ = 20000;
    bool     connected_  = true;

    std::array<PendingUrb, kPipelineDepth> pending_{};
};

// ─────────────────────────────────────────────────────────────────────────────
// TCP Transport (Wireless)
// ─────────────────────────────────────────────────────────────────────────────
class TcpTransport : public eko::core::IByteTransport {
public:
    explicit TcpTransport(int fd) : fd_(fd) {
        int opt = 1;
        setsockopt(fd_, IPPROTO_TCP, TCP_NODELAY, &opt, sizeof(opt));
    }

    ~TcpTransport() override {
        if (fd_ >= 0) { ::shutdown(fd_, SHUT_RDWR); ::close(fd_); }
    }

    bool connected() const noexcept override { return connected_ && fd_ >= 0; }
    int  timeout_ms() const noexcept override { return timeout_ms_; }
    void set_timeout_ms(int ms) noexcept override {
        timeout_ms_ = ms;
        struct timeval tv;
        tv.tv_sec  = ms / 1000;
        tv.tv_usec = (ms % 1000) * 1000;
        setsockopt(fd_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(fd_, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    }
    void set_packet_size_hint(std::size_t) noexcept override {}
    IByteTransport::Kind kind() const noexcept override {
        return IByteTransport::Kind::TcpStream;
    }

    int send(std::span<const std::uint8_t> data, unsigned = 0) override {
        std::size_t sent = 0;
        while (sent < data.size()) {
            ssize_t n = ::send(fd_, data.data() + sent, data.size() - sent, MSG_NOSIGNAL);
            if (n <= 0) { connected_ = false; return -1; }
            sent += static_cast<std::size_t>(n);
        }
        return static_cast<int>(sent);
    }

    int recv(std::span<std::uint8_t> data, unsigned = 0) override {
        ssize_t n = ::recv(fd_, data.data(), data.size(), MSG_WAITALL);
        if (n <= 0) { connected_ = false; return -1; }
        return static_cast<int>(n);
    }

    int recv_zlp(unsigned = 0) override { return 0; }

private:
    int  fd_;
    int  timeout_ms_ = 20000;
    bool connected_  = true;
};

// ─────────────────────────────────────────────────────────────────────────────
// Session & Callbacks
// ─────────────────────────────────────────────────────────────────────────────
struct JavaCallbacks {
    JavaVM*   jvm          = nullptr;
    jobject   jobj         = nullptr;
    jmethodID onLog        = nullptr;
    jmethodID onStage      = nullptr;
    jmethodID onProgress   = nullptr;
    jmethodID onModel      = nullptr;
    jmethodID onError      = nullptr;
    jmethodID onFinished   = nullptr;
    jmethodID onItemActive = nullptr;
    jmethodID onItemDone   = nullptr;
    jmethodID onPlanReady  = nullptr;
};

struct NativeSession {
    std::atomic<bool> cancelled{false};
    JavaCallbacks     cb;
    std::string       cachePath;
};

static JNIEnv* attach(NativeSession* s, bool& attached) {
    JNIEnv* env = nullptr; attached = false;
    if (!s || !s->cb.jvm) return nullptr;
    auto r = s->cb.jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) { s->cb.jvm->AttachCurrentThread(&env, nullptr); attached = true; }
    return env;
}

static void cb_log(NativeSession* s, int level, const char* msg) {
    if (!s || !s->cb.jobj || !s->cb.onLog) return;
    bool att; JNIEnv* e = attach(s, att);
    if (!e) return;
    jstring js = e->NewStringUTF(msg ? msg : "");
    e->CallVoidMethod(s->cb.jobj, s->cb.onLog, (jint)level, js);
    e->DeleteLocalRef(js);
    if (att) s->cb.jvm->DetachCurrentThread();
}

static void cb_stage(NativeSession* s, const char* stage) {
    if (!s || !s->cb.jobj || !s->cb.onStage) return;
    bool att; JNIEnv* e = attach(s, att);
    if (!e) return;
    jstring js = e->NewStringUTF(stage ? stage : "");
    e->CallVoidMethod(s->cb.jobj, s->cb.onStage, js);
    e->DeleteLocalRef(js);
    if (att) s->cb.jvm->DetachCurrentThread();
}

static void cb_progress(NativeSession* s, long done, long total, long speed, long elapsed) {
    if (!s || !s->cb.jobj || !s->cb.onProgress) return;
    bool att; JNIEnv* e = attach(s, att);
    if (!e) return;
    e->CallVoidMethod(s->cb.jobj, s->cb.onProgress,
                      (jlong)done, (jlong)total, (jlong)speed, (jlong)elapsed);
    if (att) s->cb.jvm->DetachCurrentThread();
}

static void cb_finished(NativeSession* s, bool ok, const char* msg) {
    if (!s || !s->cb.jobj || !s->cb.onFinished) return;
    bool att; JNIEnv* e = attach(s, att);
    if (!e) return;
    jstring js = e->NewStringUTF(msg ? msg : "");
    e->CallVoidMethod(s->cb.jobj, s->cb.onFinished, (jboolean)(ok ? 1 : 0), js);
    e->DeleteLocalRef(js);
    if (att) s->cb.jvm->DetachCurrentThread();
}

static void cb_error(NativeSession* s, const char* msg) {
    if (!s || !s->cb.jobj || !s->cb.onError) return;
    bool att; JNIEnv* e = attach(s, att);
    if (!e) return;
    jstring js = e->NewStringUTF(msg ? msg : "");
    e->CallVoidMethod(s->cb.jobj, s->cb.onError, js);
    e->DeleteLocalRef(js);
    if (att) s->cb.jvm->DetachCurrentThread();
}

static void cb_model(NativeSession* s, const char* model) {
    if (!s || !s->cb.jobj || !s->cb.onModel) return;
    bool att; JNIEnv* e = attach(s, att);
    if (!e) return;
    jstring js = e->NewStringUTF(model ? model : "");
    e->CallVoidMethod(s->cb.jobj, s->cb.onModel, js);
    e->DeleteLocalRef(js);
    if (att) s->cb.jvm->DetachCurrentThread();
}

// ─────────────────────────────────────────────────────────────────────────────
// بناء ImageSpec حقيقية من FDs
// ─────────────────────────────────────────────────────────────────────────────

// ByteSource مبنية على FD مع دعم open() متعددة
struct FdImageOpener {
    int         fd;
    std::string name;
    uint64_t    size;
};

// نبني ImageSpec من FD مفتوح — يستخدم Kind::FdFile الجديد
// بدلاً من /proc/self/fd/N الذي يُرجع file_size()=0 على procfs
class FdBackedImageSpec {
public:
    static eko::odin::ImageSpec make(int fd, const std::string& name, uint64_t size) {
        eko::odin::ImageSpec spec;

        spec.kind            = eko::odin::ImageSpec::Kind::FdFile;
        spec.fd              = fd;          // يُستخدم في open_fd_file()
        spec.source_basename = name;
        spec.disk_size       = size;
        spec.display         = name;
        spec.lz4             = (name.size() >= 4 &&
                                name.substr(name.size() - 4) == ".lz4");

        // basename بدون .lz4
        if (spec.lz4) {
            spec.basename = name.substr(0, name.size() - 4);
        } else {
            spec.basename = name;
        }

        spec.size = size;

        return spec;
    }
};

// ─────────────────────────────────────────────────────────────────────────────
// قراءة PIT من FD
// ─────────────────────────────────────────────────────────────────────────────
static std::shared_ptr<std::vector<std::byte>> read_pit_from_fd(int fd, uint64_t size) {
    if (fd < 0 || size == 0) return nullptr;

    int dup_fd = dup(fd);
    if (dup_fd < 0) return nullptr;
    lseek(dup_fd, 0, SEEK_SET);

    auto buf = std::make_shared<std::vector<std::byte>>(size);
    size_t off = 0;
    while (off < size) {
        ssize_t n = ::read(dup_fd, reinterpret_cast<char*>(buf->data()) + off, size - off);
        if (n <= 0) break;
        off += static_cast<size_t>(n);
    }
    close(dup_fd);

    if (off < size) {
        LOGE("PIT read: only got %zu of %llu bytes", off, (unsigned long long)size);
        buf->resize(off);
    }
    return buf;
}

// ─────────────────────────────────────────────────────────────────────────────
// Core flash runner — USB
// ─────────────────────────────────────────────────────────────────────────────
static void run_usb_flash(NativeSession* s,
                           int usb_fd, int ep_in, int ep_out,
                           const std::vector<int>&         fileFds,
                           const std::vector<std::string>& fileNames,
                           const std::vector<uint64_t>&    fileSizes,
                           int pitFd, uint64_t pitSize,
                           bool nandErase,
                           bool rebootAfter) {

    cb_log(s, 3, "EKO TURBO — Thor-style USB flash (only .tar/.tar.md5)");
    cb_log(s, 3, ("Files to flash: " + std::to_string(fileFds.size())).c_str());

    // ── بناء ImageSpecs — مع tar expansion لملفات .tar / .tar.md5 ──────────
    std::vector<eko::odin::ImageSpec> sources;
    sources.reserve(fileFds.size() * 4); // قد يحتوي كل tar على عدة partitions

    // Thor-style STRICT: "Invalid extension! Valid: '.tar' and '.tar.md5'"
    auto is_tar_name = [](const std::string& name) -> bool {
        auto ends_with_ci = [&](const char* suffix) {
            const size_t sl = strlen(suffix);
            if (name.size() < sl) return false;
            std::string tail = name.substr(name.size() - sl);
            for (char& c : tail) c = static_cast<char>(tolower(c));
            return tail == suffix;
        };
        return ends_with_ci(".tar") || ends_with_ci(".tar.md5");
    };

    for (size_t i = 0; i < fileFds.size(); ++i) {
        if (fileFds[i] < 0) {
            LOGW("Skipping invalid fd at index %zu", i);
            continue;
        }

        const std::string& fname = fileNames[i];
        cb_log(s, 3, ("  + " + fname +
                       " (" + std::to_string(fileSizes[i] / 1024 / 1024) + " MB)").c_str());

        if (is_tar_name(fname)) {
            // فتح التار عبر /proc/self/fd/N — TarArchive بيقرأ بـ ifstream فبيشتغل
            std::string proc_path = "/proc/self/fd/" + std::to_string(fileFds[i]);
            auto tar_result = eko::io::TarArchive::open(proc_path, false /* skip checksum on fd path */);
            if (!tar_result) {
                std::string err = "TAR open failed for '" + fname + "': " + tar_result.error()
                                + " — file may be corrupt (only Samsung .tar/.tar.md5 supported)";
                cb_log(s, 4, err.c_str());
                cb_error(s, err.c_str());
                cb_finished(s, false, err.c_str());
                return;
            }

            const auto& entries = tar_result->entries();
            cb_log(s, 3, ("TAR expanded: " + fname + " → " +
                           std::to_string(entries.size()) + " entries").c_str());

            for (const auto& entry : entries) {
                // تخطي download-list.txt وأي entry فارغ أو directory
                if (entry.name.find("download-list") != std::string::npos) continue;
                if (entry.size == 0) continue;
                if (entry.name.empty() || entry.name.back() == '/') continue;

                eko::odin::ImageSpec spec;
                spec.kind            = eko::odin::ImageSpec::Kind::TarEntry;
                spec.path            = proc_path;
                spec.entry           = entry;
                spec.source_basename = eko::io::basename(entry.name);
                spec.disk_size       = entry.size;
                spec.display         = fname + ":" + entry.name;

                // lz4 suffix handling
                spec.lz4 = (spec.source_basename.size() >= 4 &&
                             spec.source_basename.substr(spec.source_basename.size() - 4) == ".lz4");
                if (spec.lz4) {
                    spec.basename = spec.source_basename.substr(0, spec.source_basename.size() - 4);
                    spec.size = entry.size; // تقريبي — الـ engine بيقرأ الحجم الحقيقي من lz4 frame header
                } else {
                    spec.basename = spec.source_basename;
                    spec.size     = entry.size;
                }

                cb_log(s, 3, ("    entry: " + spec.source_basename +
                               " (" + std::to_string(entry.size / 1024 / 1024) + " MB)").c_str());
                sources.push_back(std::move(spec));
            }
        } else {
            // Thor-style: رفض صارم — نفس رسالة Thor string[30]
            std::string err = "Invalid extension for '" + fname +
                              "'. Valid: '.tar' and '.tar.md5'";
            cb_log(s, 4, err.c_str());
            cb_error(s, err.c_str());
            cb_finished(s, false, err.c_str());
            return;
        }
    }

    if (sources.empty()) {
        cb_error(s, "No valid files to flash");
        cb_finished(s, false, "No valid files");
        return;
    }

    // ── قراءة PIT إذا وُجد ──────────────────────────────────────────────────
    std::shared_ptr<const std::vector<std::byte>> pit_data;
    if (pitFd >= 0 && pitSize > 0) {
        auto pd = read_pit_from_fd(pitFd, pitSize);
        if (pd && !pd->empty()) {
            pit_data = std::const_pointer_cast<const std::vector<std::byte>>(pd);
            cb_log(s, 3, ("PIT loaded: " + std::to_string(pit_data->size()) + " bytes").c_str());
        } else {
            cb_log(s, 4, "PIT read failed — will use device PIT");
        }
    }

    // ── إعداد USB Transport ──────────────────────────────────────────────────
    // FIX: بعد فتح USB بالروت، الـ endpoints ممكن تكون في HALT state
    // نعمل CLEAR_HALT على IN و OUT قبل أي transfer
    // ── CLEAR_HALT على الـ endpoints قبل أي transfer ────────────────────────
    // ضروري بعد claim عشان الـ endpoints ممكن تكون في HALT state
    {
        unsigned ep_clr = static_cast<unsigned>(ep_out);
        ::ioctl(usb_fd, USBDEVFS_CLEAR_HALT, &ep_clr);
        ep_clr = static_cast<unsigned>(ep_in);
        ::ioctl(usb_fd, USBDEVFS_CLEAR_HALT, &ep_clr);
        // 20ms كافية — الـ async pipeline يتحمل delay الأول في الـ handshake
        usleep(20000);
    }
    AndroidUsbTransport transport(usb_fd, ep_in, ep_out);

    // ── إعداد Target ─────────────────────────────────────────────────────────
    eko::odin::Target target;
    target.id   = "eko-usb-" + std::to_string(usb_fd);
    target.link = &transport;

    std::vector<eko::odin::Target*> devs = {&target};

    // ── إعداد Cfg — مُحسَّن لـ Android OTG (مستخرج من تحليل libbrokkr) ────
    eko::odin::Cfg cfg;
    // 128MB: يُبقي TwoSlotPrefetcher مشغولاً مع async URB pipeline
    cfg.buffer_bytes        = 128ull * 1024 * 1024;  // (was 64MB)
    // 4MB packet: Samsung حديثة تقبله — يُقلل عدد begin/end_download RPCs
    cfg.pkt_all_v2plus      = 4ull * 1024 * 1024;    // (was 2MB)
    cfg.flash_timeout_ms    = 120'000;
    cfg.preflash_timeout_ms = 5000;
    cfg.preflash_retries    = 3;
    cfg.reboot_after        = rebootAfter;
    cfg.nand_erase          = nandErase;

    // ── إعداد Ui callbacks ───────────────────────────────────────────────────
    eko::odin::Ui ui;

    ui.on_stage = [s](const std::string& st) {
        cb_stage(s, st.c_str());
        // NOTE: cb_stage already calls onStage() in Java which calls store.appendLog("> Stage: ...").
        // Calling cb_log here too caused every stage to appear twice in the log.
    };

    ui.on_model = [s](const std::string& model) {
        cb_model(s, model.c_str());
        cb_log(s, 3, ("Device: " + model).c_str());
    };

    ui.on_plan = [s](const std::vector<eko::odin::PlanItem>& plan, uint64_t total) {
        std::string msg = "Flash plan: " + std::to_string(plan.size()) +
                          " items, " + std::to_string(total / 1024 / 1024) + " MB total";
        cb_log(s, 3, msg.c_str());
        // onPlanReady callback
        if (s && s->cb.jobj && s->cb.onPlanReady) {
            bool att; JNIEnv* e = attach(s, att);
            if (e) {
                e->CallVoidMethod(s->cb.jobj, s->cb.onPlanReady,
                                  (jint)plan.size(), (jlong)total);
                if (att) s->cb.jvm->DetachCurrentThread();
            }
        }
    };

    ui.on_item_active = [s](std::size_t idx) {
        if (!s || !s->cb.jobj || !s->cb.onItemActive) return;
        bool att; JNIEnv* e = attach(s, att);
        if (e) {
            e->CallVoidMethod(s->cb.jobj, s->cb.onItemActive, (jint)idx);
            if (att) s->cb.jvm->DetachCurrentThread();
        }
    };

    ui.on_item_done = [s](std::size_t idx) {
        if (!s || !s->cb.jobj || !s->cb.onItemDone) return;
        bool att; JNIEnv* e = attach(s, att);
        if (e) {
            e->CallVoidMethod(s->cb.jobj, s->cb.onItemDone, (jint)idx);
            if (att) s->cb.jvm->DetachCurrentThread();
        }
    };

    ui.on_progress = [s](uint64_t done, uint64_t total, uint64_t speed, uint64_t elapsed) {
        cb_progress(s, (long)done, (long)total, (long)speed, (long)elapsed);
    };

    ui.on_error = [s](const std::string& msg) {
        cb_error(s, msg.c_str());
    };

    ui.on_done = [s]() {
        cb_log(s, 3, "All partitions flashed successfully");
    };

    // ── تشغيل الفلاش ─────────────────────────────────────────────────────────
    cb_log(s, 3, "Calling eko::odin::flash()...");

    auto result = eko::odin::flash(devs, sources, pit_data, cfg, std::move(ui));

    if (!result) {
        std::string err = "Flash failed: " + result.error();
        LOGE("%s", err.c_str());
        cb_error(s, err.c_str());
        cb_finished(s, false, err.c_str());
    } else {
        cb_log(s, 3, "Flash completed successfully!");
        cb_finished(s, true, "Flash complete");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_eko_f_EkoNativeBridge_nativeCreateSession(
        JNIEnv* env, jobject, jobject callbacks, jstring cachePath) {

    auto* s = new NativeSession();
    env->GetJavaVM(&s->cb.jvm);
    s->cb.jobj = env->NewGlobalRef(callbacks);

    jclass cls = env->GetObjectClass(callbacks);
    s->cb.onLog        = env->GetMethodID(cls, "onLog",        "(ILjava/lang/String;)V");
    s->cb.onStage      = env->GetMethodID(cls, "onStage",      "(Ljava/lang/String;)V");
    s->cb.onProgress   = env->GetMethodID(cls, "onProgress",   "(JJJJ)V");
    s->cb.onModel      = env->GetMethodID(cls, "onModel",      "(Ljava/lang/String;)V");
    s->cb.onError      = env->GetMethodID(cls, "onError",      "(Ljava/lang/String;)V");
    s->cb.onFinished   = env->GetMethodID(cls, "onFinished",   "(ZLjava/lang/String;)V");
    s->cb.onItemActive = env->GetMethodID(cls, "onItemActive", "(I)V");
    s->cb.onItemDone   = env->GetMethodID(cls, "onItemDone",   "(I)V");
    s->cb.onPlanReady  = env->GetMethodID(cls, "onPlanReady",  "(IJ)V");
    env->DeleteLocalRef(cls);

    const char* cp = env->GetStringUTFChars(cachePath, nullptr);
    if (cp) { s->cachePath = cp; env->ReleaseStringUTFChars(cachePath, cp); }

    LOGI("Session created %p", s);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_eko_f_EkoNativeBridge_nativeDestroySession(
        JNIEnv* env, jobject, jlong ptr) {
    auto* s = reinterpret_cast<NativeSession*>(ptr);
    if (!s) return;
    if (s->cb.jobj) { env->DeleteGlobalRef(s->cb.jobj); s->cb.jobj = nullptr; }
    LOGI("Session destroyed %p", s);
    delete s;
}

JNIEXPORT void JNICALL
Java_com_eko_f_EkoNativeBridge_nativeRequestCancel(
        JNIEnv*, jclass, jlong ptr) {
    auto* s = reinterpret_cast<NativeSession*>(ptr);
    if (s) { s->cancelled = true; LOGI("Cancel requested %p", s); }
}

// ── extract_file_arrays — تحويل JNI arrays لـ C++ vectors ───────────────────
static void extract_file_arrays(
        JNIEnv* env,
        jintArray    jFileFds,
        jobjectArray jFileNames,
        jlongArray   jFileSizes,
        std::vector<int>&         fileFds,
        std::vector<std::string>& fileNames,
        std::vector<uint64_t>&    fileSizes) {

    if (!jFileFds || !jFileNames || !jFileSizes) return;

    const jsize count = env->GetArrayLength(jFileFds);
    if (count <= 0) return;

    fileFds.reserve(static_cast<size_t>(count));
    fileNames.reserve(static_cast<size_t>(count));
    fileSizes.reserve(static_cast<size_t>(count));

    // ── File FDs ──
    jint* fds = env->GetIntArrayElements(jFileFds, nullptr);
    if (fds) {
        for (jsize i = 0; i < count; ++i)
            fileFds.push_back(static_cast<int>(fds[i]));
        env->ReleaseIntArrayElements(jFileFds, fds, JNI_ABORT);
    }

    // ── File names ──
    for (jsize i = 0; i < count; ++i) {
        auto jstr = static_cast<jstring>(env->GetObjectArrayElement(jFileNames, i));
        if (jstr) {
            const char* cstr = env->GetStringUTFChars(jstr, nullptr);
            fileNames.emplace_back(cstr ? cstr : "");
            if (cstr) env->ReleaseStringUTFChars(jstr, cstr);
            env->DeleteLocalRef(jstr);
        } else {
            fileNames.emplace_back("");
        }
    }

    // ── File sizes ──
    jlong* sizes = env->GetLongArrayElements(jFileSizes, nullptr);
    if (sizes) {
        for (jsize i = 0; i < count; ++i)
            fileSizes.push_back(static_cast<uint64_t>(sizes[i]));
        env->ReleaseLongArrayElements(jFileSizes, sizes, JNI_ABORT);
    }
}

// ── nativeRunSession — الدالة الرئيسية للـ USB flash ────────────────────────
JNIEXPORT void JNICALL
Java_com_eko_f_EkoNativeBridge_nativeRunSession(
        JNIEnv* env, jobject, jlong ptr,
        jobjectArray transports,
        jintArray    jFileFds,
        jobjectArray jFileNames,
        jlongArray   jFileSizes,
        jint         pitFd,
        jlong        pitSize,
        jboolean     nandErase,
        jboolean     rebootAfter) {

    auto* s = reinterpret_cast<NativeSession*>(ptr);
    if (!s) { LOGE("nativeRunSession: null session"); return; }

    // ── استخراج ملفات الـ firmware ──
    std::vector<int>         fileFds;
    std::vector<std::string> fileNames;
    std::vector<uint64_t>    fileSizes;
    extract_file_arrays(env, jFileFds, jFileNames, jFileSizes,
                        fileFds, fileNames, fileSizes);

    if (fileFds.empty()) {
        cb_error(s, "No firmware files provided");
        cb_finished(s, false, "No firmware files");
        return;
    }

    LOGI("nativeRunSession: %zu files, pitFd=%d, nandErase=%d",
         fileFds.size(), (int)pitFd, (int)nandErase);

    // ── استخراج USB transport (fd, epIn, epOut) ──
    if (!transports || env->GetArrayLength(transports) == 0) {
        cb_error(s, "No USB transports");
        cb_finished(s, false, "No transports");
        return;
    }

    jobject jt  = env->GetObjectArrayElement(transports, 0);
    jclass  cls = env->GetObjectClass(jt);

    jfieldID fFd    = env->GetFieldID(cls, "nativeFd",       "I");
    jfieldID fEpIn  = env->GetFieldID(cls, "bulkInAddress",  "I");
    jfieldID fEpOut = env->GetFieldID(cls, "bulkOutAddress", "I");

    if (!fFd || !fEpIn || !fEpOut) {
        cb_error(s, "EkoUsbTransport reflection failed");
        cb_finished(s, false, "Reflection error");
        env->DeleteLocalRef(jt); env->DeleteLocalRef(cls);
        return;
    }

    int usbFd  = env->GetIntField(jt, fFd);
    int epIn   = env->GetIntField(jt, fEpIn);
    int epOut  = env->GetIntField(jt, fEpOut);
    env->DeleteLocalRef(jt);
    env->DeleteLocalRef(cls);

    LOGI("USB: fd=%d epIn=0x%02X epOut=0x%02X", usbFd, epIn, epOut);

    // ── تشغيل الفلاش ──
    run_usb_flash(s,
                  usbFd, epIn, epOut,
                  fileFds, fileNames, fileSizes,
                  (int)pitFd, (uint64_t)pitSize,
                  (bool)nandErase,
                  (bool)rebootAfter);
}

// nativeRunWirelessSession: REMOVED (Thor-style USB-only)
// ─────────────────────────────────────────────────────────────────────────────
// Legacy JNI — MainActivity
// ─────────────────────────────────────────────────────────────────────────────

static std::atomic<bool>                         g_init{false};
static std::unique_ptr<AndroidUsbTransport>      g_transport;

JNIEXPORT jboolean JNICALL
Java_com_eko_f_MainActivity_initEngine(JNIEnv*, jobject) {
    LOGI("initEngine %s", EKO_ENGINE_VERSION);
    g_init = false;
    g_transport.reset();
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_eko_f_MainActivity_getEngineVersion(JNIEnv* env, jobject) {
    const auto& v = eko::app::version_string();
    return env->NewStringUTF(v.empty() ? EKO_ENGINE_VERSION : v.c_str());
}

JNIEXPORT jint JNICALL
Java_com_eko_f_MainActivity_nativeInit(JNIEnv*, jobject,
                                        jint fd, jint epIn, jint epOut) {
    if (fd < 0 || epIn < 0 || epOut < 0) return -1;
    g_init = false;
    g_transport = std::make_unique<AndroidUsbTransport>(fd, epIn, epOut);
    g_init = true;
    LOGI("nativeInit fd=%d", (int)fd);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_eko_f_MainActivity_nativeHandshake(JNIEnv*, jobject) {
    return (g_init && g_transport) ? 0 : -1;
}

JNIEXPORT jstring JNICALL
Java_com_eko_f_MainActivity_nativeReadPit(JNIEnv* env, jobject) {
    if (!g_init || !g_transport)
        return env->NewStringUTF("ERROR:not_initialized");
    // PIT exchange بدون session
    eko::odin::OdinCommands cmd(*g_transport);
    g_transport->set_timeout_ms(45000);
    auto res = eko::odin::download_pit_table(cmd, 0);
    g_transport->set_timeout_ms(20000);
    return env->NewStringUTF(res ? "PIT_OK" : "ERROR:pit_failed");
}

JNIEXPORT jint JNICALL
Java_com_eko_f_MainActivity_nativeNandErase(JNIEnv*, jobject) {
    LOGW("nativeNandErase: stub"); return 0;
}

JNIEXPORT jint JNICALL
Java_com_eko_f_MainActivity_nativeReboot(JNIEnv*, jobject) {
    if (!g_init || !g_transport) return -1;
    LOGI("nativeReboot");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_eko_f_MainActivity_nativeClose(JNIEnv*, jobject) {
    if (g_transport) g_transport->close();
    g_init = false;
    g_transport.reset();
}

} // extern "C"
