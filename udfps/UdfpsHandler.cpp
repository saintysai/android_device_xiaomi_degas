/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "UdfpsHandler.degas"

#include <aidl/android/hardware/biometrics/fingerprint/BnFingerprint.h>
#include <android-base/logging.h>
#include <android-base/unique_fd.h>

#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <atomic>
#include <bitset>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>

#include "UdfpsHandler.h"
#include "mi_disp.h"
#include "xiaomi_touch.h"

#define COMMAND_NIT 10
#define TARGET_BRIGHTNESS_OFF 0
#define TARGET_BRIGHTNESS_1000NIT 1

#define COMMAND_FOD_PRESS_STATUS 1
#define PARAM_FOD_PRESSED 1
#define PARAM_FOD_RELEASED 0

#define TOUCH_DEV_PATH "/dev/xiaomi-touch"
#define TOUCH_MAGIC 'T'
#define TOUCH_IOC_SET_CUR_VALUE _IO(TOUCH_MAGIC, SET_CUR_VALUE)
#define TOUCH_IOC_GET_CUR_VALUE _IO(TOUCH_MAGIC, GET_CUR_VALUE)

#define DISP_FEATURE_PATH "/dev/mi_display/disp_feature"

#define FOD_ARM_DELAY_US (80 * 1000)

#define FOD_STUCK_WATCHDOG_US (1000 * 1000)
#define FOD_OFF_REASSERT_US (150 * 1000)

using ::aidl::android::hardware::biometrics::fingerprint::AcquiredInfo;

namespace {
constexpr size_t kMaxDispEventLength = 4096;

static ssize_t readNoIntr(int fd, void* buf, size_t count) {
    ssize_t rc;
    do {
        rc = read(fd, buf, count);
    } while (rc < 0 && errno == EINTR);
    return rc;
}

static std::shared_ptr<disp_event_resp> parseDispEvent(int fd) {
    disp_event header;
    ssize_t headerSize = readNoIntr(fd, &header, sizeof(header));
    if (headerSize != static_cast<ssize_t>(sizeof(header))) {
        LOG(ERROR) << "unexpected display event header size: " << headerSize;
        return nullptr;
    }

    if (header.length < sizeof(header) || header.length > kMaxDispEventLength) {
        LOG(ERROR) << "invalid display event length: " << header.length;
        return nullptr;
    }

    std::shared_ptr<disp_event_resp> response(static_cast<disp_event_resp*>(malloc(header.length)),
                                              free);
    if (!response) {
        LOG(ERROR) << "failed to allocate memory for display event response";
        return nullptr;
    }
    response->base = header;

    const size_t dataLength = header.length - sizeof(header);
    if (dataLength > 0) {
        ssize_t dataSize = readNoIntr(fd, &response->data, dataLength);
        if (dataSize != static_cast<ssize_t>(dataLength)) {
            LOG(ERROR) << "unexpected display event data size: " << dataSize;
            return nullptr;
        }
    }

    return response;
}

struct disp_base displayBasePrimary = {
        .flag = 0,
        .disp_id = MI_DISP_PRIMARY,
};
}

class XiaomiDegasUdfpsHandler : public UdfpsHandler {
  public:
    void init(fingerprint_device_t* device) {
        mDevice = device;
        touch_fd_ = android::base::unique_fd(open(TOUCH_DEV_PATH, O_RDWR | O_CLOEXEC));
        disp_fd_ = android::base::unique_fd(open(DISP_FEATURE_PATH, O_RDWR | O_CLOEXEC));

        std::thread([this]() {
            android::base::unique_fd fd(open(DISP_FEATURE_PATH, O_RDWR | O_CLOEXEC));
            if (fd < 0) {
                LOG(ERROR) << "failed to open " << DISP_FEATURE_PATH << ": " << strerror(errno);
                mEventThreadBroken = true;
                return;
            }

            struct disp_event_req displayEventRequest = {
                    .base = displayBasePrimary,
                    .type = MI_DISP_EVENT_FOD,
            };
            if (ioctl(fd.get(), MI_DISP_IOCTL_REGISTER_EVENT, &displayEventRequest) < 0) {
                LOG(ERROR) << "failed to register FOD event: " << strerror(errno);
                mEventThreadBroken = true;
                return;
            }

            struct pollfd dispEventPoll = {
                    .fd = fd.get(),
                    .events = POLLIN,
                    .revents = 0,
            };

            int failures = 0;
            while (true) {
                int rc = poll(&dispEventPoll, 1, -1);
                if (rc < 0) {
                    if (errno == EINTR) {
                        continue;
                    }
                    LOG(ERROR) << "failed to poll " << DISP_FEATURE_PATH << ": " << strerror(errno)
                               << ", giving up (fallback NIT in use)";
                    mEventThreadBroken = true;
                    return;
                }

                if (dispEventPoll.revents & (POLLERR | POLLHUP | POLLNVAL)) {
                    LOG(ERROR) << "disp event fd in error state, revents: " << std::hex
                               << dispEventPoll.revents << ", giving up (fallback NIT in use)";
                    mEventThreadBroken = true;
                    return;
                }

                std::shared_ptr<disp_event_resp> response = parseDispEvent(fd.get());
                if (!response) {
                    if (++failures >= 10) {
                        LOG(ERROR) << "too many malformed disp events, giving up "
                                      "(fallback NIT in use)";
                        mEventThreadBroken = true;
                        return;
                    }
                    continue;
                }
                failures = 0;

                if (response->base.type != MI_DISP_EVENT_FOD) {
                    LOG(ERROR) << "unexpected display event: " << response->base.type;
                    continue;
                }

                if (response->base.length <= sizeof(response->base)) {
                    LOG(ERROR) << "display FOD event missing payload";
                    continue;
                }

                int value = response->data[0];
                LOG(DEBUG) << "received data: " << std::bitset<8>(value);

                bool localHbmUiReady = value & LOCAL_HBM_UI_READY;

                mDevice->extCmd(
                        mDevice, COMMAND_NIT,
                        localHbmUiReady ? TARGET_BRIGHTNESS_1000NIT : TARGET_BRIGHTNESS_OFF);
            }
        }).detach();
    }

    void onFingerDown(uint32_t x, uint32_t y, float , float ) {
        LOG(INFO) << __func__ << " x: " << x << ", y: " << y;

        uint64_t generation = ++mFodGeneration;
        mFingerDown = true;

        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_STATUS, PARAM_FOD_PRESSED);

        setLocalHbm(LHBM_TARGET_BRIGHTNESS_WHITE_1000NIT);

        setFingerDown(true);

        if (mEventThreadBroken) {
            std::thread([this, generation]() {
                usleep(FOD_ARM_DELAY_US);
                if (mFodGeneration != generation) {
                    LOG(INFO) << "stale FOD arm, not lighting LHBM";
                    return;
                }
                mDevice->extCmd(mDevice, COMMAND_NIT, TARGET_BRIGHTNESS_1000NIT);
            }).detach();
        }

        std::thread([this, generation]() {
            usleep(FOD_STUCK_WATCHDOG_US);
            if (mFodGeneration != generation || !mFingerDown) {
                return;
            }
            LOG(INFO) << "FOD watchdog: no finger-up, forcing LHBM off";
            mFingerDown = false;
            setLocalHbm(LHBM_TARGET_BRIGHTNESS_OFF_FINGER_UP);
            if (mEventThreadBroken) {
                mDevice->extCmd(mDevice, COMMAND_NIT, TARGET_BRIGHTNESS_OFF);
            }
            mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_STATUS, PARAM_FOD_RELEASED);
            setFingerDown(false);
        }).detach();
    }

    void onFingerUp() {
        LOG(INFO) << __func__;

        uint64_t generation = ++mFodGeneration;
        bool wasDown = mFingerDown.exchange(false);

        mDevice->extCmd(mDevice, COMMAND_FOD_PRESS_STATUS, PARAM_FOD_RELEASED);

        setLocalHbm(LHBM_TARGET_BRIGHTNESS_OFF_FINGER_UP);

        if (mEventThreadBroken) {
            mDevice->extCmd(mDevice, COMMAND_NIT, TARGET_BRIGHTNESS_OFF);
        }

        setFingerDown(false);

        if (wasDown) {
            std::thread([this, generation]() {
                usleep(FOD_OFF_REASSERT_US);
                if (mFodGeneration != generation) {
                    return;
                }
                setLocalHbm(LHBM_TARGET_BRIGHTNESS_OFF_FINGER_UP);
                if (mEventThreadBroken) {
                    mDevice->extCmd(mDevice, COMMAND_NIT, TARGET_BRIGHTNESS_OFF);
                }
            }).detach();
        }
    }

    void onAcquired(int32_t result, int32_t vendorCode) {
        LOG(DEBUG) << __func__ << " result: " << result << " vendorCode: " << vendorCode;
        switch (static_cast<AcquiredInfo>(result)) {
            case AcquiredInfo::GOOD:
            case AcquiredInfo::PARTIAL:
            case AcquiredInfo::INSUFFICIENT:
            case AcquiredInfo::SENSOR_DIRTY:
            case AcquiredInfo::TOO_SLOW:
            case AcquiredInfo::TOO_FAST:
            case AcquiredInfo::TOO_DARK:
            case AcquiredInfo::TOO_BRIGHT:
            case AcquiredInfo::IMMOBILE:
            case AcquiredInfo::LIFT_TOO_SOON:
                onFingerUp();
                break;
            default:
                break;
        }

        if (vendorCode == 21 || vendorCode == 22) {
            setFingerDown(true);
        }
    }

    void cancel() {
        LOG(INFO) << __func__;
        onFingerUp();
    }

  private:
    fingerprint_device_t* mDevice{nullptr};
    std::mutex mFdMutex;
    android::base::unique_fd touch_fd_;
    android::base::unique_fd disp_fd_;
    std::atomic<bool> mEventThreadBroken{false};

    std::atomic<uint64_t> mFodGeneration{0};
    std::atomic<bool> mFingerDown{false};

    void setLocalHbm(uint32_t value) {
        std::lock_guard<std::mutex> lock(mFdMutex);
        if (disp_fd_.get() < 0) {
            disp_fd_.reset(open(DISP_FEATURE_PATH, O_RDWR | O_CLOEXEC));
            if (disp_fd_.get() < 0) {
                return;
            }
        }
        struct disp_local_hbm_req req = {
                .base = displayBasePrimary,
                .local_hbm_value = value,
        };
        ioctl(disp_fd_.get(), MI_DISP_IOCTL_SET_LOCAL_HBM, &req);
    }

    void setFingerDown(bool pressed) {
        std::lock_guard<std::mutex> lock(mFdMutex);
        if (touch_fd_.get() < 0) {
            touch_fd_.reset(open(TOUCH_DEV_PATH, O_RDWR | O_CLOEXEC));
            if (touch_fd_.get() < 0) {
                return;
            }
        }
        int buf[MAX_BUF_SIZE] = {MI_DISP_PRIMARY, Touch_Fod_Enable, pressed ? 1 : 0};
        ioctl(touch_fd_.get(), TOUCH_IOC_SET_CUR_VALUE, &buf);
    }
};

static UdfpsHandler* create() {
    return new XiaomiDegasUdfpsHandler();
}

static void destroy(UdfpsHandler* handler) {
    delete handler;
}

extern "C" UdfpsHandlerFactory UDFPS_HANDLER_FACTORY = {
        .create = create,
        .destroy = destroy,
};
