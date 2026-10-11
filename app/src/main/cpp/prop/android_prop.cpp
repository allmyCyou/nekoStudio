#include <iostream>
#include <string>
#include <cstdio>
#include <unistd.h>

std::string getProperty(const std::string& property) {
    FILE *fp;
    char value[256];

    std::string command = "getprop " + property;
    fp = popen(command.c_str(), "r");
    if (fp == nullptr) {
        return "Failed to run command";
    }

    if (fgets(value, sizeof(value), fp) != nullptr) {
        pclose(fp);
        return std::string(value);
    }

    pclose(fp);
    return "Unknown";
}

int main() {
    std::cout << "Android SDK版本: " << getProperty("ro.build.version.sdk");
    std::cout << "Android 版本号: " << getProperty("ro.build.version.release");
    std::cout << "Vendor SDK版本: " << getProperty("ro.vendor.build.version.sdk");
    std::cout << "Vendor 版本: " << getProperty("ro.vendor.build.version.release");
    std::cout << "Android 系统版本: " << getProperty("ro.build.version.incremental");
    std::cout << "最低支持SDK版本: " << getProperty("ro.build.version.min_supported_target_sdk");
    std::cout << "安全补丁级别: " << getProperty("ro.build.version.security_patch");
    std::cout << "VNDK 版本号: " << getProperty("ro.vndk.version");
    std::cout << "构建主机: " << getProperty("ro.build.host");
    std::cout << "构建时间: " << getProperty("ro.system.build.date");
    std::cout << "构建标签: " << getProperty("ro.system.build.tags");
    std::cout << "构建类型: " << getProperty("ro.build.flavor");
    std::cout << "构建显示 ID: " << getProperty("ro.build.display.id");
    std::cout << "设备标识符: " << getProperty("ro.product.system.device");
    std::cout << "产品名称: " << getProperty("ro.product.name");
    std::cout << "产品市场名称: " << getProperty("ro.product.system.name");
    std::cout << "构建类型: " << getProperty("ro.build.type");
    std::cout << "硬件平台: " << getProperty("ro.hardware");
    std::cout << "CPU 架构: " << getProperty("ro.system.product.cpu.abilist");
    std::cout << "制造商: " << getProperty("ro.product.manufacturer");
    std::cout << "品牌: " << getProperty("ro.product.brand");
    std::cout << "型号: " << getProperty("ro.product.model");
    std::cout << "设备名称: " << getProperty("ro.product.vendor.marketname");
    std::cout << "主板型号: " << getProperty("ro.product.board");
    std::cout << "默认显示分辨率: " << getProperty("persist.sys.miui_resolution");
    std::cout << "默认显示尺寸: " << getProperty("ro.vendor.display.default_size");
    std::cout << "WLAN芯片制造商: " << getProperty("ro.hardware.wlan.chip");
    std::cout << "游戏或视频播放默认帧率: " << getProperty("ro.vendor.display.default_fps");
    std::cout << "默认语言与地区: " << getProperty("ro.product.locale");
    std::cout << "dex2oat64: " << getProperty("dalvik.vm.dex2oat64.enabled");
    std::cout << "LLNDK API: " << getProperty("ro.llndk.api_level");
    std::cout << "Zygote: " << getProperty("ro.zygote");
    std::cout << "OpenGL ES: " << getProperty("ro.opengles.version");
    std::cout << "CPU变种: " << getProperty("ro.bionic.cpu_variant");
    std::cout << "CPU变种2: " << getProperty("ro.bionic.2nd_cpu_variant");
    std::cout << "USB共享数据名称: " << getProperty("persist.data.df.dev_name");
    std::cout << "GPU性能分析工具支持: " << getProperty("graphics.gpu.profiler.support");
    std::cout << "设备支持的SIM卡类型: " << getProperty("ril.subscription.types");
    std::cout << "数据模式设置: " << getProperty("persist.vendor.data.mode");
    std::cout << "NFC: " << getProperty("ro.nfc.port");
    std::cout << "动态帧率切换支持: " << getProperty("ro.vendor.fps.switch.default");
    std::cout << "调试 Coresight 分析: " << getProperty("persist.debug.coresight.config");
    std::cout << "高通 QCA1530 WiFI芯片: " << getProperty("sys.qca1530");
    std::cout << "媒体录制最大帧率: " << getProperty("ro.media.recorder-max-base-layer-fps");
    std::cout << "SeLinux状态: " << getProperty("ro.secure");
    std::cout << "内核页面大小: " << getpagesize() << std::endl;

    return 0;
}
