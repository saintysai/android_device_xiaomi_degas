#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2024 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'device/xiaomi/degas',
    'hardware/mediatek',
    'hardware/xiaomi'
]

def lib_fixup_vendor_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}-{partition}' if partition == 'vendor' else None


lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    ('libtflite_mtk',
     'vendor.mediatek.hardware.apuware.utils-V1-ndk',
     'vendor.mediatek.hardware.apuware.utils@2.0',
     'vendor.mediatek.hardware.videotelephony-V1-ndk'): lib_fixup_vendor_suffix,
    # Also shipped by odm or system_ext under the same name;
    # the vendor copy is NAME-vendor (see proprietary-files.txt).
    ('libHISCppAlgos',
     'libcameraopt',
     'libcom.xiaomi.hwadapter.decoupleutil',
     'libcom.xiaomi.hwadapter.postprocinterface',
     'libhis_face_tracking',
     'libhis_motion_tracker',
     'libmialgo_aio_seg',
     'libmialgo_utils',
     'libremosaic_wrapper',
     'vendor.mediatek.hardware.bluetooth.audio-V1-ndk',
     'vendor.mediatek.hardware.mbrain-V2-ndk',
     'vendor.mediatek.hardware.pq_aidl-V7-ndk',
     'vendor.xiaomi.hw.touchfeature-V1-ndk'): lib_fixup_vendor_suffix,
}


blob_fixups: blob_fixups_user_type = {
    'vendor/lib64/hw/audio.primary.mediatek.so': blob_fixup()
        .replace_needed('libalsautils.so', 'libalsautils-v34.so')
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),

    'vendor/lib64/hw/android.hardware.audio@7.1-impl-mediatek.so': blob_fixup()
        .replace_needed('android.hardware.audio@7.1-util.so', 'android.hardware.audio@7.1-util-v34.so'),

    'vendor/lib64/hw/android.hardware.soundtrigger3-impl.so': blob_fixup()
        .replace_needed('libaudio_aidl_conversion_common_ndk.so', 'libaudio_aidl_conversion_common_ndk-v34.so'),

    'vendor/lib64/vendor.mediatek.hardware.bluetooth.audio-V1-ndk.so': blob_fixup()
        .replace_needed('android.hardware.audio.common-V1-ndk.so', 'android.hardware.audio.common-V2-ndk.so'),

    ('vendor/etc/init/hw/init.batterysecret.rc',
     'vendor/etc/init/hw/init.mi_thermald.rc'): blob_fixup()
        .regex_replace('.*seclabel.*\n', ''),

    ('vendor/lib64/libarmnn_ndk.mtk.vndk.so',
     'vendor/lib64/mt6897/lib3a.ae.stat.so'): blob_fixup()
        .add_needed('liblog.so'),

    'vendor/lib64/mt6897/libmtkcam_hal_aidl_common.so': blob_fixup()
        .replace_needed('android.hardware.camera.common-V2-ndk.so', 'android.hardware.camera.common-V1-ndk.so'),

    ('vendor/lib64/mt6897/libmtkcam_grallocutils.so',
     'vendor/lib64/libmtkcam_grallocutils_aidlv1helper.so'): blob_fixup()
        .replace_needed('android.hardware.graphics.allocator-V1-ndk.so', 'android.hardware.graphics.allocator-V2-ndk.so')
        .replace_needed('android.hardware.graphics.common-V4-ndk.so', 'android.hardware.graphics.common-V7-ndk.so')
        # same libui unification as the camera blobs below
        .replace_needed('libui.so', 'libui-v34.so'),

    ('odm/lib64/libTrueSight.so',
     'odm/lib64/libalLDC.so',
     'odm/lib64/libalAILDC.so',
     'odm/lib64/libalhLDC.so',
     'vendor/lib64/libMiPhotoFilter.so',
     'vendor/lib64/mt6897/libneuralnetworks_sl_driver_mtk_prebuilt.so'): blob_fixup()
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_createFromHandle')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_getNativeHandle')
        .clear_symbol_version('AHardwareBuffer_isSupported')
        .clear_symbol_version('AHardwareBuffer_lock')
        .clear_symbol_version('AHardwareBuffer_lockPlanes')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock'),

    'vendor/lib64/mt6897/libmtkcam_hwnode.jpegnode.so': blob_fixup()
        .replace_needed('libultrahdr.so', 'libultrahdr-v34.so'),

    (
        'odm/lib64/camera/plugins/com.xiaomi.plugin.gainmap.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.jpegrAggr.so',
    ): blob_fixup()
        .replace_needed('libultrahdr.so', 'libultrahdr-v34.so'),

    'vendor/lib64/libultrahdr-v34.so': blob_fixup()
        .replace_needed('libjpegencoder.so', 'libjpegencoder-v34.so')
        .replace_needed('libjpegdecoder.so', 'libjpegdecoder-v34.so'),

    ('vendor/bin/hw/mt6897/android.hardware.graphics.allocator-V2-service-mediatek.mt6897',
     'vendor/lib64/egl/mt6897/libGLES_mali.so',
     'vendor/lib64/hw/mt6897/android.hardware.graphics.allocator-V2-mediatek.so',
     'vendor/lib64/hw/mt6897/android.hardware.graphics.mapper@4.0-impl-mediatek.so',
     'vendor/lib64/libaimemc.so',
     'vendor/lib64/vendor.mediatek.hardware.camera.isphal-V1-ndk.so',
     'vendor/lib64/vendor.mediatek.hardware.pq_aidl-V2-ndk.so',
     'vendor/lib64/vendor.mediatek.hardware.pq_aidl-V4-ndk.so',
     'vendor/lib64/vendor.mediatek.hardware.pq_aidl-V6-ndk.so',
     'vendor/lib64/vendor.mediatek.hardware.pq_aidl-V7-ndk.so'): blob_fixup()
        .replace_needed('android.hardware.graphics.common-V4-ndk.so', 'android.hardware.graphics.common-V7-ndk.so'),

    # Silence per-frame GrallocMapperV5::getMetadata Mapper error: 7 logd spam on A17 SF
    'vendor/lib64/hw/mt6897/mapper.mediatek.so': blob_fixup()
        .replace_needed('android.hardware.graphics.common-V4-ndk.so', 'android.hardware.graphics.common-V7-ndk.so')
        .binary_regex_replace(
            b'\xc0\x00\x80\x52\xe1\x03\x1f\xaa\xe3\x03\x15\x2a\xb6\x52\x00\x94',
            b'\xc0\x00\x80\x52\xe1\x03\x1f\xaa\xe3\x03\x15\x2a\x1f\x20\x03\xd5',
        ),

    'vendor/lib64/mt6897/libpqconfig.so': blob_fixup()
        .replace_needed('android.hardware.sensors-V2-ndk.so', 'android.hardware.sensors-V3-ndk.so'),

    ('vendor/etc/init/android.hardware.graphics.allocator-V2-service-mediatek.rc',
     'vendor/etc/init/android.hardware.graphics.composer@3.2-service.rc' ): blob_fixup()
        .regex_replace('.*writepid.*\n', ''),

    'vendor/lib64/hw/mt6897/vendor.mediatek.hardware.pq_aidl-impl.so': blob_fixup()
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so')
        .replace_needed('libui.so', 'libui-v34.so'),

    # GraphicBufferMapper::lock/unlock (old libui API removed in A16).
    #
    # mihal drags the rest of the camera stack with it: everything below shares
    # android::GraphicBuffer objects with it, and a process holding both libui
    # and libui-v34 ends up with two layouts of that class under the same symbol
    # names. The mismatch corrupts the heap in the vendor tract - caught with
    # malloc_debug guard on 2026-08-16 (CORRUPTED REAR GUARD in
    # RealtimePostProcessor::deleteRecord) and gone once these are relinked.
    'vendor/lib64/libmicamera_hal_core.so': blob_fixup()
        .replace_needed('libui.so', 'libui-v34.so')
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),

    ('vendor/lib64/libcom.xiaomi.ecoenginemonitor.so',
     'vendor/lib64/libcom.xiaomi.grallocutils.so',
     'vendor/lib64/libcom.xiaomi.ionutils.so',
     'vendor/lib64/libcom.xiaomi.pluginutils.so',
     'vendor/lib64/libcom.xiaomi.sensorpolicy.so',
     'vendor/lib64/libecoengine.so',
     'vendor/lib64/libmialgoengine.so',
     'vendor/lib64/mt6897/libmtkcam_thirdparty.customer.so',
     'odm/lib64/camera/plugins/com.xiaomi.plugin.beautydeformation.so',
     'odm/lib64/camera/plugins/com.xiaomi.plugin.filter.so',
     'odm/lib64/camera/plugins/com.xiaomi.plugin.videofilter.so'): blob_fixup()
        .replace_needed('libui.so', 'libui-v34.so'),

    # plain-C SetTaskProfiles came from VNDK v34 libprocessgroup on stock
    'vendor/lib64/libcameraopt.so': blob_fixup()
        .add_needed('libprocessgroup_shim.so'),

    # libmiface carries a vestigial DT_NEEDED on libdumpstateutil, which is a
    # /system library the vendor namespace cannot reach. It imports ZERO symbols
    # from it (checked: 0 of its 33 exports are undefined in libmiface), so the
    # entry is simply dropped instead of shipping a system lib into /vendor.
    'vendor/lib64/libmiface.so': blob_fixup()
        .remove_needed('libdumpstateutil.so')
        .replace_needed('libcamera2ndk_vendor.so', 'libcamera2ndk_vendor-v34.so'),

    # remosaic_process_gain_set exporter must be loaded first (BIND_NOW)
    'odm/lib64/libmiremosaic.so': blob_fixup()
        .add_needed('libremosaic_wrapper.so'),

    ('odm/lib64/camera/plugins/com.xiaomi.plugin.mialgoainr.so',
     'odm/lib64/hw/displayfeature.default.so',
     'vendor/lib64/libHardwareBacklightcore.so',
     'vendor/lib64/libaudiocloudctrl.so',
     'vendor/lib64/libmicamera_aidl_provider.so',
     'vendor/lib64/libpqxmlflagparser.so',
     'vendor/lib64/libpqxmlparser.so',
     'vendor/lib64/librt_extamp_intf.so',
     'vendor/lib64/libsilkybrightnesscore.so',
     'vendor/lib64/mt6897/libmmlpqImpl.so'): blob_fixup()
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),

    # Android 17 Keymint V3 split (uses AOSP system/keymaster *_V3 + stock libkeymint_V3)
    'vendor/bin/hw/android.hardware.security.keymint@3.0-service.mitee': blob_fixup()
        .replace_needed('lib_android_keymaster_keymint_utils.so', 'lib_android_keymaster_keymint_utils_V3.so')
        .replace_needed('libkeymint.so', 'libkeymint_V3.so'),

    'vendor/lib64/libkeymint_V3.so': blob_fixup()
        .replace_needed('lib_android_keymaster_keymint_utils.so', 'lib_android_keymaster_keymint_utils_V3.so'),

    'vendor/lib64/libkeymint_remote_prov_support_V3.so': blob_fixup()
        .replace_needed('libbase.so', 'libbase-v34.so')
        .add_needed('libcppcose_rkp-V3.so'),

    'odm/lib64/libmt_mitee.so': blob_fixup()
        .replace_needed('lib_android_keymaster_keymint_utils.so', 'lib_android_keymaster_keymint_utils_V3.so')
        .replace_needed('libkeymint.so', 'libkeymint_V3.so')
        .replace_needed('libkeymint_remote_prov_support.so', 'libkeymint_remote_prov_support_V3.so')
        .replace_needed('libkeymint_support.so', 'libkeymint_support_V3.so'),

    # Android 17 Codec2 HIDL -v34 split
    'vendor/bin/hw/android.hardware.media.c2@1.2-mediatek-64b': blob_fixup()
        .replace_needed('libcodec2_hidl@1.0.so', 'libcodec2_hidl@1.0-v34.so')
        .replace_needed('libcodec2_hidl@1.1.so', 'libcodec2_hidl@1.1-v34.so')
        .replace_needed('libcodec2_hidl@1.2.so', 'libcodec2_hidl@1.2-v34.so')
        .replace_needed('libcodec2_vndk.so', 'libcodec2_vndk-v34.so'),

    'vendor/etc/vintf/manifest/manifest_media_c2_V1_2_default.xml': blob_fixup()
        .regex_replace('.+dolby.+\n', ''),

    'vendor/lib64/libcodec2_fsr.so': blob_fixup()
        .replace_needed('android.hardware.graphics.common-V4-ndk.so', 'android.hardware.graphics.common-V7-ndk.so')
        .replace_needed('libui.so', 'libui-v34.so'),

    'vendor/lib64/libcodec2_hidl@1.0-v34.so': blob_fixup()
        .replace_needed('libstagefright_bufferqueue_helper.so', 'libstagefright_bufferqueue_helper-v36.so')
        .replace_needed('libcodec2_hidl_plugin.so', 'libcodec2_hidl_plugin-v34.so')
        .replace_needed('libcodec2_vndk.so', 'libcodec2_vndk-v34.so')
        .replace_needed('libui.so', 'libui-v34.so'),

    'vendor/lib64/libcodec2_hidl@1.1-v34.so': blob_fixup()
        .replace_needed('libstagefright_bufferqueue_helper.so', 'libstagefright_bufferqueue_helper-v36.so')
        .replace_needed('libcodec2_hidl@1.0.so', 'libcodec2_hidl@1.0-v34.so')
        .replace_needed('libcodec2_hidl_plugin.so', 'libcodec2_hidl_plugin-v34.so')
        .replace_needed('libcodec2_vndk.so', 'libcodec2_vndk-v34.so')
        .replace_needed('libui.so', 'libui-v34.so'),

    'vendor/lib64/libcodec2_hidl@1.2-v34.so': blob_fixup()
        .replace_needed('libstagefright_bufferqueue_helper.so', 'libstagefright_bufferqueue_helper-v36.so')
        .replace_needed('libcodec2_hidl@1.0.so', 'libcodec2_hidl@1.0-v34.so')
        .replace_needed('libcodec2_hidl@1.1.so', 'libcodec2_hidl@1.1-v34.so')
        .replace_needed('libcodec2_hidl_plugin.so', 'libcodec2_hidl_plugin-v34.so')
        .replace_needed('libcodec2_vndk.so', 'libcodec2_vndk-v34.so')
        .replace_needed('libui.so', 'libui-v34.so'),

    ('vendor/lib64/libcodec2_hidl_plugin-v34.so',
     'vendor/lib64/libsfplugin_ccodec_utils-v34.so'): blob_fixup()
        .replace_needed('libcodec2_vndk.so', 'libcodec2_vndk-v34.so'),

    ('vendor/lib64/libcodec2_mtk_c2store.so',
     'vendor/lib64/libcodec2_mtk_vdec.so',
     'vendor/lib64/libcodec2_mtk_venc.so',
     'vendor/lib64/libcodec2_soft_mtk_alacdec.so',
     'vendor/lib64/libcodec2_soft_mtk_imaadpcmdec.so',
     'vendor/lib64/libcodec2_soft_mtk_mp3dec.so',
     'vendor/lib64/libcodec2_soft_mtk_msadpcmdec.so',
     'vendor/lib64/libcodec2_vpp_fa_plugin.so',
     'vendor/lib64/libcodec2_vpp_frc_plugin.so',
     'vendor/lib64/libcodec2_vpp_qt_plugin.so',
     'vendor/lib64/libcodec2_vpp_rs_plugin.so'): blob_fixup()
        .replace_needed('libcodec2_soft_common.so', 'libcodec2_soft_common-v34.so')
        .replace_needed('libcodec2_vndk.so', 'libcodec2_vndk-v34.so')
        .replace_needed('libsfplugin_ccodec_utils.so', 'libsfplugin_ccodec_utils-v34.so')
        .replace_needed('libui.so', 'libui-v34.so')
        .replace_needed('libformatter.so', 'libformatter-v34.so'),

    'vendor/lib64/libcodec2_soft_common-v34.so': blob_fixup()
        .replace_needed('libcodec2_vndk.so', 'libcodec2_vndk-v34.so')
        .replace_needed('libsfplugin_ccodec_utils.so', 'libsfplugin_ccodec_utils-v34.so')
        .replace_needed('libui.so', 'libui-v34.so'),

    'vendor/lib64/libcodec2_vndk-v34.so': blob_fixup()
        .replace_needed('libui.so', 'libui-v34.so')
        .replace_needed('android.hardware.media.bufferpool2-V1-ndk.so', 'android.hardware.media.bufferpool2-V2-ndk.so'),

    'system_ext/lib64/libimsma.so': blob_fixup()
        .replace_needed('libsink.so', 'libsink-mtk.so'),

    'system_ext/lib64/libsink-mtk.so': blob_fixup()
        .add_needed('libaudioclient_shim.so'),

    'odm/bin/hw/vendor.xiaomi.sensor.citsensorservice.aidl': blob_fixup()
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so')
        .replace_needed('libui.so', 'libui-v34.so'),

    'vendor/etc/init/tee-supplicant.rc': blob_fixup()
        .regex_replace('chown system system /dev/ufs-bsg0', 'chown root system /dev/ufs-bsg0'),

    'vendor/bin/mnld': blob_fixup()
        .replace_needed('libmnl.so', 'libmnl-v34.so'),

    # Stock names this jar androidx.camera.extensions.impl.jar. It is installed
    # as ...impl.xiaomi.jar (see proprietary-files.txt) so it cannot collide with
    # the AOSP sample module of the same name; point the permission XML at it.
    'vendor/etc/permissions/camera_extensions.xml': blob_fixup()
        .regex_replace(r'/androidx\.camera\.extensions\.impl\.jar',
                       '/androidx.camera.extensions.impl.xiaomi.jar'),

    # Android 14+ requires RECEIVER_EXPORTED on dynamic broadcast receivers.
    'system_ext/priv-app/ImsService/ImsService.apk': blob_fixup()
        .apktool_patch('blob-patches/ImsService'),
}  # fmt: skip

module = ExtractUtilsModule(
    'degas',
    'xiaomi',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    add_firmware_proprietary_file=False,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
