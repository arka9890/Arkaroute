package com.omniroute.app.service

import com.omniroute.app.config.AppMeta

object BootDecision {
    fun shouldStart(meta: AppMeta): Boolean = meta.bootStart
}
