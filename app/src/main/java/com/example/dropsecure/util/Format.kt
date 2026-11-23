package com.example.dropsecure.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun speed(bytesPerSec: Double): String =
        if (bytesPerSec < 1) "-" else bytes(bytesPerSec.toLong()) + "/s"

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) String.format(Locale.US, "%.1f s", ms / 1000.0)
        else String.format(Locale.US, "%d min %02d s", s / 60, s % 60)
    }

    fun eta(seconds: Long?): String {
        if (seconds == null) return "calculating..."
        if (seconds < 60) return "$seconds s left"
        return String.format(Locale.US, "%d min %02d s left", seconds / 60, seconds % 60)
    }

    fun time(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

    fun mbps(v: Double): String = String.format(Locale.US, "%.2f MB/s", v)
}
