package com.miui.airplaycast.airplay

/**
 * AirPlay 屏幕镜像 FairPlay 加密层
 *
 * 重要法律与技术声明:
 *
 * 1. AirPlay 镜像使用 Apple 专有的 FairPlay 加密保护视频流，密钥交换使用
 *    Ed25519 + HKDF + AES-128-CTR。
 *
 * 2. 完整逆向 Apple FairPlay 在很多司法管辖区可能违反 DMCA / 反规避法律，
 *    因此本工程不实现 FairPlay 解密/加密核心代码。
 *
 * 3. 本工程仅实现协议信令流程（RTSP SETUP/RECORD/TEARDOWN 与 plist 参数
 *    协商），便于学习者理解 AirPlay 镜像会话结构。
 *
 * 4. 若需在真实 Apple TV 上完成镜像，可考虑:
 *    - 通过 AirPlay 1 (AirPlay Video) 协议将本地视频文件以 HTTP 形式投放
 *    - 或使用第三方开源接收端 (如 UglyAuth, AirPlayServer-ESP32) 旁路 FairPlay
 *
 * 5. 想深入研究的开发者请参考开源项目:
 *    - https://github.com/FDH2/UxPlay  (基于 GStreamer 的接收端，包含 FairPlay 旁路)
 *    - https://github.com/antimof/UAirPlay  (旧的 AirPlay 客户端参考实现)
 *    - espressif esp-apple-airplay (Apple 授权的 ESP32 实现)
 */
object MirroringEncryption {

    /**
     * 生成镜像会话 AES 密钥占位
     *
     * 真实实现需要:
     *  - 调用 /pair-setup 完成设备配对
     *  - 通过 Ed25519 公钥交换派生会话密钥
     *  - 使用 HKDF-SHA512 派生 AES-CTR 所需 key + iv
     *
     * 在本工程仅作演示，不实现加密流程
     */
    fun generateSessionKey(): ByteArray = ByteArray(16)

    /**
     * 判断设备是否需要 FairPlay 配对
     * AirPlay 2 设备 (Apple TV 4K+) 默认 requireAuthentication = true
     */
    fun requiresPairing(features: Long): Boolean {
        return features and 0x80L != 0L || features and 0x40000L != 0L
    }
}
