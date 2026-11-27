/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "mtkaudiohalservice"

#include <signal.h>
#include <string>
#include <vector>

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android-base/logging.h>
#include <binder/ProcessState.h>
#include <cutils/properties.h>
#include <dlfcn.h>
#include <hidl/HidlTransportSupport.h>
#include <hidl/LegacySupport.h>
#include <hwbinder/ProcessState.h>

#include "SoundTriggerHw.h"
#include "MtkAudio.h"

using namespace android::hardware;
using android::OK;

using InterfacesList = std::vector<std::string>;

using ::aidl::android::hardware::soundtrigger3::SoundTriggerHw;
using ::aidl::vendor::mediatek::hardware::audio::MtkAudio;

template <class Iter>
static bool registerMandatoryPassthroughServiceImplementations(Iter first, Iter last) {
    bool ret = false;
    for (; first != last; ++first) {
        ret |= (registerPassthroughServiceImplementation(*first) == OK);
    }
    return ret;
}

static bool registerExternalServiceImplementation(const std::string& libName,
                                                  const std::string& funcName) {
    constexpr int dlMode = RTLD_LAZY;
    void* handle = nullptr;
    dlerror();
    auto libPath = libName + ".so";
    handle = dlopen(libPath.c_str(), dlMode);
    if (handle == nullptr) {
        const char* error = dlerror();
        ALOGE("Failed to dlopen %s: %s", libPath.c_str(),
              error != nullptr ? error : "unknown error");
        return false;
    }
    binder_status_t (*factoryFunction)();
    *(void**)(&factoryFunction) = dlsym(handle, funcName.c_str());
    if (!factoryFunction) {
        const char* error = dlerror();
        ALOGE("Factory function %s not found in libName %s: %s", funcName.c_str(), libPath.c_str(),
              error != nullptr ? error : "unknown error");
        dlclose(handle);
        return false;
    }
    if ((*factoryFunction)() != STATUS_OK) {
        dlclose(handle);
        return false;
    }
    return true;
}

int main(int , char* []) {
    signal(SIGPIPE, SIG_IGN);

    if (::android::ProcessState::isVndservicemanagerEnabled()) {
        ::android::ProcessState::initWithDriver("/dev/vndbinder");
        ::android::ProcessState::self()->startThreadPool();
    }

    ABinderProcess_setThreadPoolMaxThreadCount(1);
    ABinderProcess_startThreadPool();

    const int32_t defaultValue = -1;
    int32_t value =
            property_get_int32("persist.vendor.audio.service.hwbinder.size_kbyte", defaultValue);
    if (value > 0 && value <= 16384) {
        ALOGD("Configuring hwbinder with mmap size %d KBytes", value);
        ProcessState::initWithMmapSize(static_cast<size_t>(value) * 1024);
    }
    configureRpcThreadpool(16, true );

    const std::vector<InterfacesList> mandatoryInterfaces = {
        {
            "Audio Core API",
            "android.hardware.audio@7.1::IDevicesFactory",
        },
        {
            "Audio Effect API",
            "android.hardware.audio.effect@7.0::IEffectsFactory",
        }
    };

    const std::vector<std::pair<std::string,std::string>> optionalInterfaceSharedLibs = {
        {
            "android.hardware.bluetooth.audio-impl",
            "createIBluetoothAudioProviderFactory",
        }
    };

    for (const auto& listIter : mandatoryInterfaces) {
        auto iter = listIter.begin();
        const std::string& interfaceFamilyName = *iter++;
        LOG_ALWAYS_FATAL_IF(
                !registerMandatoryPassthroughServiceImplementations(iter, listIter.end()),
                "Could not register %s", interfaceFamilyName.c_str());
    }

    for (const auto& interfacePair : optionalInterfaceSharedLibs) {
        const std::string& libraryName = interfacePair.first;
        const std::string& interfaceLoaderFuncName = interfacePair.second;
        if (registerExternalServiceImplementation(libraryName, interfaceLoaderFuncName)) {
            ALOGI("%s() from %s success", interfaceLoaderFuncName.c_str(), libraryName.c_str());
        } else {
            ALOGW("%s() from %s failed", interfaceLoaderFuncName.c_str(), libraryName.c_str());
        }
    }

    std::shared_ptr<SoundTriggerHw> mtkSoundTriggerHw = ndk::SharedRefBase::make<SoundTriggerHw>();
    const std::string soundTriggerHw_instance =
            std::string() + SoundTriggerHw::descriptor + "/default";
    binder_status_t soundTriggerHw_status = AServiceManager_addService(
            mtkSoundTriggerHw->asBinder().get(), soundTriggerHw_instance.c_str());
    CHECK_EQ(soundTriggerHw_status, STATUS_OK);

    std::shared_ptr<MtkAudio> mtkAudio = ndk::SharedRefBase::make<MtkAudio>();
    const std::string instance = std::string() + MtkAudio::descriptor + "/default";
    binder_status_t mtkAudio_status =
            AServiceManager_addService(mtkAudio->asBinder().get(), instance.c_str());
    CHECK_EQ(mtkAudio_status, STATUS_OK);

    joinRpcThreadpool();
}
