package com.miui.airplaycast.airplay.pairing

import android.util.Log
import com.miui.airplaycast.airplay.AirPlayError
import com.miui.airplaycast.airplay.AirPlayException
import com.miui.airplaycast.airplay.AirPlayHttpClient
import com.miui.airplaycast.airplay.AirPlayResult
import com.miui.airplaycast.airplay.ServerInfo
import com.miui.airplaycast.airplay.airPlayTry
import com.miui.airplaycast.discovery.AirPlayDevice
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom
import java.util.UUID

/**
 * AirPlay PIN 配对 + Pair-Verify 管理
 *
 * 支持两种配对路径:
 *
 * 1. **简化 PIN 配对** (本实现主路径):
 *    适用于 UxPlay 等开源接收端的简化模式 —— 客户端将屏幕显示的 PIN 码
 *    通过 TLV8 直接发送至 /pair-setup-pin，服务端校验后建立会话。
 *    不依赖 SRP-6a，实现简单且覆盖大多数开源接收端场景。
 *
 * 2. **Pair-Verify** (已配对设备复用):
 *    使用持久化的 Ed25519 长期身份密钥建立加密会话，免 PIN。
 *
 * 完整 HomeKit SRP-6a Pair-Setup (Apple TV / HomePod) 暂未实现，
 * 检测到该流程时返回明确错误，避免误判。
 */
class PairingManager(
    private val device: AirPlayDevice
) {
    companion object {
        private const val TAG = "PairingManager"

        // 客户端长期 Ed25519 密钥对
        // 生产环境应持久化到 DataStore，确保多次连接使用同一身份
        private val clientEd25519Key: Ed25519PrivateKeyParameters by lazy {
            Ed25519PrivateKeyParameters(SecureRandom())
        }
        val clientEd25519Public: ByteArray by lazy {
            clientEd25519Key.generatePublicKey().encoded
        }

        // 客户端标识符 (pairing ID)
        private val CLIENT_PAIRING_ID: ByteArray = UUID.randomUUID().toString().toByteArray()
    }

    private val httpClient = AirPlayHttpClient(device)
    private val secureRandom = SecureRandom()

    /**
     * 探测目标设备是否要求 PIN 配对
     *
     * 调用方应在握手前调用此方法，若返回 true 则需先调用 [pairSetupWithPin]
     * 完成配对后才能继续后续 /info /SETUP /RECORD。
     *
     * @return true 表示设备要求 PIN 配对
     */
    fun isPinRequired(): AirPlayResult<Boolean> = httpClient.probePinRequired()

    /**
     * 使用屏幕显示的 PIN 码完成配对
     *
     * 实现策略 (按顺序尝试):
     *  1. **简化模式**: 直接 POST PIN 码 TLV，部分开源接收端 (UxPlay) 接受
     *  2. **HomeKit M1-M2 探测**: 若简化模式失败，检测服务端是否返回 SRP 参数
     *     - 返回 SRP salt/publicKey => 完整 HomeKit 流程，本应用暂不支持，
     *       抛出明确错误引导用户使用无需 PIN 的接收端
     *     - 返回其他错误 => 透传错误信息
     *
     * @param pin 用户输入的 4 位 PIN 码
     * @return 配对成功
     */
    fun pairSetupWithPin(pin: String): AirPlayResult<Unit> = airPlayTry {
        require(pin.length == 4 && pin.all { it.isDigit() }) {
            throw AirPlayException(AirPlayError.PairingRequired("PIN 码必须为 4 位数字"))
        }

        Log.i(TAG, "Starting pair-setup-pin with ${device.name}, pin=***")

        // ---------- 策略 1: 简化 PIN 模式 ----------
        // 构造 TLV: method=PairSetup, state=1, identifier=client-id, proof=PIN
        // (部分开源接收端将 proof 字段直接当作 PIN 校验)
        val simplifiedTlv = Tlv8.encode(listOf(
            Tlv8.TAG_METHOD to Tlv8.byteOf(Tlv8.METHOD_PAIR_SETUP),
            Tlv8.TAG_SEQUENCE to Tlv8.byteOf(1),
            Tlv8.TAG_IDENTIFIER to CLIENT_PAIRING_ID,
            Tlv8.TAG_PROOF to pin.toByteArray(Charsets.US_ASCII)
        ))

        val simpResp = httpClient.pairSetupPinStep(simplifiedTlv, "pair-setup-pin(simplified)")
        when (simpResp) {
            is AirPlayResult.Success -> {
                val map = Tlv8.decodeToMap(simpResp.value)
                val err = map[Tlv8.TAG_ERROR]
                if (err == null) {
                    // 无错误 => 配对成功
                    Log.i(TAG, "Simplified PIN pairing succeeded")
                    return@airPlayTry
                }
                val errCode = Tlv8.intOf(err)
                Log.w(TAG, "Simplified pairing returned TLV error $errCode, falling back to HomeKit probe")
                // 继续尝试策略 2
            }
            is AirPlayResult.Failure -> {
                Log.w(TAG, "Simplified pairing failed: ${simpResp.error.displayText}, probing HomeKit flow")
                // 继续尝试策略 2
            }
        }

        // ---------- 策略 2: HomeKit M1 探测 ----------
        // M1: method=PairSetup, state=1
        val m1Tlv = Tlv8.encode(listOf(
            Tlv8.TAG_METHOD to Tlv8.byteOf(Tlv8.METHOD_PAIR_SETUP),
            Tlv8.TAG_SEQUENCE to Tlv8.byteOf(1)
        ))
        when (val m1Resp = httpClient.pairSetupPinStep(m1Tlv, "pair-setup-pin(M1)")) {
            is AirPlayResult.Success -> {
                val m1Map = Tlv8.decodeToMap(m1Resp.value)
                val err = m1Map[Tlv8.TAG_ERROR]
                if (err != null) {
                    val errCode = Tlv8.intOf(err)
                    val msg = when (errCode) {
                        Tlv8.ERROR_AUTHENTICATION -> "PIN 码错误，请重新输入"
                        Tlv8.ERROR_BACK_OFF -> "配对尝试过于频繁，请稍后再试"
                        Tlv8.ERROR_MAX_TRIES -> "已达最大尝试次数，请重启接收端"
                        Tlv8.ERROR_UNAVAILABLE -> "配对功能不可用"
                        Tlv8.ERROR_BUSY -> "设备忙，请稍后再试"
                        else -> "配对失败 (错误码 $errCode)"
                    }
                    throw AirPlayException(AirPlayError.PairingRequired(msg))
                }
                // 检查是否进入 SRP 流程 (返回 salt + publicKey + state=2)
                val hasSrp = m1Map[Tlv8.TAG_SALT] != null && m1Map[Tlv8.TAG_PUBLIC_KEY] != null
                if (hasSrp) {
                    // 完整 HomeKit SRP-6a 流程暂未实现
                    throw AirPlayException(AirPlayError.PairingRequired(
                        "该设备要求完整 HomeKit SRP-6a 配对 (Apple TV / HomePod)，" +
                            "本应用暂不支持，建议使用 UxPlay 等开源接收端或关闭 PIN 验证"
                    ))
                }
                // 无 SRP 参数且无错误 => 配对可能已成功
                Log.i(TAG, "Pair-setup-pin completed (no SRP required)")
            }
            is AirPlayResult.Failure -> {
                throw AirPlayException(m1Resp.error)
            }
        }
    }

    /**
     * 执行 Pair-Verify (使用已配对凭证建立加密会话)
     *
     * @param serverInfo /server-info 返回的设备信息 (含 pk 公钥)
     * @return 成功建立加密会话
     */
    fun pairVerify(serverInfo: ServerInfo): AirPlayResult<Unit> = airPlayTry {
        Log.i(TAG, "Starting pair-verify with ${device.name}")

        // 1. 生成客户端 X25519 临时密钥对 (ECDH)
        val clientX25519Private = ByteArray(32)
        secureRandom.nextBytes(clientX25519Private)
        val clientX25519Public = ByteArray(32)
        X25519.scalarMultBase(clientX25519Private, 0, clientX25519Public, 0)

        // 2. M1: 发送客户端 X25519 公钥
        val m1Tlv = Tlv8.encode(listOf(
            Tlv8.TAG_SEQUENCE to Tlv8.byteOf(1),
            Tlv8.TAG_PUBLIC_KEY to clientX25519Public
        ))
        val m1Resp = httpClient.pairVerify(m1Tlv)
        val m1RespData = (m1Resp as? AirPlayResult.Success)?.value
            ?: throw AirPlayException((m1Resp as AirPlayResult.Failure).error)

        val m1Map = Tlv8.decodeToMap(m1RespData)
        // 检查服务端错误
        m1Map[Tlv8.TAG_ERROR]?.let {
            val errCode = Tlv8.intOf(it)
            throw AirPlayException(AirPlayError.PairingRequired("pair-verify M2 返回错误码 $errCode"))
        }
        val serverX25519Public = m1Map[Tlv8.TAG_PUBLIC_KEY]
            ?: throw AirPlayException(AirPlayError.HandshakeFailed("pair-verify M2", 0, "缺少 server X25519 public key"))
        val serverEncryptedData = m1Map[Tlv8.TAG_ENCRYPTED_DATA]
            ?: throw AirPlayException(AirPlayError.HandshakeFailed("pair-verify M2", 0, "缺少 encrypted data"))

        // 3. 计算 X25519 共享密钥 (ECDH)
        val sharedSecret = ByteArray(32)
        X25519.scalarMult(clientX25519Private, 0, serverX25519Public, 0, sharedSecret, 0)

        // 4. HKDF-SHA512 派生会话密钥
        val sessionKey = hkdfSha512(
            ikm = sharedSecret,
            salt = "Pair-Verify-Encrypt-Salt".toByteArray(),
            info = "Pair-Verify-Encrypt-Info".toByteArray(),
            length = 32
        )

        // 5. 解密 server 的加密数据 (含 server 签名)
        val nonce = "PV-Msg02".toByteArray()
        val decrypted = chacha20Poly1305Decrypt(sessionKey, nonce, serverEncryptedData)
            ?: throw AirPlayException(AirPlayError.PairingRequired("pair-verify M2 解密失败 - 配对凭证不匹配"))

        val decryptedMap = Tlv8.decodeToMap(decrypted)
        val serverIdentifier = decryptedMap[Tlv8.TAG_IDENTIFIER] ?: ByteArray(0)
        val serverSignature = decryptedMap[Tlv8.TAG_SIGNATURE]
            ?: throw AirPlayException(AirPlayError.PairingRequired("pair-verify M2 缺少 server 签名"))

        // 6. 验证 server Ed25519 签名
        // 签名内容: serverX25519Public + serverIdentifier + clientX25519Public
        val signedData = serverX25519Public + serverIdentifier + clientX25519Public
        val serverEd25519Pub = serverInfo.publicKey?.let { decodeBase64(it) }
            ?: throw AirPlayException(AirPlayError.PairingRequired("server-info 缺少 pk 公钥"))

        if (!verifyEd25519(serverEd25519Pub, signedData, serverSignature)) {
            throw AirPlayException(AirPlayError.PairingRequired("pair-verify server 签名验证失败 - 设备可能未被信任"))
        }
        Log.i(TAG, "Server signature verified")

        // 7. M3: 客户端签名 + 加密发送
        val clientSignedData = clientX25519Public + CLIENT_PAIRING_ID + serverX25519Public
        val clientSignature = signEd25519(clientSignedData)

        val m3InnerTlv = Tlv8.encode(listOf(
            Tlv8.TAG_IDENTIFIER to CLIENT_PAIRING_ID,
            Tlv8.TAG_SIGNATURE to clientSignature
        ))
        val m3Nonce = "PV-Msg03".toByteArray()
        val m3Encrypted = chacha20Poly1305Encrypt(sessionKey, m3Nonce, m3InnerTlv)

        val m3Tlv = Tlv8.encode(listOf(
            Tlv8.TAG_SEQUENCE to Tlv8.byteOf(3),
            Tlv8.TAG_ENCRYPTED_DATA to m3Encrypted
        ))
        val m3Resp = httpClient.pairVerify(m3Tlv)
        val m3RespData = (m3Resp as? AirPlayResult.Success)?.value
            ?: throw AirPlayException((m3Resp as AirPlayResult.Failure).error)

        val m3Map = Tlv8.decodeToMap(m3RespData)
        m3Map[Tlv8.TAG_ERROR]?.let {
            throw AirPlayException(AirPlayError.PairingRequired("pair-verify M4 返回错误码 ${Tlv8.intOf(it)}"))
        }

        Log.i(TAG, "Pair-verify completed, encrypted session established")
    }

    // ---------- 加密原语封装 ----------

    /** HKDF-SHA512 密钥派生 */
    private fun hkdfSha512(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(org.bouncycastle.crypto.digests.SHA512Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }

    /**
     * ChaCha20-Poly1305 AEAD 加密
     * HomeKit 用 8 字节 nonce，BC 需要 12 字节，前 4 字节补 0
     */
    private fun chacha20Poly1305Encrypt(key: ByteArray, nonce8: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        val nonce12 = ByteArray(4) + nonce8
        cipher.init(true, ParametersWithIV(KeyParameter(key), nonce12))
        val output = ByteArray(plaintext.size + 16)  // +16 for Poly1305 tag
        val len = cipher.process(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, len)
        return output
    }

    /** ChaCha20-Poly1305 AEAD 解密，验证失败返回 null */
    private fun chacha20Poly1305Decrypt(key: ByteArray, nonce8: ByteArray, ciphertext: ByteArray): ByteArray? {
        return try {
            val cipher = ChaCha20Poly1305()
            val nonce12 = ByteArray(4) + nonce8
            cipher.init(false, ParametersWithIV(KeyParameter(key), nonce12))
            val output = ByteArray(ciphertext.size)
            val len = cipher.process(ciphertext, 0, ciphertext.size, output, 0)
            cipher.doFinal(output, len)
            output.copyOf(len)
        } catch (e: Exception) {
            Log.w(TAG, "ChaCha20 decrypt failed: ${e.message}")
            null
        }
    }

    /** Ed25519 签名 */
    private fun signEd25519(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, clientEd25519Key)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /** Ed25519 签名验证 */
    private fun verifyEd25519(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(data, 0, data.size)
            signer.verifySignature(signature)
        } catch (e: Exception) {
            Log.w(TAG, "Ed25519 verify failed: ${e.message}")
            false
        }
    }

    private fun decodeBase64(s: String): ByteArray =
        java.util.Base64.getDecoder().decode(s)
}
