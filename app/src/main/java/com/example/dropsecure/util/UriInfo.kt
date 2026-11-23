package com.example.dropsecure.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object UriInfo {
    /** @return display name and size (size -1 if unknown). */
    fun describe(context: Context, uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        return name to size
    }
}
