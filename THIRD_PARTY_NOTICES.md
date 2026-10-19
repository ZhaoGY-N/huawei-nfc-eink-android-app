# References and notices

The factory image scan layout and D3/D4 protocol implementation were adapted with reference to:

- kur0neko, https://gitee.com/kur0neko/huawei-epd-pn532, commit `5d3acf730a988815b637262fd4862c1794ab079e`.
- Required Notice: Copyright (c) 2026 kur0neko
- PolyForm Noncommercial License 1.0.0; exact license and notice are retained in `third-party/kur0neko/` and packaged in APK assets. The app's license dialog displays the full license.

Protocol research also used solar2ain/ink-nfc-fmsc-by-pn532, commit `0f88cb315eab60e538c102e5883c5f957d9c1be0` (GPL-3.0-or-later), including the published D8/APDU definitions and LZO1X literal-stream format. No Python modules or native binaries from that project are embedded. The local reference checkout retains its license. The Android literal encoder implements the literal length prefix and LZO end marker directly; its output is independently tested with lzokay.

The v2.10-test1 panel selector and Java layout implementation also use the published Huawei A0 even/odd controller-row mapping and A1 linear layout as protocol references from that checkout (`images.py`, `catalog.py`, `_assets/drivers.json`). Full-frame scan-01 output was compared byte-for-byte against its `pack_indices` function. Its DB/DA driver recipes are not embedded or written by this app; the board's stored driver is retained.

The Android port adds Root/Binder Q-Poll discovery, ISO-DEP transport, target/profile gates, immutable image snapshots, Android image selection/preview, cancellation, and stricter refresh-state reporting. It does not ship PN532 serial transport, replacement display drivers, or vendor test images. The built-in test pattern is drawn locally.

lzokay is used only by the host-side test script and is not part of the APK.


Shizuku API 13.1.5 (api, provider, aidl, shared), copyright Rikka and contributors, MIT License. Source: https://github.com/RikkaApps/Shizuku-API . Full license: assets/licenses/Shizuku-LICENSE.txt.

The experimental SN220 Root discovery adapter follows the publicly documented approach in HBWuChang's issue https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/3 and device fork https://github.com/HBWuChang/huawei-nfc-eink-android-app . The implementation in `native/` is written for this branch and built locally; the fork's precompiled injectors/libraries are not redistributed. No NFC firmware, system HAL library, or display driver is included.
