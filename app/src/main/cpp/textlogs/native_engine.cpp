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

// 日志块头部元数据，用于无锁解析变长数据
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

    // 每次写入直接原子抢占全局缓冲区，保证 UI 实时可见
    void append_fast(std::string_view raw_log, bool auto_newline = true) {
        size_t len = raw_log.size();
        if (len == 0) return;

        size_t total_len = len + (auto_newline ? 1 : 0);
        size_t needed = total_len + sizeof(LogHeader);
        if (needed > capacity) return; // 单条日志超限

        while (true) {
            size_t current = write_pos.load(std::memory_order_relaxed);
            
            // 空间不足时，通过原子 CAS 尝试将写指针重置回 0
            if (current + needed > capacity) {
                if (write_pos.compare_exchange_weak(current, 0, std::memory_order_release, std::memory_order_relaxed)) {
                    continue; // 重置成功，重新循环从头抢占
                }
                continue; // 抢占失败，有其他线程修改了，重试
            }

            // 原子抢占当前片段空间
            if (write_pos.compare_exchange_weak(current, current + needed, std::memory_order_release, std::memory_order_relaxed)) {
                // 抢占成功，安全写入对应位置
                LogHeader* header = reinterpret_cast<LogHeader*>(off_heap_buffer.data() + current);
                header->length = static_cast<uint32_t>(total_len);
                header->magic = kMagic;

                char* dest = off_heap_buffer.data() + current + sizeof(LogHeader);
                std::memcpy(dest, raw_log.data(), len);
                if (auto_newline) {
                    dest[len] = '\n';
                }
                break;
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
        g_log_engine->append_fast(std::string_view(chars, static_cast<size_t>(len)), true);
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
        g_log_engine->append_fast(std::string_view(reinterpret_cast<const char*>(buffer), static_cast<size_t>(len)), true);
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
