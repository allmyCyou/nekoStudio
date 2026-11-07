# Version
- [android-release-version-app](https://github.com/deleteFAILunknown/nekoStudio/releases)
- [android-beta-version-app](https://github.com/deleteFAILunknown/nekoStudio/actions)

## System Support
- Android 17 - Android 7.0
- Android TV

## Mode
- [x] ROOT
- [x] Shell
- [x] adb
  - [x] pair
  - [x] connect
  - [x] disconnect
  - [x] shell
  - [x] --shell-exit
  - [x] root
  - [x] unroot
  - [x] install
  - [x] uninstall
  - [x] push
  - [x] pull
  - [x] reboot
  - [x] usb
  - [x] mdns
  - [x] abb
  - [ ] sideload
- [x] fastboot
  - [x] fastbootd
    - [x] flash
    - [x] boot
    - [x] getvar
    - [x] oem
    - [x] reboot
    - [x] erase
    - [x] format
    - [x] set active
- [x] 900e
  - [x] usb
    - [x] maxPower
    - [x] isSelfPowered
- [ ] 9008
- [ ] MTK Preloader
- [ ] MTK BROM

## su
- KernelSU、SukiSU
```shell
# Flashing non-vab devices
$ su -c cat /sdcard/boot.img > /dev/block/by-name/boot
$ su -c cat /sdcard/init_boot.img > /dev/block/by-name/init_boot

# Adding the -M parameter and using global root permissions to flash can solve the problem of insufficient permissions on most devices.
$ su -M -c cat /sdcard/boot.img > /dev/block/by-name/boot
$ su -M -c cat /sdcard/init_boot.img > /dev/block/by-name/init_boot

# Query the currently active slot before flashing the vab device
$ getprop ro.boot.slot_suffix

# Flash vab device partition _a
$ su -c cat /sdcard/boot.img > /dev/block/by-name/boot_a
$ su -c cat /sdcard/init_boot.img > /dev/block/by-name/init_boot_a

# Flash vab device partition _b
$ su -c cat /sdcard/boot.img > /dev/block/by-name/boot_b
$ su -c cat /sdcard/init_boot.img > /dev/block/by-name/init_boot_b
```

## DocumentsProvider
- You don't need to use MT Manager to inject a file provider for your APK to create the corresponding local storage directory

## Signature
- Now there are not only sample scripts in the project, but also built APKs, which use signature schemes v2, v3, v3.1, and v3.2 respectively.
- Hope this sample script can help you

## Verify signature
- V3.2 signature is only supported by JDK25
- Verify Release APK
```shell
$ ./apksigner verify -v --verbose app-release-sign.apk
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): true
Verified using v3.2 scheme (APK Signature Scheme v3.2): true
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
```
- Verify Debug APK
```shell
$ ./apksigner verify -v --verbose app-debug-sign.apk
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): true
Verified using v3.2 scheme (APK Signature Scheme v3.2): true
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
```

## Acknowledgements
- [Android](https://github.com/Android)
- [Kotlin-lang](https://github.com/jetbrains/kotlin)
- [Gradle-Builds](https://github.com/gradle/gradle)
- [Cmake](https://github.com/Kitware/CMake)
- [Kadb](https://github.com/flyfishxu/Kadb)
- [OpenSSL](https://github.com/openssl/openssl)
- [libsu](https://github.com/topjohnwu/libsu)
- [android-Kernel-su](https://github.com/tiann/KernelSU)
- [android-Hidden-api](https://github.com/LSPosed/AndroidHiddenApiBypass)
- [Termux-app](https://github.com/termux/termux-app)
- [Termux-ubuntu](https://github.com/termux/proot-distro)
- [android-sdk-aarch64](https://github.com/HomuHomu833/android-sdk-custom)
- [android-ndk-aarch64](https://github.com/HomuHomu833/android-ndk-custom)
- [MT-DocumentsProvider](https://github.com/L-JINBIN/MTDataFilesProvider)
- [Spake2-android](https://github.com/MuntashirAkon/spake2-java)