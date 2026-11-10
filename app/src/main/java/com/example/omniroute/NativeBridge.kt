package com.example.omniroute

object NativeBridge {
    init {
        System.loadLibrary("omniroute")
    }

    @JvmStatic external fun start(port: Int, upstream: String): Int
    @JvmStatic external fun stop()
    @JvmStatic external fun restart(port: Int, upstream: String): Int
    @JvmStatic external fun statusJson(): String
    @JvmStatic external fun health(port: Int): Int
    @JvmStatic external fun probeGitPull(): Int
    @JvmStatic external fun flushCache(): Int
}