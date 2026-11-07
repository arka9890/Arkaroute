package com.omniroute.app.config

interface CryptoEngine {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}
