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
#include <fstream>

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

    std::string get_snapshot_text(size_t max_bytes) {
        size_t current_write = write_pos.load(std::memory_order_acquire);
        if (current_write == 0 || current_write > capacity) return "";

        const char* base_ptr = off_heap_buffer.data();
        size_t offset = 0;
        const uint32_t kMagic = 0x4C4F4753; // "LOGS"

        // 使用 string_view 引用堆外内存，零额外内存分配
        std::vector<std::string_view> blocks;

        // 1. 在 C++ 堆外直接解析二进制头部，提取所有合法日志块
        while (offset + sizeof(LogHeader) <= current_write) {
            const LogHeader* header = reinterpret_cast<const LogHeader*>(base_ptr + offset);
            if (header->magic != kMagic || header->length == 0 || offset + sizeof(LogHeader) + header->length > current_write) {
                break; // 遇到无效数据或空白区，停止
            }

            const char* content_ptr = base_ptr + offset + sizeof(LogHeader);
            blocks.emplace_back(content_ptr, header->length);
            offset += sizeof(LogHeader) + header->length;
        }

        // 2. 在 C++ 内部计算滑动窗口（从后往前，只取最近的 max_bytes）
        size_t total_size = 0;
        size_t start_index = blocks.size();
        for (long long i = static_cast<long long>(blocks.size()) - 1; i >= 0; --i) {
            if (total_size + blocks[i].size() > max_bytes && !blocks.empty()) {
                break;
            }
            total_size += blocks[i].size();
            start_index = i;
        }

        // 3. 在 C++ 内存中一次性拼接最终文本
        std::string result;
        result.reserve(total_size);
        for (size_t i = start_index; i < blocks.size(); ++i) {
            result.append(blocks[i].data(), blocks[i].size());
        }

        return result;
    }

    // 新增：在 C++ 堆外直接解析并写入文件，零 JVM 内存消耗
    bool export_to_file(const char* filepath) {
        size_t current_write = write_pos.load(std::memory_order_acquire);

        // 打开目标文件（二进制模式写入）
        std::ofstream outfile(filepath, std::ios::out | std::ios::binary);
        if (!outfile.is_open()) return false;

        if (current_write == 0 || current_write > capacity) {
            return true; // 缓冲区为空，直接生成空文件
        }

        const char* base_ptr = off_heap_buffer.data();
        size_t offset = 0;
        const uint32_t kMagic = 0x4C4F4753; // "LOGS"

        // 循环解析二进制块，直接写入文件流
        while (offset + sizeof(LogHeader) <= current_write) {
            const LogHeader* header = reinterpret_cast<const LogHeader*>(base_ptr + offset);
            if (header->magic != kMagic || header->length == 0 || offset + sizeof(LogHeader) + header->length > current_write) {
                break; // 遇到无效数据或空白区，停止
            }

            const char* content_ptr = base_ptr + offset + sizeof(LogHeader);
            outfile.write(content_ptr, header->length);
            offset += sizeof(LogHeader) + header->length;
        }

        outfile.flush();
        return outfile.good();
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

JNIEXPORT jstring JNICALL
Java_com_adb_kitty_data_NativeLibs_getLogSnapshot(JNIEnv* env, jobject thiz, jint max_bytes) {
    if (!g_log_engine) return env->NewStringUTF("");
    std::string text = g_log_engine->get_snapshot_text(static_cast<size_t>(max_bytes));
    return env->NewStringUTF(text.c_str());
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

JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_data_NativeLibs_exportLogToFile(JNIEnv* env, jobject thiz, jstring file_path) {
    if (!g_log_engine || !file_path) return JNI_FALSE;

    const char* path_chars = env->GetStringUTFChars(file_path, nullptr);
    if (!path_chars) return JNI_FALSE;

    bool success = g_log_engine->export_to_file(path_chars);

    env->ReleaseStringUTFChars(file_path, path_chars);
    return success ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
