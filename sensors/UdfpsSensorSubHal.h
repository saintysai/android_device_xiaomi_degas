/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <V2_0/SubHal.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

namespace android {
namespace hardware {
namespace sensors {
namespace V2_0 {
namespace subhal {
namespace implementation {
using ::android::sp;
using ::android::hardware::Return;
using ::android::hardware::sensors::V1_0::Event;
using ::android::hardware::sensors::V1_0::OperationMode;
using ::android::hardware::sensors::V1_0::RateLevel;
using ::android::hardware::sensors::V1_0::Result;
using ::android::hardware::sensors::V1_0::SensorInfo;
using ::android::hardware::sensors::V1_0::SharedMemInfo;
using ::android::hardware::sensors::V2_0::implementation::IHalProxyCallback;
using ::android::hardware::sensors::V2_0::implementation::ISensorsSubHal;

class UdfpsSensorSubHal : public ISensorsSubHal {
  public:
    UdfpsSensorSubHal();
    ~UdfpsSensorSubHal();

    Return<void> getSensorsList(getSensorsList_cb _hidl_cb) override;
    Return<Result> setOperationMode(OperationMode mode) override;
    Return<Result> activate(int32_t sensorHandle, bool enabled) override;
    Return<Result> batch(int32_t sensorHandle, int64_t samplingPeriodNs,
                         int64_t maxReportLatencyNs) override;
    Return<Result> flush(int32_t sensorHandle) override;
    Return<Result> injectSensorData(const Event& event) override;
    Return<void> registerDirectChannel(const SharedMemInfo& mem,
                                       registerDirectChannel_cb _hidl_cb) override;
    Return<Result> unregisterDirectChannel(int32_t channelHandle) override;
    Return<void> configDirectReport(int32_t sensorHandle, int32_t channelHandle, RateLevel rate,
                                    configDirectReport_cb _hidl_cb) override;

    Return<void> debug(const hidl_handle& fd, const hidl_vec<hidl_string>& args) override;
    const std::string getName() override { return "degas-udfps"; }
    Return<Result> initialize(const sp<IHalProxyCallback>& halProxyCallback) override;

  private:

    static int openTouchDevice();

    void startReader();
    void stopReader();
    void readerLoop(int fd);
    void reportPress();

    SensorInfo mSensorInfo{};

    std::atomic<bool> mArmed{false};

    std::mutex mThreadMutex;
    std::thread mThread;
    std::atomic<bool> mStop{false};

    std::mutex mCallbackMutex;
    sp<IHalProxyCallback> mCallback;
};
}
}
}
}
}
}
