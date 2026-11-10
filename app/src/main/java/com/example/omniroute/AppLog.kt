package com.example.omniroute

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val _buffer = mutableStateListOf<String>()
    val buffer: List<String> get() = _buffer

    private fun ts() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    fun log(tag: String, message: String, level: String = "INFO") {
        _buffer.add("[${ts()}] [$tag] [$level] $message")
        if (_buffer.size > 500) _buffer.removeAt(0)
    }

    fun error(tag: String, message: String, t: Throwable? = null) {
        val stack = t?.let { "\n" + it.stackTraceToString() } ?: ""
        _buffer.add("[${ts()}] [$tag] [ERROR] $message$stack")
        if (_buffer.size > 500) _buffer.removeAt(0)
    }

    fun clear() = _buffer.clear()

    private fun Throwable.stackTraceToString(): String {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}
