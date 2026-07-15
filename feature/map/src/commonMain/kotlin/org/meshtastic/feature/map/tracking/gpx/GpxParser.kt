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
package org.meshtastic.feature.map.tracking.gpx

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue
import org.meshtastic.feature.map.tracking.model.GpxOverlayData
import org.meshtastic.feature.map.tracking.model.GpxPoint
import org.meshtastic.feature.map.tracking.model.GpxTrack

private val xmlParser = XML {
    repairNamespaces = false
    defaultPolicy { ignoreUnknownChildren() }
}

/**
 * Namespace-agnostic GPX parser for waypoints (`wpt`), tracks (`trk`/`trkseg`/`trkpt`), and routes (`rte`/`rtept`,
 * folded into tracks). GPX 1.0 and 1.1 differ only by their default namespace for these elements, so the default
 * `xmlns` declarations are stripped before parsing against unqualified element names.
 */
object GpxParser {

    // runCatching (not an enumerated catch list): xmlutil surfaces malformed input through several exception
    // types, and an unlisted one escaping here would crash the caller's coroutine instead of rejecting the file.
    fun parse(fileId: String, fileName: String, gpxXml: String): Result<GpxOverlayData> = runCatching {
        val cleaned = stripDefaultNamespace(stripBom(gpxXml))
        val gpx = xmlParser.decodeFromString(GpxXml.serializer(), cleaned)
        val trackList =
            gpx.tracks.map { trk ->
                GpxTrack(
                    name = trk.name?.value,
                    points = trk.segments.flatMap { seg -> seg.points.mapNotNull(::toPoint) },
                )
            } + gpx.routes.map { rte -> GpxTrack(name = rte.name?.value, points = rte.points.mapNotNull(::toPoint)) }
        GpxOverlayData(
            fileId = fileId,
            name = fileName,
            waypoints = gpx.waypoints.mapNotNull(::toPoint),
            tracks = trackList.filter { it.points.isNotEmpty() },
        )
    }

    // A single point with a missing/unparseable lat or lon (or one out of valid range) is dropped rather than
    // failing the whole file — real-world exports occasionally carry one bad fix among many good ones.
    private fun toPoint(p: GpxPointXml): GpxPoint? {
        val lat = p.lat?.toDoubleOrNull()?.takeIf { it in -MAX_LATITUDE..MAX_LATITUDE }
        val lon = p.lon?.toDoubleOrNull()?.takeIf { it in -MAX_LONGITUDE..MAX_LONGITUDE }
        return if (lat != null && lon != null) {
            GpxPoint(latitude = lat, longitude = lon, name = p.name?.value)
        } else {
            null
        }
    }

    /**
     * Removes default `xmlns="..."` / `xmlns='...'` declarations so 1.0- and 1.1-namespaced documents both parse
     * unqualified.
     */
    private fun stripDefaultNamespace(xml: String): String = xml.replace(DEFAULT_XMLNS_REGEX, "")

    /** Strips a leading UTF-8 BOM, which some Windows/Garmin exporters prepend and which otherwise breaks parsing. */
    private fun stripBom(xml: String): String = xml.removePrefix(BOM)

    private val DEFAULT_XMLNS_REGEX = Regex("""\sxmlns\s*=\s*(?:"[^"]*"|'[^']*')""")
    private const val BOM = "\uFEFF"
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0
}

@Serializable
@XmlSerialName("gpx", "", "")
internal data class GpxXml(
    @XmlElement(true) @XmlSerialName("wpt", "", "") val waypoints: List<GpxPointXml> = emptyList(),
    @XmlElement(true) @XmlSerialName("trk", "", "") val tracks: List<GpxTrackXml> = emptyList(),
    @XmlElement(true) @XmlSerialName("rte", "", "") val routes: List<GpxRouteXml> = emptyList(),
)

// lat/lon are plain (nullable) strings rather than Double: a missing attribute or a non-numeric value (e.g.
// lat="abc") must not throw during deserialization and reject the whole document — toPoint() converts and
// validates them, dropping just the offending point.
@Serializable
internal data class GpxPointXml(
    val lat: String? = null,
    val lon: String? = null,
    @XmlElement(true) @XmlSerialName("name", "", "") val name: GpxNameXml? = null,
)

@Serializable internal data class GpxNameXml(@XmlValue val value: String = "")

@Serializable
internal data class GpxTrackXml(
    @XmlElement(true) @XmlSerialName("name", "", "") val name: GpxNameXml? = null,
    @XmlElement(true) @XmlSerialName("trkseg", "", "") val segments: List<GpxTrackSegmentXml> = emptyList(),
)

@Serializable
internal data class GpxTrackSegmentXml(
    @XmlElement(true) @XmlSerialName("trkpt", "", "") val points: List<GpxPointXml> = emptyList(),
)

@Serializable
internal data class GpxRouteXml(
    @XmlElement(true) @XmlSerialName("name", "", "") val name: GpxNameXml? = null,
    @XmlElement(true) @XmlSerialName("rtept", "", "") val points: List<GpxPointXml> = emptyList(),
)
