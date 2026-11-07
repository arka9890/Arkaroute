package com.omniroute.app

import android.app.Application
import com.omniroute.app.config.ConfigStore
import com.omniroute.app.config.KeystoreCryptoEngine
import com.omniroute.app.data.AndroidServiceController
import com.omniroute.app.data.GatewayBridge
import com.omniroute.app.data.GatewayRepository
import com.omniroute.app.data.HttpHealthSource
import com.omniroute.app.data.JniGatewayBridge

/**
 * Manual DI graph (no Hilt). All singletons are created in onCreate and
 * exposed via [graph] for the UI layer.
 */
class OmniRouteApplication : Application() {
    class Graph(
        val store: ConfigStore,
        val healthSource: HttpHealthSource,
        val bridge: GatewayBridge,
        val repository: GatewayRepository,
    )

    companion object {
        lateinit var graph: Graph
    }

    override fun onCreate() {
        super.onCreate()
        val store = ConfigStore(filesDir, KeystoreCryptoEngine())
        val healthSource = HttpHealthSource()
        val repository = GatewayRepository(
            JniGatewayBridge,
            healthSource,
            store,
            AndroidServiceController(this),
        )
        graph = Graph(store, healthSource, JniGatewayBridge, repository)
    }
}
