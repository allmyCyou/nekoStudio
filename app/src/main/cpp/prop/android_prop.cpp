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
    std::cout << "Android SDK: " << getProperty("ro.build.version.sdk");
    std::cout << "Android Version: " << getProperty("ro.build.version.release");
    std::cout << "Vendor SDK: " << getProperty("ro.vendor.build.version.sdk");
    std::cout << "Vendor Version: " << getProperty("ro.vendor.build.version.release");
    std::cout << "Android System Version: " << getProperty("ro.build.version.incremental");
    std::cout << "Android Min SDK: " << getProperty("ro.build.version.min_supported_target_sdk");
    std::cout << "Android Security Patch: " << getProperty("ro.build.version.security_patch");
    std::cout << "Build Host: " << getProperty("ro.build.host");
    std::cout << "Build Time: " << getProperty("ro.system.build.date");
    std::cout << "Build Tags: " << getProperty("ro.system.build.tags");
    std::cout << "Build flavor: " << getProperty("ro.build.flavor");
    std::cout << "Build ID: " << getProperty("ro.build.display.id");
    std::cout << "Device: " << getProperty("ro.product.system.device");
    std::cout << "Device Name: " << getProperty("ro.product.name");
    std::cout << "Device System Name: " << getProperty("ro.product.system.name");
    std::cout << "Build Type: " << getProperty("ro.build.type");
    std::cout << "Hardware: " << getProperty("ro.hardware");
    std::cout << "CPU ABI: " << getProperty("ro.system.product.cpu.abilist");
    std::cout << "Manufacturer: " << getProperty("ro.product.manufacturer");
    std::cout << "Brand: " << getProperty("ro.product.brand");
    std::cout << "Model: " << getProperty("ro.product.model");
    std::cout << "Vendor Marketname: " << getProperty("ro.product.vendor.marketname");
    std::cout << "Board: " << getProperty("ro.product.board");
    std::cout << "Miui Resolution: " << getProperty("persist.sys.miui_resolution");
    std::cout << "Display Size: " << getProperty("ro.vendor.display.default_size");
    std::cout << "WLAN Chip: " << getProperty("ro.hardware.wlan.chip");
    std::cout << "Display Default FPS: " << getProperty("ro.vendor.display.default_fps");
    std::cout << "Locale: " << getProperty("ro.product.locale");
    std::cout << "dex2oat64: " << getProperty("dalvik.vm.dex2oat64.enabled");
    std::cout << "Zygote: " << getProperty("ro.zygote");
    std::cout << "OpenGL ES: " << getProperty("ro.opengles.version");
    std::cout << "CPU Variant: " << getProperty("ro.bionic.cpu_variant");
    std::cout << "CPU Variant2: " << getProperty("ro.bionic.2nd_cpu_variant");
    std::cout << "data df dev name: " << getProperty("persist.data.df.dev_name");
    std::cout << "GPU Profiler: " << getProperty("graphics.gpu.profiler.support");
    std::cout << "Subscription Type: " << getProperty("ril.subscription.types");
    std::cout << "NFC: " << getProperty("ro.nfc.port");
    std::cout << "Switch FPS: " << getProperty("ro.vendor.fps.switch.default");
    std::cout << "QCA1530: " << getProperty("sys.qca1530");
    std::cout << "Media Max FPS: " << getProperty("ro.media.recorder-max-base-layer-fps");
    std::cout << "SeLinux: " << getProperty("ro.secure");
    std::cout << "Kernel Page Size: " << getpagesize() << std::endl;

    return 0;
}
