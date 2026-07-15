/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.map.tracking

import android.app.Application
import android.provider.OpenableColumns
import co.touchlab.kermit.Logger
import com.eygraber.uri.toAndroidUri
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.feature.map.tracking.model.GpxFileEntry
import java.io.File
import java.util.UUID

@Single
class AndroidGpxFileStore(private val context: Application, private val dispatchers: CoroutineDispatchers) :
    GpxFileStore {

    private val gpxDir: File
        get() = File(context.filesDir, GPX_DIR).apply { mkdirs() }

    override suspend fun import(sourceUri: CommonUri, displayName: String): GpxFileEntry? =
        withContext(dispatchers.io) {
            try {
                val androidUri = sourceUri.toAndroidUri()
                val target = File(gpxDir, "${UUID.randomUUID()}.gpx")
                context.contentResolver.openInputStream(androidUri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                val name = queryDisplayName(androidUri) ?: displayName
                GpxFileEntry(id = target.nameWithoutExtension, name = name, storedPath = target.absolutePath)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Logger.e(e) { "Failed to import GPX from $sourceUri" }
                null
            }
        }

    override suspend fun readText(entry: GpxFileEntry): String? = withContext(dispatchers.io) {
        try {
            File(entry.storedPath).takeIf { it.exists() }?.readText()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.e(e) { "Failed to read GPX ${entry.name}" }
            null
        }
    }

    override suspend fun delete(entry: GpxFileEntry) {
        withContext(dispatchers.io) {
            try {
                File(entry.storedPath).delete()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Logger.e(e) { "Failed to delete GPX ${entry.name}" }
            }
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private companion object {
        const val GPX_DIR = "gpx_tracks"
    }
}
