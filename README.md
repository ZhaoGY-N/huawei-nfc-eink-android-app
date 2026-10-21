# Huawei NFC E-Ink Android App

Android 墨水屏刷新工具，当前实验版本 **v2.12.0-reader-test1**（基于 v2.12.0）。统一通过 Shizuku（Shell / Root）调用兼容系统的 NXP Q-Poll，再通过 Android ISO-DEP 向 **552×768 四色华为 A0 / A1 墨水屏**传图刷新，使用板内已存驱动。

## 功能

- 设备与检测：授权连接、NFC 芯片配置识别、Q-Poll 能力测试、普通 NFC-A 对照、只读检查屏幕。
- 图像与发送：选图、四色预览、旋转、裁切、黑白二值化、阈值、色块简化和抖动；支持全屏缩放与 1:1 像素查看。
- 预览和发送按钮位于页面上方，图片编辑选项位于下方；预览高度根据可用空间调整。
- 高级参数：上电等待时间、忙状态查询间隔、屏幕校色；参数保存在本地。校色支持相册照片四色取样及 RGB / 十六进制手调，A0 / A1 共用一套配置。
- 保留屏幕规格和原厂扫描配置检查，不限制屏幕 UID；发送前确认，运行中可停止。
- D1 的 C0（FMSC APPID）接受参考实现支持的 `201EA001` 和 `00000001`；未知值仍停止，其他屏幕规格校验保留。

## 使用

1. 开启 NFC，保持手机解锁。在 Shizuku 中通过无线调试或 Root 启动服务，再在“设备与检测”页授权连接；界面显示实际服务身份。Root 手机需在 Magisk / KernelSU 中允许 Shizuku 的 Root 权限，再通过 Root 方式启动。更换启动方式后重新连接。
2. 点击“检查芯片 / NFC”，按需测试 Q-Poll。型号来自系统固件配置，能力以实际控制器响应和通信结果为准。
3. 在“图像与发送”页手动选择实物屏幕类型 A0 或 A1，再选择图片并调整预览，可先在“设备与检测”页执行“只读检查屏幕”。选择会保存，更换屏幕时需重新核对；D1 无法自动判断 A0 / A1。
4. 点击“发送当前预览到墨水屏”，核对图片和参数后确认。持续贴合并保持应用在前台，直到操作结束。

仅适用于兼容 Q-Poll 的手机系统及相同原厂配置的目标屏；不能仅凭接口存在或型号名称判定兼容。刷新结果取决于实际供能与屏幕状态。中断可能留下不完整画面，程序不会自动重发刷新指令。

## ReaderMode 系统接口兼容

针对 issue #5，worker 在发送 QTag 或启动读卡前，检查当前系统 `INfcAdapter.setReaderMode` 的实际签名：优先使用五参数版本（含包名），不存在时使用四参数版本。开启、重新寻卡和退出清理复用同一个接口；不按 Android 版本号硬编码，也不需要替换手机系统文件。

日志 `READER_API` 显示所选参数个数，`READER_API_MISSING` 表示两种签名均不存在，`READER_CALL_FAILED` 表示接口存在但调用失败。接口不存在时不尝试开启 ReaderMode，避免因此误报 ReaderMode 恢复失败；权限或服务异常保留原始原因，不切换重试其他重载。

四参数适配通过离线模拟接口验证；realme RMX3888 / Android 15 的实际调用、寻卡及恢复仍需实机测试。

## 直接 Q-Poll 实验路径（reader-test1）

根据 [issue #5 的补充实测](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/5#issuecomment-6273145604)，RMX3888 / SN100U 在绕过 ReaderMode 的临时测试中，两轮均接受 `210303017101`；这还没有验证 Android Tag 回调和墨水屏通信。本版本增加独立选项，继续验证完整系统路径。

在“设备与检测 → Q-Poll 寻卡方式”手动选择“直接 Q-Poll（实验，SN100U 待验证）”。默认仍为系统 QTag；选项不跨重启保存。检查 NFC 和普通 NFC-A 对照始终使用系统方式，直接路径不注入 SN220 HAL 兼容库。Shell / Root 后端均保持 worker 的 UID 2000 与 shell SELinux 域检查，系统拒绝调用时停止。

直接路径先注册四参数或五参数 ReaderMode 回调，等待系统普通寻卡成功响应，再依次发送 `RF_DEACTIVATE`（`21060100`）与 Q-Poll `RF_DISCOVER`（`210303017101`）。两条命令都必须在 HAL 日志中匹配实际发送及 `STATUS_OK`；API 返回 0 本身不算成功。日志不可用、响应缺失、命令被拒绝、寻卡命令交错或用户停止时，不继续后续步骤。每次响应等待上限 3 秒，系统 Binder 调用时限仍由 ROM 控制。

停止、异常和重连均先停止直接 Q-Poll，再释放 ReaderMode，并等待系统普通寻卡的发送与成功响应；恢复证据不足时报告 `RESTORE_FAILED`，需关闭再开启 NFC。清理期间继续读取日志，停止标记不阻止恢复命令。不硬编码普通寻卡配置，也不替换系统文件。

仅在直接 Q-Poll 阶段接受标签回调；仍使用 Android IsoDep 和原有屏幕协议校验。若控制器已接受 `0x71` 却没有 Tag，不能认定屏幕已兼容；可能仍需针对该 ROM 的发现/激活通知进行适配。本版本没有把 `0x71` 通知伪装成普通 NFC-A，也没有扩展 SN220 HAL 注入范围。QTag 被拒绝的日志改为说明当前路径失败，不据此判定硬件不支持 Q-Poll。

建议实机验证顺序：普通 NFC-A 对照 → 直接 Q-Poll 能力测试 → 贴屏只读 D1 → 发送图片。重点保留 `READER_API`、`DIRECT_ACK`、`CAPABILITY`、`TAG`、`READER_OFF` 与 `RESTORED` / `RESTORE_FAILED` 日志。离线回归覆盖精确命令顺序、异步响应、四/五参数选择、主机成功但无控制器响应、异常状态、取消、重连和恢复失败；实机结果仍待确认。

## 屏幕校色（v2.12.0）

1. 在“高级参数 → 屏幕校色”点击“显示校色测试图”，再到“图像与发送”确认发送。测试图左上黑、右上白、左下黄、右下红，直接使用固定四色编码，绕过校色、二值化、抖动、简化和裁切。更换为普通图片即可退出基准图模式。
2. 等待实物完整刷新，在固定均匀光照下自行拍摄，尽量锁定曝光、白平衡并关闭 HDR / 滤镜。App 不调用相机。在校色部分选择“从相册选择屏幕照片”；Android 13 及以上使用系统照片选择器，不可用或旧系统回退到系统图片文件选择器，无需相机或整个相册的读取权限。
3. 在照片中依次点选黑、白、黄、红色块中心并确认每种颜色，可拖动取样框或重新选择颜色重取。取样框覆盖照片短边约 2% 的区域，在解码为 sRGB 的图片上按线性光平均；避开边缘、反光和阴影。全部四色确认后点击“保存并启用校色”；取消不改写配置，也不替换当前编辑图片。
4. 点击四色按钮，通过 RGB 滑块或六位十六进制手调，保存后启用。数值描述屏幕四色的近似外观；四种颜色不可重复，白色须明显亮于黑色且各通道高于黑色。关闭勾选保留配置并回到原转换方式；恢复默认色板会清除手调值并关闭校色。A0 / A1 共用配置，重启后保留。

校色模式将输入映射到取样黑白范围，在统一的线性 RGB 空间内进行加权距离量化、蛇形 Floyd–Steinberg 误差扩散；图像简化也使用该色板并在线性光下平均。纯黑白端点与中性灰受到保护。黑白二值化保留原阈值逻辑；预览按最终发送编码解码，再以校色色板显示，属于近似实物预览。

无标准色卡时，结果包含相机与光源偏色，适合相对优化；没有建立绝对色度校准或复杂 LUT。相册照片在本机临时解码，只有四色数值和启用状态保存到设置。

离线验证覆盖取样平均、边界和无效色板、黑白范围归一化、中性灰与纯黑白保护、各简化档位、A0 / A1 打包后校色预览，以及现有图像/协议回归。APK 构建与签名检查通过；相册选择器、取样操作与实物显示效果仍待手机验证。

## 构建与验证

需要 Python 3.9+、JDK 17、Android SDK platform android-34、build-tools 34.0.0，以及 Android NDK（本次验证使用 27.2.12479018）。设置 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_NDK_HOME` 指向本机安装目录，然后在仓库根目录运行：

```sh
python3 tests/run_java.py
cc -std=c11 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined tests/nci_rewrite_test.c -o build/nci-rewrite-test
build/nci-rewrite-test
cc -std=c11 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined tests/hal_discovery_test.c -o build/hal-discovery-test
build/hal-discovery-test
cc -std=c11 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined tests/hook_debug_test.c -o build/hook-debug-test
build/hook-debug-test
python3 build.py
```

脚本使用 Android SDK 的命令行工具构建，无 Gradle、无构建期下载。Shizuku API / provider / aidl / shared 13.1.5 依赖已固定在 `libs/`。构建脚本当前使用 Unix 工具路径；Windows 可在 WSL 中运行。

设置 `ANDROID_NDK_HOME` 后，`build.py` 自动运行 `native/build.sh`，从源码生成 arm64 兼容库及注入器。生成物位于忽略的 `assets/native/`，不采用 fork 的预编译二进制。也可先单独运行 `sh native/build.sh`；后续构建会校验源码与二进制清单，拒绝缺失或过期的原生资产。

如安装了 `lzokay==2.1.0`，可独立检查解压与扫描打包：

```sh
python3 tests/check_fixtures.py build/<protocol-tests-directory>
```

APK 和 SHA-256 输出在 `dist/`。首次构建会在 `build/` 生成本地调试签名密钥；密钥、构建缓存和 APK 不纳入 Git。自行构建时需要保留自己的密钥，以支持后续覆盖安装。

```sh
adb install -r dist/QPollLab-2.12.0-reader-test1.apk
```

## 隐私

本仓库仅包含 App 源码、测试、构建脚本、必要依赖与许可证。测试使用合成 UID，不包含原始手机日志、NFC 抓包或个人图片。

应用在运行时读取当前设备与标签信息，并在应用私有目录保存运行日志。日志可能包含设备型号、标签 UID 和本轮操作参数；分享日志前请自行检查。应用未申请网络权限。

## 参考逆向项目

本 App 的协议学习与实现参考了以下两个逆向仓库，感谢原作者公开研究成果：

- [kur0neko/huawei-epd-pn532](https://gitee.com/kur0neko/huawei-epd-pn532)：参考原厂图像扫描布局及 D3 传图、D4 刷新流程；对应代码遵循 PolyForm Noncommercial 1.0.0。
- [solar2ain/ink-nfc-fmsc-by-pn532](https://github.com/solar2ain/ink-nfc-fmsc-by-pn532)：参考公开的 D8/APDU 定义及 LZO1X literal-stream 格式；该项目采用 GPL-3.0-or-later。

具体参考提交、版权声明和许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 协议与许可

保留原厂驱动与扫描配置，无 DB/DA 配置写入。图像为 105984 字节，分块发送 D3，单次 D4 启动刷新，通过 DE 查询状态。

参考项目、第三方版权和许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。协议与扫描布局所参考代码使用 PolyForm Noncommercial 1.0.0，完整许可证及 Required Notice 保留在 `third-party/` 和 APK assets 中；请遵守其非商业条款。Shizuku 依赖遵循 MIT License。

## v2.9.1 配置诊断

配置校验失败时，日志逐项显示不匹配字段、期望值和实际十六进制值；缺失或长度异常也显示对应字段。D1 只检查首字节时明确标注。仍执行原有兼容性检查，校验失败后不会认证、传图或刷新，不改写 DB/DA。UID 不参与兼容性校验。

## v2.9.2-test1 连接测试版（Issue #1）

针对寻到标签后 ISO-DEP 连接失败，增加最多三次连接尝试：仅 `IsoDep.connect()` 抛出 IOException、尚未发送任何 APDU 时重新启动 ReaderMode，获取同一 UID 的新 Tag；重新寻卡超时、取消或遇到不同 UID 即停止。协议通信开始后不自动重试，保留原有屏幕规格校验。

日志增加 CONNECT_ATTEMPT、ISO_DEP、CONNECTED / CONNECT_FAILED、APDU_BEGIN / APDU_FAILED 和异常堆栈；不记录 PIN 或图像内容。升级后重新连接 Shizuku，先执行“只读检查屏幕”，反馈从本轮开始到结束的完整日志及手机、系统、屏幕型号。此版本是连接恢复尝试与诊断增强，尚未在 Issue 中的目标屏实测，不代表已支持该型号。

## v2.10-test1 手动选择 A0 / A1

- “图像与发送”页新增屏幕类型选项，首次使用必须明确选择；不根据 D1、UID 或旧版设置猜测型号。切换后重新生成发送快照，运行中不能更改；发送确认和日志显示所选类型。
- A1 保留原有线性控制器行布局，A0 使用参考仓库的奇偶行交错布局。预览按所选布局反解，显示逻辑画面，而非交错后的传输排列。
- 继续使用板内已存驱动，不发送 DB / DA。不负责将 A1 驱动板重配为 A0 或反向改装；实物与板内驱动必须对应。
- 接受已知的 `00120030FFFFFF` / `01120030FFFFFF` 扫描配置，并按读出的方向调整传输数据；其他规格校验仍然保留。写入前复核发现方向变化即停止，不传图。
- 预览快照 SHA-256 与实际发送 SHA-256 分别记录；扫描方向转换发生在 D1 校验之后。只读检查仍可在未选择型号时执行，但不能据此识别 A0 / A1。
- 全帧映射与参考打包函数逐字节比对通过，回归覆盖两种型号、两种扫描方向、实际 D3 数据、预览还原及未知选择拒绝；APK 构建和签名验证通过。尚未完成两种实物屏的刷新验证；此改动不解决 D1 返回 `6401` 的问题。
- 临时类型 `A0X`（A0临时 · 左右半屏互换）：在按 A0 打包前交换逻辑画面的左右各 276 列（各 52992 字节）半区，用于实测中左右两半顺序相反的屏；不影响 A0 / A1。

## v2.11-root-test1 Shizuku 统一后端

- 移除应用直接 `su → app_process` 的执行路径；所有会话统一通过 Shizuku UserService。Shizuku Shell 保持原有系统 QTag 路径。
- Shizuku Root 通过 `su 2000` 和 `runcon u:r:shell:s0` 启动 NFC worker；调用 NFC 前检查 UID=2000 和 shell SELinux 域，切换失败就停止。Root 身份不再直接调用 ReaderMode，避免部分 ROM 的 UID 0 包名查询异常。需要 Root 管理器允许切换身份及 SELinux 域。
- SN220 HAL 兼容选项默认关闭、不保存勾选状态。仅 Q-Poll 寻卡 / D1 / 刷新会话可启用；检查芯片与普通 NFC-A 对照不注入。需要 Shizuku Root、arm64、SN220 固件配置、符合下述库名正则的 HAL 进程及受支持的函数符号；不根据芯片型号宣称普遍兼容。
- 兼容层将出站 RF_DISCOVER 的 NFC-A `00` 改为 `71`，将入站发现/激活通知中的 `71` 改回 `00`。跳过原来的 QTag 开启/关闭命令，后续保留 Android IsoDep/APDU 通信。控制器接受情况和 Tag 回调仍由真实日志判断。
- 注入器以 Root 通过 ptrace 加载兼容库，显式调用安装函数并确认两个方向的 GOT 项。符号缺失、已有其他 hook、加载或安装失败均停止，不继续发送 APDU。只读取 APK 内的固定资产，不接受调用方提供的注入路径。
- `/data/vendor/nfc/qpolllab/enable` 控制两个方向的改写；停止、异常和正常结束均清理。开关超过 360 秒失效；桥接进程被强杀时不能保证立即清理，下次 Root 会话会在确认无旧 worker 后清理。库及内容哈希命名文件保留，停用后直通，NFC HAL 进程重启后内存 hook 消失。同一版本重复安装复用加载库；test5 起可替换本应用已驻留的旧版 hook，仍拒绝第三方 hook。
- 跨服务版本共享会话锁，另有 worker 锁防止桥接进程退出后与遗留 worker 并行。恢复失败会明确提示重新开关 NFC；不把注入成功等同于寻卡、供能或刷新成功。
- 此版本的权限和寻卡后端调整保留 A0/A1 布局、单次 PIN 条件验证和刷新时序；后续已将 C0 放宽为参考实现的两个已知 APPID。尚未合入 issue 的双 PIN 或延长保场参数，不能认为该 issue 的整套屏幕刷新问题已解决。
- 验证：Java 协议/图像/连接恢复及权限分流回归、NCI 改写边界测试、arm64 原生编译、APK 构建与签名检查。尚未在真实 Root 手机验证身份切换、HAL 注入、寻卡、刷新和普通 NFC 恢复；此版本用于实验验证。

适配思路来自 [HBWuChang 的 Issue #3](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/3) 及其公开的设备分支；本分支独立实现会话门禁、双向开关、安装确认及源码构建。

## v2.11-root-test2 HAL 进程识别与诊断

- test1 要求进程名严格等于 `android.hardware.nqnfc-service.nxp`，会漏掉其他服务名称。test2 以进程实际加载的 `nfc_nci.nqx.default.hw.so` 为准，不依赖服务名称；仍要求库文件名和后续 GOT 符号精确匹配，找到多个候选就停止。
- 增加 `HAL_MATCH`、`HAL_CANDIDATE`、`HAL_SCAN` 日志，显示匹配 PID、进程名、库路径，以及 maps 读取受拒/不可用计数，区分名称差异、权限问题和模块缺失。
- 修正注入失败后的清理日志：没有收到安装确认时，不再声称兼容库已经驻留。
- 离线回归覆盖服务改名、cmdline 不可读但模块可读、相似库名拒绝、多进程歧义及诊断输出；带 ASan/UBSan 的测试和 Clang 静态分析通过。尚待手机验证，此次日志本身不足以确定具体 ROM 的失败原因。

## v2.11-root-test3 maps 解析和实际库路径诊断

- 用户 test2 日志确认 `/odm/bin/hw/android.hardware.nqnfc-service.nxp` 存在，maps 可读，但没有匹配到目标库；尚不能判断是库名、映射布局或解析差异。
- 改用直接解析 maps 字段，去掉 `scanf` 的计数转换依赖；HAL 进程识别接受目标库的任意映射段，不再要求该段文件偏移为 0。系统函数重定位仍要求准确的 offset-zero 基址，不用段起点猜测加载基址。
- 未匹配的 NFC 硬件服务额外记录 `HAL_MAP` 原始映射行，包含相关库的实际路径和偏移；不自动放宽 HAL 库名或 GOT 符号门禁。
- 回归新增非零偏移段、制表符、多空格、数值溢出拒绝及实际库路径诊断；未接触手机进行注入或 APDU 操作。

## v2.11-root-test4 HAL 库名正则匹配

- 共享 `native/hal_name.h` 中的 POSIX 扩展正则 `^nfc_nci([._-][[:alnum:]]+)*\.so$`，对完整文件名匹配；注入器的 maps 扫描和兼容库的 `dl_iterate_phdr` 使用同一实现。覆盖点号、下划线或连字符分隔的厂商、芯片族与版本后缀，不枚举两个库名。
- 可匹配 `nfc_nci.nqx.default.hw.so`、`nfc_nci_nxp_snxxx_v2.so`、其他版本后缀及 `nfc_nci.so`；拒绝相似前缀、`.so.bak`、`(deleted)` 和无关的 `libnfc_vendor_extn.so` / 固件库。
- maps 同一路径的多个段按一个库处理；一个进程存在多个不同匹配库，或多个进程符合条件时拒绝注入。hook 在任何 GOT 写入前遍历完整模块列表，同样拒绝多个候选，防止选中首个同名族模块。
- 库名匹配只负责筛选候选，仍要求两个目标函数存在、类型正确、GOT 原始地址一致，且两个方向安装确认成功。未验证函数 ABI 的变种仍不能宣称兼容。
- 用户手机的实际库 `/odm/lib64/nfc_nci_nxp_snxxx_v2.so` 已只读提取到忽略的 build 目录核对；两个目标函数和 JUMP_SLOT 重定位项均存在。此版本完成离线匹配回归、静态分析及 APK 构建；尚未在手机注入测试。

## v2.11-root-test5 注入发送路径与统计

- 用户 test4 实测已确认 HAL 正则匹配及 GOT 安装成功，但两次注入寻卡超时；原系统 QTag 路径随后立即收到 Tag。三轮均使用 Shizuku UID 0 / worker UID 2000，因此区别在于寻卡后端，不能归因于 Root 身份切换。
- 对实际 HAL 离线反汇编发现 `phNxpNciHal_write_unlocked(unsigned short, const uint8_t *, int)` 的独立调用路径。除原 `write_internal` 外，新增该 GOT 入口（若存在）；严格保留第三个参数，两层仅改写 NFC-A `00`，已是 `71` 时不重复改写。仍要求至少一条发送路径及接收路径安装确认。
- 新增导出的固定版本统计结构。`--status` 只读 `/proc/<pid>/mem`，不 ptrace、不加载库、不远程调用、不改写 HAL。服务在 worker 启动前与恢复普通寻卡前各读取一次累计值。
- `HOOK_SLOTS` 显示实际安装的入口数及旧版升级数；`HOOK_STATS` 显示两条 TX 路径的调用、发现与改写次数，以及 RX/发现响应次数；`HOOK_GATE` 显示开关检查状态、原始 syscall 返回值、属主、权限和时间差；`HOOK_PACKET` 仅记录发现命令、控制器应答与发现/激活通知的前后最多 32 字节。不会记录 APDU、PIN 或图像。
- HAL 回调仅使用内联原子操作、固定大小采样和 raw syscall；不在回调内写日志文件、调用动态加载器或阻塞等待采样锁。状态为累计值，需对比两个 phase。
- 恢复顺序调整：worker 请求恢复，Root 服务先读取统计、删除兼容开关并确认，再由 worker 释放 ReaderMode；避免普通 NFC 恢复命令仍被转换成 Q-Poll。确认缺失超过 20 秒明确报告恢复未确认。
- 更新兼容库时，只允许替换位于本应用固定目录、64 位十六进制内容哈希命名、Root 所有且无组/其他写权限的既有 hook。直接连接 HAL 原函数，不串联旧版；旧库不卸载，避免仍在执行的回调失效，失败时回滚到之前的 GOT 值。其他来源的 hook 继续拒绝。
- 离线测试覆盖双 TX 入口的计数/改写、gate-off 直通、重复改写抑制、RX 归一化、控制器响应采样、APDU 排除、非阻塞采样及本应用旧库路径约束。仍需用户手动测试实际寻卡和恢复握手。

### test5 实机验证（2026-10-19）

- Xiaomi 25102RKBEC / myron、SN220U、`nfc_nci_nxp_snxxx_v2.so`：用户确认注入方式成功。23:44 这一轮统计中 `tx_internal` 始终为 0，`tx_unlocked` 从 21 增至 26；发现命令改写从 3 增至 4。确认此 HAL 的实际发送路径经过 `write_unlocked`，原先仅拦截 `write_internal` 会遗漏。
- 实际发现命令 `210303010001 → 210303017101`，控制器应答 `41030100`；激活通知 technology `71 → 00`。收到 Tag、D1 校验通过，D3 上传后 D4 返回 `9000`，并观察到 DE 忙状态。
- `HOOK_RESTORE_ACK → READER_OFF → RESTORED → HOOK_OFF` 恢复握手完成。该轮最终 DE 返回 `68CA`（按现有协议定义为低电压/供能不足），所以日志没有确认忙→空闲完成；用户报告成功不能替代对最终状态检查的区分。未自动重发 D4。

## v2.11-root-test6 色阶测试图

“图像与发送 → 编辑图片”新增独立的“色阶测试图”按钮，保留原四色测试图。内置 552×768 源图包含 16 级灰阶、白→黄→黑和白→红→黑的 17 级色阶，每组配连续渐变，底部有四色基准。所有源图均经过现有量化、抖动和 A0/A1 打包流程，最终仍只发送黑白黄红四色。

比较色阶时可选择“原始细节”、关闭二值化，再分别开关抖动观察预览和实物；选择测试图不会自动更改已有图像参数。

## v2.11-root-test7 连续选图裁切修复

修正裁切确认时的状态顺序：弹窗的 dismiss 回调可能晚于预览刷新调用，此时 `editingCrop` 仍为 true，导致刷新被跳过。首次裁切时，“留白→填满”的选项变化可能再次触发刷新；加载下一张图片后，选项已是“填满”，因此不会有这次补偿刷新，预览和发送快照可能仍是裁切前的图片。

应用裁切前现在显式结束裁切状态，再关闭弹窗并重新生成预览和发送快照，不依赖选项变化。手动复验：选择图片 A、裁切并应用，换图片 B、再次裁切并应用；保持“填满”选项不变时，也应立即更新预览。取消裁切和“还原原图”的行为保持原有逻辑。

## v2.11-root-test8 原图重新裁切

重复裁切始终使用完整原图，并恢复上次应用的裁切位置与缩放；取消或重置当前裁切不会改动已应用的裁切。旋转后重新打开裁切时使用相应方向的完整原图，并重置裁切窗口。
