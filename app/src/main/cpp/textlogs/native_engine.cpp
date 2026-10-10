#include <jni.h>
#include <string>
#include <string_view>
#include <vector>
#include <atomic>
#include <memory>
#include <cstring>
#include <sys/mman.h>
#include <dlfcn.h>
#include <cstdint>

inline void safe_malloc_trim() {
    typedef int (*malloc_trim_fn)(size_t);
    static auto trim_func = reinterpret_cast<malloc_trim_fn>(dlsym(RTLD_DEFAULT, "malloc_trim"));
    if (trim_func) {
        trim_func(0);
    }
}

// 日志块头部元数据
struct LogHeader {
    uint32_t length;
    uint32_t magic;
};

class NativeLogEngine {
private:
    std::vector<char> off_heap_buffer;
    std::atomic<size_t> write_pos{0};
    size_t capacity;
    const uint32_t kMagic = 0x4C4F4753; // "LOGS"

public:
    explicit NativeLogEngine(size_t cap) : capacity(cap) {
        off_heap_buffer.resize(cap, 0);
    }

    ~NativeLogEngine() {
        off_heap_buffer.clear();
        off_heap_buffer.shrink_to_fit();
        safe_malloc_trim();
    }

    // 批量写入全局环形缓冲区（由线程本地缓存满时触发）
    bool append_batch(const char* data, size_t size) {
        if (size == 0 || size > capacity) return false;

        while (true) {
            size_t current = write_pos.load(std::memory_order_relaxed);
            
            // 空间不足，原子 CAS 尝试回滚到 0
            if (current + size > capacity) {
                if (write_pos.compare_exchange_weak(current, 0, std::memory_order_release, std::memory_order_relaxed)) {
                    continue;
                }
                continue;
            }

            // 原子抢占整批空间
            if (write_pos.compare_exchange_weak(current, current + size, std::memory_order_release, std::memory_order_relaxed)) {
                std::memcpy(off_heap_buffer.data() + current, data, size);
                return true;
            }
        }
    }

    void* get_raw_buffer_ptr() {
        return off_heap_buffer.data();
    }

    size_t get_capacity() const {
        return capacity;
    }

    size_t get_write_offset() const {
        return write_pos.load(std::memory_order_acquire);
    }

    void clear() {
        write_pos.store(0, std::memory_order_release);
        if (!off_heap_buffer.empty()) {
            std::memset(off_heap_buffer.data(), 0, capacity);
            madvise(off_heap_buffer.data(), capacity, MADV_DONTNEED);
        }
    }
};

// 线程本地日志缓冲区（Thread-Local Buffer）
class ThreadLocalLogBuffer {
private:
    NativeLogEngine* engine;
    std::vector<char> buffer;
    size_t capacity;
    size_t current_pos;
    const uint32_t kMagic = 0x4C4F4753;

public:
    explicit ThreadLocalLogBuffer(NativeLogEngine* eng, size_t cap = 4096) 
        : engine(eng), capacity(cap), current_pos(0) {
        buffer.resize(capacity, 0);
    }

    ~ThreadLocalLogBuffer() {
        flush(); // 线程退出时自动清理剩余日志到全局
    }

    void append(std::string_view raw_log, bool auto_newline) {
        size_t len = raw_log.size();
        if (len == 0) return;

        size_t total_len = len + (auto_newline ? 1 : 0);
        size_t needed = total_len + sizeof(LogHeader);

        // 如果单条日志超出了整个本地缓存容量，直接绕过本地，单独强行提交
        if (needed > capacity) {
            flush(); // 先刷出积压的
            std::vector<char> temp_chunk(needed);
            LogHeader* header = reinterpret_cast<LogHeader*>(temp_chunk.data());
            header->length = static_cast<uint32_t>(total_len);
            header->magic = kMagic;
            char* dest = temp_chunk.data() + sizeof(LogHeader);
            std::memcpy(dest, raw_log.data(), len);
            if (auto_newline) {
                dest[len] = '\n';
            }
            engine->append_batch(temp_chunk.data(), needed);
            return;
        }

        // 如果本地缓存空间不够，先触发一次 Flush 提交到全局
        if (current_pos + needed > capacity) {
            flush();
        }

        // 写入线程本地缓冲区
        LogHeader* header = reinterpret_cast<LogHeader*>(buffer.data() + current_pos);
        header->length = static_cast<uint32_t>(total_len);
        header->magic = kMagic;

        char* dest = buffer.data() + current_pos + sizeof(LogHeader);
        std::memcpy(dest, raw_log.data(), len);
        if (auto_newline) {
            dest[len] = '\n';
        }
        current_pos += needed;
    }

    void flush() {
        if (current_pos == 0 || !engine) return;
        engine->append_batch(buffer.data(), current_pos);
        current_pos = 0;
    }
};

// 获取当前线程私有的 ThreadLocalLogBuffer 实例
inline ThreadLocalLogBuffer* get_thread_local_buffer(NativeLogEngine* engine) {
    thread_local std::unique_ptr<ThreadLocalLogBuffer> tls_buf = nullptr;
    if (!tls_buf && engine) {
        tls_buf = std::make_unique<ThreadLocalLogBuffer>(engine, 4096); // 每个线程分配 4KB 本地缓存
    }
    return tls_buf.get();
}

static std::unique_ptr<NativeLogEngine> g_log_engine = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_adb_kitty_data_NativeLibs_initNativeEngine(JNIEnv* env, jobject thiz, jint capacity) {
    g_log_engine = std::make_unique<NativeLogEngine>(static_cast<size_t>(capacity));
}

JNIEXPORT jobject JNICALL
Java_com_adb_kitty_data_NativeLibs_getDirectBuffer(JNIEnv* env, jobject thiz) {
    if (!g_log_engine) return nullptr;
    return env->NewDirectByteBuffer(g_log_engine->get_raw_buffer_ptr(), g_log_engine->get_capacity());
}

JNIEXPORT void JNICALL
Java_com_adb_kitty_data_NativeLibs_appendNativeLog(JNIEnv* env, jobject thiz, jstring log_str) {
    if (!g_log_engine || !log_str) return;

    const char* chars = env->GetStringUTFChars(log_str, nullptr);
    jsize len = env->GetStringUTFLength(log_str);

    if (chars) {
        auto tls = get_thread_local_buffer(g_log_engine.get());
        if (tls) {
            tls->append(std::string_view(chars, static_cast<size_t>(len)), true);
        }
        env->ReleaseStringUTFChars(log_str, chars);
    }
}

JNIEXPORT void JNICALL
Java_com_adb_kitty_data_NativeLibs_appendNativeLogBytes(JNIEnv* env, jobject thiz, jbyteArray bytes) {
    if (!g_log_engine || !bytes) return;

    jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return;

    jbyte* buffer = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(bytes, nullptr));
    if (buffer) {
        auto tls = get_thread_local_buffer(g_log_engine.get());
        if (tls) {
            tls->append(std::string_view(reinterpret_cast<const char*>(buffer), static_cast<size_t>(len)), true);
        }
        env->ReleasePrimitiveArrayCritical(bytes, buffer, JNI_ABORT);
    }
}

JNIEXPORT jlong JNICALL
Java_com_adb_kitty_data_NativeLibs_getWriteOffset(JNIEnv* env, jobject thiz) {
    return g_log_engine ? static_cast<jlong>(g_log_engine->get_write_offset()) : 0L;
}

JNIEXPORT void JNICALL
Java_com_adb_kitty_data_NativeLibs_clearNativeBuffer(JNIEnv* env, jobject thiz) {
    if (g_log_engine) {
        g_log_engine->clear();
    }
}

JNIEXPORT void JNICALL
Java_com_adb_kitty_data_NativeLibs_releaseNativeEngine(JNIEnv* env, jobject thiz) {
    if (g_log_engine) {
        g_log_engine.reset();
        safe_malloc_trim();
    }
}

} // extern "C"
