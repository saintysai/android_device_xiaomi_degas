/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "degas-udfps-subhal"

#include "UdfpsSensorSubHal.h"

#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <log/log.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <utils/SystemClock.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <vector>

namespace android {
namespace hardware {
namespace sensors {
namespace V2_0 {
namespace subhal {
namespace implementation {
using ::android::hardware::sensors::V1_0::SensorFlagBits;
using ::android::hardware::sensors::V1_0::SensorType;

namespace {
constexpr uint16_t kFodKeyCode = 0x152;

constexpr const char* kTouchDeviceNames[] = {"goodix_ts", "focaltech_ts"};

static bool isTouchDevice(const char* name) {
    for (const char* candidate : kTouchDeviceNames) {
        if (strcmp(name, candidate) == 0) {
            return true;
        }
    }
    return false;
}
constexpr char kInputDir[] = "/dev/input";

constexpr float kFodCentreX = 610.0f;
constexpr float kFodCentreY = 2444.0f;
constexpr float kFodMajor = 105.0f;
constexpr float kFodMinor = 105.0f;

constexpr int kPollTimeoutMs = 250;
}

UdfpsSensorSubHal::UdfpsSensorSubHal() {
    mSensorInfo.sensorHandle = 1;
    mSensorInfo.name = "UDFPS Long Press";
    mSensorInfo.vendor = "Xiaomi";
    mSensorInfo.version = 1;
    mSensorInfo.type =
            static_cast<SensorType>(static_cast<int32_t>(SensorType::DEVICE_PRIVATE_BASE) + 1);
    mSensorInfo.typeAsString = "org.degas.sensor.udfps_long_press";
    mSensorInfo.maxRange = 2048.0f;
    mSensorInfo.resolution = 1.0f;
    mSensorInfo.power = 0.0f;

    mSensorInfo.minDelay = -1;
    mSensorInfo.maxDelay = 0;
    mSensorInfo.fifoReservedEventCount = 0;
    mSensorInfo.fifoMaxEventCount = 0;
    mSensorInfo.requiredPermission = "";
    mSensorInfo.flags = static_cast<uint32_t>(SensorFlagBits::ONE_SHOT_MODE) |
                        static_cast<uint32_t>(SensorFlagBits::WAKE_UP);
}

UdfpsSensorSubHal::~UdfpsSensorSubHal() {
    mArmed = false;
    stopReader();
}

Return<Result> UdfpsSensorSubHal::initialize(const sp<IHalProxyCallback>& halProxyCallback) {
    mArmed = false;
    stopReader();

    std::lock_guard<std::mutex> lock(mCallbackMutex);
    mCallback = halProxyCallback;
    return Result::OK;
}

Return<void> UdfpsSensorSubHal::getSensorsList(getSensorsList_cb _hidl_cb) {
    hidl_vec<SensorInfo> sensors{mSensorInfo};
    _hidl_cb(sensors);
    return Void();
}

Return<Result> UdfpsSensorSubHal::setOperationMode(OperationMode mode) {
    return mode == OperationMode::NORMAL ? Result::OK : Result::BAD_VALUE;
}

Return<Result> UdfpsSensorSubHal::activate(int32_t sensorHandle, bool enabled) {
    if (sensorHandle != mSensorInfo.sensorHandle) {
        return Result::BAD_VALUE;
    }

    if (enabled) {
        startReader();
        mArmed = true;
    } else {
        mArmed = false;
        stopReader();
    }

    return Result::OK;
}

Return<Result> UdfpsSensorSubHal::batch(int32_t sensorHandle, int64_t ,
                                        int64_t ) {
    return sensorHandle == mSensorInfo.sensorHandle ? Result::OK : Result::BAD_VALUE;
}

Return<Result> UdfpsSensorSubHal::flush(int32_t ) {
    return Result::BAD_VALUE;
}

Return<Result> UdfpsSensorSubHal::injectSensorData(const Event& ) {
    return Result::INVALID_OPERATION;
}

Return<void> UdfpsSensorSubHal::registerDirectChannel(const SharedMemInfo& ,
                                                      registerDirectChannel_cb _hidl_cb) {
    _hidl_cb(Result::INVALID_OPERATION, -1);
    return Void();
}

Return<Result> UdfpsSensorSubHal::unregisterDirectChannel(int32_t ) {
    return Result::INVALID_OPERATION;
}

Return<void> UdfpsSensorSubHal::configDirectReport(int32_t ,
                                                   int32_t ,
                                                   RateLevel ,
                                                   configDirectReport_cb _hidl_cb) {
    _hidl_cb(Result::INVALID_OPERATION, -1);
    return Void();
}

Return<void> UdfpsSensorSubHal::debug(const hidl_handle& fd,
                                      const hidl_vec<hidl_string>& ) {
    if (fd == nullptr || fd->numFds < 1) {
        return Void();
    }

    bool reading;
    {
        std::lock_guard<std::mutex> lock(mThreadMutex);
        reading = mThread.joinable();
    }
    dprintf(fd->data[0], "degas UDFPS long press: armed=%d, reading input=%d\n",
            mArmed ? 1 : 0, reading ? 1 : 0);
    return Void();
}

int UdfpsSensorSubHal::openTouchDevice() {
    DIR* dir = opendir(kInputDir);
    if (dir == nullptr) {
        ALOGE("failed to open %s: %s", kInputDir, strerror(errno));
        return -1;
    }

    int found = -1;
    while (dirent* entry = readdir(dir)) {
        if (strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }

        std::string path = std::string(kInputDir) + "/" + entry->d_name;
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            continue;
        }

        char name[80] = {};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && isTouchDevice(name)) {
            found = fd;
            break;
        }
        close(fd);
    }
    closedir(dir);

    if (found < 0) {
        ALOGE("no goodix_ts or focaltech_ts input device");
    }
    return found;
}

void UdfpsSensorSubHal::startReader() {
    std::lock_guard<std::mutex> lock(mThreadMutex);
    if (mThread.joinable()) {
        return;
    }

    int fd = openTouchDevice();
    if (fd < 0) {
        return;
    }

    mStop = false;
    mThread = std::thread(&UdfpsSensorSubHal::readerLoop, this, fd);
}

void UdfpsSensorSubHal::stopReader() {
    std::thread thread;
    {
        std::lock_guard<std::mutex> lock(mThreadMutex);
        if (!mThread.joinable()) {
            return;
        }
        mStop = true;
        thread = std::move(mThread);
    }

    thread.join();
}

void UdfpsSensorSubHal::readerLoop(int fd) {
    pollfd pfd = {.fd = fd, .events = POLLIN, .revents = 0};

    while (!mStop) {
        int ret = poll(&pfd, 1, kPollTimeoutMs);
        if (ret < 0) {
            if (errno == EINTR) {
                continue;
            }
            ALOGE("poll failed: %s", strerror(errno));
            break;
        }
        if (ret == 0) {
            continue;
        }

        input_event events[16];
        ssize_t bytes = read(fd, events, sizeof(events));
        if (bytes < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }
            ALOGE("read failed: %s", strerror(errno));
            break;
        }

        const size_t count = static_cast<size_t>(bytes) / sizeof(input_event);
        for (size_t i = 0; i < count; i++) {
            if (events[i].type == EV_KEY && events[i].code == kFodKeyCode &&
                events[i].value == 1) {
                reportPress();
                break;
            }
        }
    }

    close(fd);
}

void UdfpsSensorSubHal::reportPress() {
    if (!mArmed.exchange(false)) {
        return;
    }

    sp<IHalProxyCallback> callback;
    {
        std::lock_guard<std::mutex> lock(mCallbackMutex);
        callback = mCallback;
    }
    if (callback == nullptr) {
        return;
    }

    Event event{};
    event.sensorHandle = mSensorInfo.sensorHandle;
    event.sensorType = mSensorInfo.type;
    event.timestamp = ::android::elapsedRealtimeNano();
    event.u.data[0] = kFodCentreX;
    event.u.data[1] = kFodCentreY;
    event.u.data[2] = kFodMajor;
    event.u.data[3] = kFodMinor;

    ALOGI("reporting UDFPS press at %.0f,%.0f", kFodCentreX, kFodCentreY);

    std::vector<Event> events{event};
    callback->postEvents(events, callback->createScopedWakelock(true));
}
}
}
}
}
}
}

::android::hardware::sensors::V2_0::implementation::ISensorsSubHal* sensorsHalGetSubHal(
        uint32_t* version) {
    static ::android::hardware::sensors::V2_0::subhal::implementation::UdfpsSensorSubHal subHal;
    *version = SUB_HAL_2_0_VERSION;
    return &subHal;
}
