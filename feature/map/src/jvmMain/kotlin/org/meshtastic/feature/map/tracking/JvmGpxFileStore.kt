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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.feature.map.tracking.model.GpxFileEntry
import java.io.File
import java.net.URI
import java.util.UUID

@Single
class JvmGpxFileStore(private val dispatchers: CoroutineDispatchers) : GpxFileStore {

    private val gpxDir: File
        get() = File(System.getProperty("user.home"), GPX_DIR).apply { mkdirs() }

    override suspend fun import(sourceUri: CommonUri, displayName: String): GpxFileEntry? =
        withContext(dispatchers.io) {
            try {
                val source = File(URI(sourceUri.toString()))
                val target = File(gpxDir, "${UUID.randomUUID()}.gpx")
                source.copyTo(target)
                GpxFileEntry(id = target.nameWithoutExtension, name = displayName, storedPath = target.absolutePath)
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

    private companion object {
        const val GPX_DIR = ".meshtastic/gpx_tracks"
    }
}
