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

    /** Returns the saved item, or null if it was already known or failed. */
    suspend fun ingest(ctx: Context, dao: ItemDao, uri: Uri, source: String): Item? = try {
        val text = rec.process(InputImage.fromFilePath(ctx, uri)).await().text
        val (type, detail) = Extractor.classify(text)
        val item = Item(
            source = source, uri = uri.toString(), text = text,
            type = type, detail = detail, folder = type,
            addedAt = System.currentTimeMillis()
        )
        if (dao.insert(item) > 0) item else null
    } catch (e: Exception) {
        null
    }

    suspend fun scanScreenshots(ctx: Context, dao: ItemDao, limit: Int = 100): List<Item> {
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
        val added = mutableListOf<Item>()
        for (u in fresh) Scanner.ingest(ctx, dao, u, u.toString())?.let { added.add(it) }
        return added
    }
}
