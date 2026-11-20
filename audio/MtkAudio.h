/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/vendor/mediatek/hardware/audio/BnMtkAudio.h>

#include <cstdint>

namespace aidl::vendor::mediatek::hardware::audio {
using ::aidl::vendor::mediatek::hardware::audio::IAudioParameterChangedCallback;
using ::aidl::vendor::mediatek::hardware::audio::Result;

class MtkAudio : public BnMtkAudio {
  public:
    MtkAudio();
    virtual ~MtkAudio();

    ndk::ScopedAStatus setAudioParameterChangedCallback(
            const std::shared_ptr<IAudioParameterChangedCallback>& callback, int32_t cbkKey,
            Result* pResult) override;

    ndk::ScopedAStatus clearAudioParameterChangedCallback(int32_t cbkKey, Result* pResult) override;

  private:

    alignas(8) uint8_t mPrebuiltStorage[512];
};
}
