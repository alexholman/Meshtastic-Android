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

import org.meshtastic.feature.map.tracking.gpx.GpxParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpxParserTest {

    private fun parse(xml: String) = GpxParser.parse(fileId = "id", fileName = "test.gpx", gpxXml = xml)

    @Test
    fun `parses waypoints with names`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <wpt lat="47.123" lon="8.456"><name>Aid Station 1</name><ele>1200</ele></wpt>
              <wpt lat="47.223" lon="8.556"/>
            </gpx>
            """
                .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(2, data.waypoints.size)
        assertEquals("Aid Station 1", data.waypoints[0].name)
        assertEquals(47.123, data.waypoints[0].latitude)
        assertEquals(8.456, data.waypoints[0].longitude)
        assertEquals(null, data.waypoints[1].name)
        assertTrue(data.tracks.isEmpty())
    }

    @Test
    fun `parses multi-segment track into one flattened track`() {
        val xml =
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <trk><name>Course</name>
                <trkseg>
                  <trkpt lat="47.0" lon="8.0"><ele>1000</ele></trkpt>
                  <trkpt lat="47.1" lon="8.1"/>
                </trkseg>
                <trkseg>
                  <trkpt lat="47.2" lon="8.2"/>
                </trkseg>
              </trk>
            </gpx>
            """
                .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(1, data.tracks.size)
        assertEquals("Course", data.tracks[0].name)
        assertEquals(3, data.tracks[0].points.size)
        assertEquals(47.2, data.tracks[0].points[2].latitude)
    }

    @Test
    fun `parses routes as tracks`() {
        val xml =
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <rte><name>Route A</name>
                <rtept lat="10.0" lon="20.0"/>
                <rtept lat="10.1" lon="20.1"/>
              </rte>
            </gpx>
            """
                .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(1, data.tracks.size)
        assertEquals("Route A", data.tracks[0].name)
        assertEquals(2, data.tracks[0].points.size)
    }

    @Test
    fun `accepts GPX 1_0 namespace`() {
        val xml =
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/0" version="1.0" creator="test">
              <wpt lat="1.0" lon="2.0"><name>Old</name></wpt>
            </gpx>
            """
                .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(1, data.waypoints.size)
        assertEquals("Old", data.waypoints[0].name)
    }

    @Test
    fun `ignores unknown children`() {
        val xml =
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <metadata><time>2026-07-02T00:00:00Z</time></metadata>
              <wpt lat="1.0" lon="2.0"><sym>Flag</sym><extensions><foo>bar</foo></extensions></wpt>
            </gpx>
            """
                .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(1, data.waypoints.size)
    }

    @Test
    fun `malformed xml returns failure`() {
        assertTrue(parse("<gpx><wpt lat=\"broken\"").isFailure)
    }

    @Test
    fun `non gpx xml returns failure`() {
        assertTrue(parse("<kml><Document/></kml>").isFailure)
    }

    @Test
    fun `accepts single quoted xmlns`() {
        val xml =
            """
            <gpx xmlns='http://www.topografix.com/GPX/1/1' version="1.1" creator="test">
              <wpt lat="47.1" lon="8.1"><name>Single Quoted</name></wpt>
            </gpx>
            """
                .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(1, data.waypoints.size)
        assertEquals("Single Quoted", data.waypoints[0].name)
    }

    @Test
    fun `strips leading byte order mark before parsing`() {
        val xml =
            "\uFEFF" +
                """
                <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
                  <wpt lat="47.1" lon="8.1"><name>After BOM</name></wpt>
                </gpx>
                """
                    .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(1, data.waypoints.size)
        assertEquals("After BOM", data.waypoints[0].name)
    }

    @Test
    fun `skips a trackpoint with malformed lat while keeping the rest`() {
        val xml =
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <trk><name>Course</name>
                <trkseg>
                  <trkpt lat="47.0" lon="8.0"/>
                  <trkpt lat="abc" lon="8.1"/>
                  <trkpt lat="47.2" lon="8.2"/>
                </trkseg>
              </trk>
            </gpx>
            """
                .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(1, data.tracks.size)
        assertEquals(2, data.tracks[0].points.size)
        assertEquals(47.0, data.tracks[0].points[0].latitude)
        assertEquals(47.2, data.tracks[0].points[1].latitude)
    }

    @Test
    fun `skips a trackpoint with missing lon while keeping the rest`() {
        val xml =
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <trk><name>Course</name>
                <trkseg>
                  <trkpt lat="47.0" lon="8.0"/>
                  <trkpt lat="47.1"/>
                  <trkpt lat="47.2" lon="8.2"/>
                </trkseg>
              </trk>
            </gpx>
            """
                .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(1, data.tracks.size)
        assertEquals(2, data.tracks[0].points.size)
        assertEquals(47.0, data.tracks[0].points[0].latitude)
        assertEquals(47.2, data.tracks[0].points[1].latitude)
    }

    @Test
    fun `waypoint name in cdata parses`() {
        val xml =
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <wpt lat="1.0" lon="2.0"><name><![CDATA[Aid & Station]]></name></wpt>
            </gpx>
            """
                .trimIndent()
        val data = parse(xml).getOrThrow()
        assertEquals(1, data.waypoints.size)
        assertEquals("Aid & Station", data.waypoints[0].name)
    }
}
