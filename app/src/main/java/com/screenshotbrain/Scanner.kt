package com.screenshotbrain

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object Scanner {
    private val rec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun ingest(ctx: Context, dao: ItemDao, uri: Uri, source: String): Boolean = try {
        val text = rec.process(InputImage.fromFilePath(ctx, uri)).await().text
        if (text.isBlank()) false else {
            val (type, detail) = Extractor.classify(text)
            dao.insert(
                Item(
                    source = source, uri = uri.toString(), text = text,
                    type = type, detail = detail, addedAt = System.currentTimeMillis()
                )
            ) > 0
        }
    } catch (e: Exception) {
        false
    }

    suspend fun scanScreenshots(ctx: Context, dao: ItemDao, limit: Int = 100): Int {
        val known = dao.sources().toHashSet()
        val fresh = mutableListOf<Uri>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sel = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        ctx.contentResolver.query(
            base, arrayOf(MediaStore.Images.Media._ID), sel,
            arrayOf("%creenshot%", "%creenshot%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && fresh.size < limit) {
                val u = ContentUris.withAppendedId(base, c.getLong(idx))
                if (u.toString() !in known) fresh.add(u)
            }
        }
        var added = 0
        for (u in fresh) if (ingest(ctx, dao, u, u.toString())) added++
        return added
    }
}
