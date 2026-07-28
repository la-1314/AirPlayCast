package com.miui.airplaycast.airplay.pairing

/**
 * HomeKit TLV8 编解码
 *
 * HomeKit 配对协议使用 Type-Length-Value 格式传输参数
 * 每个 TLV: [1 byte tag][1 byte length][0-255 bytes value]
 * 超过 255 字节的值会被分片成多个相同 tag 的 TLV
 *
 * 参考: HomeKit Accessory Protocol Specification (Non-Commercial)
 */
object Tlv8 {

    // TLV8 Tag 定义
    const val TAG_METHOD = 0x00          // Pairing method
    const val TAG_IDENTIFIER = 0x01      // 设备标识符 (pairing ID)
    const val TAG_SALT = 0x02            // SRP salt
    const val TAG_PUBLIC_KEY = 0x03      // 公钥
    const val TAG_PROOF = 0x04           // SRP proof
    const val TAG_ENCRYPTED_DATA = 0x05  // 加密数据
    const val TAG_SEQUENCE = 0x06        // 序列号 (M1-M6)
    const val TAG_ERROR = 0x07           // 错误码
    const val TAG_SIGNATURE = 0x08       // 签名
    const val TAG_PERMISSIONS = 0x0B     // 权限
    const val TAG_FRAGMENT_DATA = 0x0C   // 分片数据
    const val TAG_FRAGMENT_LAST = 0x0D   // 最后分片

    // Pairing Method
    const val METHOD_PAIR_SETUP = 0x00
    const val METHOD_PAIR_SETUP_WITH_AUTH = 0x01
    const val METHOD_PAIR_VERIFY = 0x02
    const val METHOD_ADD_PAIRING = 0x03
    const val METHOD_REMOVE_PAIRING = 0x04
    const val METHOD_LIST_PAIRING = 0x05

    // Error codes
    const val ERROR_RESERVED = 0x00
    const val ERROR_UNKNOWN = 0x01
    const val ERROR_AUTHENTICATION = 0x02
    const val ERROR_BACK_OFF = 0x03
    const val ERROR_MAX_PEERS = 0x04
    const val ERROR_MAX_TRIES = 0x05
    const val ERROR_UNAVAILABLE = 0x06
    const val ERROR_BUSY = 0x07

    /**
     * 编码 TLV8 列表为字节数组
     *
     * @param items TLV 项列表 (tag to value)
     * @return 编码后的字节数组
     */
    fun encode(items: List<Pair<Int, ByteArray>>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        for ((tag, value) in items) {
            require(tag in 0..255) { "TLV tag must be 0-255, got $tag" }
            if (value.isEmpty()) {
                output.write(tag)
                output.write(0)
                continue
            }
            var offset = 0
            while (offset < value.size) {
                val chunk = minOf(255, value.size - offset)
                output.write(tag)
                output.write(chunk)
                output.write(value, offset, chunk)
                offset += chunk
            }
        }
        return output.toByteArray()
    }

    /**
     * 解码 TLV8 字节数组
     *
     * @param data TLV8 编码数据
     * @return 解析后的 TLV 项列表 (相同 tag 可能出现多次，需调用方合并分片)
     */
    fun decode(data: ByteArray): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        val merged = mutableMapOf<Int, java.io.ByteArrayOutputStream>()
        var i = 0
        while (i + 1 < data.size) {
            val tag = data[i].toInt() and 0xFF
            val length = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + length > data.size) break
            val value = data.copyOfRange(i, i + length)
            i += length
            // 合并同 tag 的分片
            val baos = merged.getOrPut(tag) { java.io.ByteArrayOutputStream() }
            baos.write(value)
        }
        for ((tag, baos) in merged) {
            result.add(tag to baos.toByteArray())
        }
        return result
    }

    /**
     * 便捷构造: 从 map 构建 TLV8
     */
    fun encodeMap(map: Map<Int, ByteArray>): ByteArray = encode(map.toList())

    /**
     * 便捷解析: 转为 map (后出现的同 tag 值会覆盖，分片已合并)
     */
    fun decodeToMap(data: ByteArray): Map<Int, ByteArray> = decode(data).toMap()

    /**
     * 整数转单字节 ByteArray (TLV 中 method/sequence/error 等是 1 字节)
     */
    fun byteOf(value: Int): ByteArray = byteArrayOf(value.toByte())

    /**
     * 单字节 ByteArray 转整数
     */
    fun intOf(bytes: ByteArray): Int = if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else 0
}
