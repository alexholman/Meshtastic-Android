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

import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.feature.map.tracking.model.GpxFileEntry

/**
 * Copies user-picked GPX files into app-internal storage so they remain readable across restarts (SAF grants are not
 * persisted), and reads/deletes them later.
 */
interface GpxFileStore {
    /** Copies the document at [sourceUri] into internal storage; returns the persisted entry, or null on failure. */
    suspend fun import(sourceUri: CommonUri, displayName: String): GpxFileEntry?

    /** Reads the stored file's text, or null if it is missing/unreadable. */
    suspend fun readText(entry: GpxFileEntry): String?

    suspend fun delete(entry: GpxFileEntry)
}
