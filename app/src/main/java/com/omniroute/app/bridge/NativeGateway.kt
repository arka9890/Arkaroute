package com.omniroute.app.bridge

object NativeGateway {
    init { System.loadLibrary("omniroute_bridge") }
    external fun startServer(configJson: String): String
    external fun stopServer(): String
    external fun isRunning(): Boolean
}
