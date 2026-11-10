package com.example.omniroute

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    val buffer = mutableStateListOf<String>()

    fun log(tag: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        buffer.add("[$ts] [$tag] $message")
        if (buffer.size > 500) buffer.removeAt(0)
    }

    fun clear() = buffer.clear()
}