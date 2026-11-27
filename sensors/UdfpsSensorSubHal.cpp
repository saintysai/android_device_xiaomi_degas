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
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <utils/SystemClock.h>

#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
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
constexpr int32_t kUdfpsHandle = 1;
constexpr int32_t kDoubleTapHandle = 2;

constexpr uint16_t kFodKeyCode = 0x152;
constexpr int kMaxSlots = 16;

constexpr int64_t kMinTapDurationMs = 10;
constexpr int64_t kMaxTapDurationMs = 260;
constexpr int64_t kMinTapGapMs = 20;
constexpr int64_t kMaxDoubleTapWindowMs = 500;

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
}

UdfpsSensorSubHal::UdfpsSensorSubHal() {
    mSensorInfo.sensorHandle = kUdfpsHandle;
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

    mDoubleTapSensorInfo.sensorHandle = kDoubleTapHandle;
    mDoubleTapSensorInfo.name = "AOD Double Tap";
    mDoubleTapSensorInfo.vendor = "Xiaomi";
    mDoubleTapSensorInfo.version = 1;
    mDoubleTapSensorInfo.type =
            static_cast<SensorType>(static_cast<int32_t>(SensorType::DEVICE_PRIVATE_BASE) + 2);
    mDoubleTapSensorInfo.typeAsString = "org.degas.sensor.double_tap";
    mDoubleTapSensorInfo.maxRange = 1.0f;
    mDoubleTapSensorInfo.resolution = 1.0f;
    mDoubleTapSensorInfo.power = 0.0f;
    mDoubleTapSensorInfo.minDelay = -1;
    mDoubleTapSensorInfo.maxDelay = 0;
    mDoubleTapSensorInfo.fifoReservedEventCount = 0;
    mDoubleTapSensorInfo.fifoMaxEventCount = 0;
    mDoubleTapSensorInfo.requiredPermission = "";
    mDoubleTapSensorInfo.flags = static_cast<uint32_t>(SensorFlagBits::ONE_SHOT_MODE) |
                                 static_cast<uint32_t>(SensorFlagBits::WAKE_UP);
}

UdfpsSensorSubHal::~UdfpsSensorSubHal() {
    mArmed = false;
    mDoubleTapArmed = false;
    stopReader();
}

Return<Result> UdfpsSensorSubHal::initialize(const sp<IHalProxyCallback>& halProxyCallback) {
    mArmed = false;
    mDoubleTapArmed = false;
    stopReader();

    std::lock_guard<std::mutex> lock(mCallbackMutex);
    mCallback = halProxyCallback;
    return Result::OK;
}

Return<void> UdfpsSensorSubHal::getSensorsList(getSensorsList_cb _hidl_cb) {
    hidl_vec<SensorInfo> sensors{mSensorInfo, mDoubleTapSensorInfo};
    _hidl_cb(sensors);
    return Void();
}

Return<Result> UdfpsSensorSubHal::setOperationMode(OperationMode mode) {
    return mode == OperationMode::NORMAL ? Result::OK : Result::BAD_VALUE;
}

Return<Result> UdfpsSensorSubHal::activate(int32_t sensorHandle, bool enabled) {
    if (sensorHandle != kUdfpsHandle && sensorHandle != kDoubleTapHandle) {
        return Result::BAD_VALUE;
    }

    if (sensorHandle == kUdfpsHandle) {
        mArmed = enabled;
    } else {
        mDoubleTapArmed = enabled;
        if (enabled) {
            mDoubleTapResetGen.fetch_add(1, std::memory_order_relaxed);
        }
    }

    if (mArmed || mDoubleTapArmed) {
        if (!startReader()) {
            if (sensorHandle == kUdfpsHandle) {
                mArmed = false;
            } else {
                mDoubleTapArmed = false;
            }
            return Result::INVALID_OPERATION;
        }
    } else {
        stopReader();
    }

    return Result::OK;
}

Return<Result> UdfpsSensorSubHal::batch(int32_t sensorHandle, int64_t ,
                                        int64_t ) {
    return (sensorHandle == kUdfpsHandle || sensorHandle == kDoubleTapHandle) ? Result::OK
                                                                              : Result::BAD_VALUE;
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
        reading = mThread.joinable() && mRunning;
    }
    dprintf(fd->data[0],
            "degas touch subhal: udfps_armed=%d, double_tap_armed=%d, reading_input=%d\n",
            mArmed ? 1 : 0, mDoubleTapArmed ? 1 : 0, reading ? 1 : 0);
    return Void();
}

int UdfpsSensorSubHal::openTouchDevice() {
    DIR* dir = opendir(kInputDir);
    if (dir == nullptr) {
        ALOGE("failed to open %s: %s", kInputDir, strerror(errno));
        return -1;
    }

    int found = -1;
    dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }
        char path[64];
        snprintf(path, sizeof(path), "%s/%s", kInputDir, entry->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            continue;
        }
        char name[128] = {};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && isTouchDevice(name)) {
            ALOGI("watching %s (%s)", path, name);
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

bool UdfpsSensorSubHal::startReader() {
    std::lock_guard<std::mutex> lock(mThreadMutex);
    if (mThread.joinable()) {
        if (mRunning) {
            return true;
        }
        mThread.join();
        if (mWakeupFd >= 0) {
            close(mWakeupFd);
            mWakeupFd = -1;
        }
    }

    int inputFd = openTouchDevice();
    if (inputFd < 0) {
        return false;
    }

    int wakeupFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (wakeupFd < 0) {
        ALOGE("failed to create wakeup eventfd: %s", strerror(errno));
        close(inputFd);
        return false;
    }

    mWakeupFd = wakeupFd;
    mStop = false;
    mRunning = true;
    mThread = std::thread(&UdfpsSensorSubHal::readerLoop, this, inputFd, wakeupFd);
    return true;
}

void UdfpsSensorSubHal::stopReader() {
    std::thread thread;
    int wakeupFd = -1;
    {
        std::lock_guard<std::mutex> lock(mThreadMutex);
        if (!mThread.joinable()) {
            return;
        }
        mStop = true;
        wakeupFd = mWakeupFd;
        mWakeupFd = -1;
        thread = std::move(mThread);
    }

    if (wakeupFd >= 0) {
        const uint64_t wake = 1;
        ssize_t written;
        do {
            written = write(wakeupFd, &wake, sizeof(wake));
        } while (written < 0 && errno == EINTR);
        if (written < 0 && errno != EAGAIN) {
            ALOGW("failed to signal wakeup eventfd: %s", strerror(errno));
        }
    }

    thread.join();
    if (wakeupFd >= 0) {
        close(wakeupFd);
    }
}

void UdfpsSensorSubHal::readerLoop(int inputFd, int wakeupFd) {
    input_absinfo absX{};
    int32_t maxX = 121999;
    if (ioctl(inputFd, EVIOCGABS(ABS_MT_POSITION_X), &absX) >= 0 && absX.maximum > 0) {
        maxX = absX.maximum;
    }
    const int64_t tapMoveSlop = static_cast<int64_t>(maxX) * 8 / 100;
    const int64_t doubleTapSlop = static_cast<int64_t>(maxX) * 16 / 100;

    int32_t slotTrackingId[kMaxSlots];
    int32_t slotX[kMaxSlots] = {};
    int32_t slotY[kMaxSlots] = {};
    for (int i = 0; i < kMaxSlots; i++) {
        slotTrackingId[i] = -1;
    }
    int currentSlot = 0;
    bool sawTrackingId = false;
    bool btnTouch = false;

    bool wasTouching = false;
    bool hadMultiTouch = false;
    bool hadFod = false;
    int64_t downTimeNs = 0;
    int32_t downX = 0;
    int32_t downY = 0;
    int32_t lastX = 0;
    int32_t lastY = 0;

    bool hasFirstTap = false;
    int64_t firstTapDownNs = 0;
    int64_t firstTapUpNs = 0;
    int32_t firstTapX = 0;
    int32_t firstTapY = 0;
    uint32_t seenResetGen = mDoubleTapResetGen.load(std::memory_order_relaxed);

    pollfd pfds[2] = {
            {.fd = inputFd, .events = POLLIN, .revents = 0},
            {.fd = wakeupFd, .events = POLLIN, .revents = 0},
    };

    while (!mStop) {
        pfds[0].revents = 0;
        pfds[1].revents = 0;
        int ret = poll(pfds, 2, -1);
        if (ret < 0) {
            if (errno == EINTR) {
                continue;
            }
            ALOGE("poll failed: %s", strerror(errno));
            break;
        }
        if (mStop || (pfds[1].revents & POLLIN)) {
            break;
        }
        if (pfds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            ALOGE("input fd error state: revents=0x%x",
                  static_cast<unsigned int>(pfds[0].revents));
            break;
        }
        if (!(pfds[0].revents & POLLIN)) {
            continue;
        }

        input_event events[32];
        ssize_t bytes = read(inputFd, events, sizeof(events));
        if (bytes < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }
            ALOGE("read failed: %s", strerror(errno));
            break;
        }
        if (bytes == 0) {
            ALOGE("unexpected EOF on input fd");
            break;
        }

        const size_t count = static_cast<size_t>(bytes) / sizeof(input_event);
        for (size_t i = 0; i < count; i++) {
            const auto& ev = events[i];
            if (ev.type == EV_KEY) {
                if (ev.code == kFodKeyCode && ev.value == 1) {
                    hadFod = true;
                    hasFirstTap = false;
                    reportPress();
                } else if (ev.code == BTN_TOUCH) {
                    btnTouch = (ev.value != 0);
                }
            } else if (ev.type == EV_ABS) {
                switch (ev.code) {
                    case ABS_MT_SLOT:
                        if (ev.value >= 0 && ev.value < kMaxSlots) {
                            currentSlot = ev.value;
                        }
                        break;
                    case ABS_MT_TRACKING_ID:
                        sawTrackingId = true;
                        slotTrackingId[currentSlot] = ev.value;
                        break;
                    case ABS_MT_POSITION_X:
                        slotX[currentSlot] = ev.value;
                        break;
                    case ABS_MT_POSITION_Y:
                        slotY[currentSlot] = ev.value;
                        break;
                }
            } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                const uint32_t gen = mDoubleTapResetGen.load(std::memory_order_relaxed);
                if (gen != seenResetGen) {
                    seenResetGen = gen;
                    hasFirstTap = false;
                    wasTouching = false;
                }

                int activeCount = 0;
                int primarySlot = 0;
                if (sawTrackingId) {
                    for (int s = 0; s < kMaxSlots; s++) {
                        if (slotTrackingId[s] >= 0) {
                            if (activeCount == 0) {
                                primarySlot = s;
                            }
                            activeCount++;
                        }
                    }
                } else if (btnTouch) {
                    activeCount = 1;
                    primarySlot = 0;
                }

                const bool nowTouching = activeCount > 0;
                const int32_t curX = slotX[primarySlot];
                const int32_t curY = slotY[primarySlot];

                if (!wasTouching && nowTouching) {
                    downTimeNs = ::android::elapsedRealtimeNano();
                    downX = curX;
                    downY = curY;
                    lastX = curX;
                    lastY = curY;
                    hadMultiTouch = (activeCount > 1);
                    hadFod = false;
                } else if (wasTouching && nowTouching) {
                    lastX = curX;
                    lastY = curY;
                    if (activeCount > 1) {
                        hadMultiTouch = true;
                    }
                } else if (wasTouching && !nowTouching) {
                    const int64_t upTimeNs = ::android::elapsedRealtimeNano();
                    const int64_t durationMs = (upTimeNs - downTimeNs) / 1000000LL;
                    const int64_t dx = std::llabs(static_cast<int64_t>(lastX) - downX);
                    const int64_t dy = std::llabs(static_cast<int64_t>(lastY) - downY);

                    const bool isCleanTap = !hadMultiTouch && !hadFod &&
                                            durationMs >= kMinTapDurationMs &&
                                            durationMs <= kMaxTapDurationMs &&
                                            dx <= tapMoveSlop && dy <= tapMoveSlop;
                    if (isCleanTap && mDoubleTapArmed) {
                        if (hasFirstTap) {
                            const int64_t gapMs = (downTimeNs - firstTapUpNs) / 1000000LL;
                            const int64_t totalMs = (upTimeNs - firstTapDownNs) / 1000000LL;
                            const int64_t ddx =
                                    std::llabs(static_cast<int64_t>(downX) - firstTapX);
                            const int64_t ddy =
                                    std::llabs(static_cast<int64_t>(downY) - firstTapY);
                            if (gapMs >= kMinTapGapMs && totalMs <= kMaxDoubleTapWindowMs &&
                                ddx <= doubleTapSlop && ddy <= doubleTapSlop) {
                                hasFirstTap = false;
                                reportDoubleTap();
                            } else {
                                firstTapDownNs = downTimeNs;
                                firstTapUpNs = upTimeNs;
                                firstTapX = downX;
                                firstTapY = downY;
                            }
                        } else {
                            hasFirstTap = true;
                            firstTapDownNs = downTimeNs;
                            firstTapUpNs = upTimeNs;
                            firstTapX = downX;
                            firstTapY = downY;
                        }
                    } else {
                        hasFirstTap = false;
                    }
                }
                wasTouching = nowTouching;
            }
        }
    }

    mRunning = false;
    close(inputFd);
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

void UdfpsSensorSubHal::reportDoubleTap() {
    if (!mDoubleTapArmed.exchange(false)) {
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
    event.sensorHandle = mDoubleTapSensorInfo.sensorHandle;
    event.sensorType = mDoubleTapSensorInfo.type;
    event.timestamp = ::android::elapsedRealtimeNano();
    event.u.data[0] = 1.0f;

    ALOGI("reporting AOD double tap");

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
