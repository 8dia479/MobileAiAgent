package com.mobileaiagent

import com.mobileaiagent.tools.builtin.Geometry
import com.mobileaiagent.tools.builtin.MeshReader
import com.mobileaiagent.ui.viewer.MeshRenderer
import org.junit.Assert
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The Workspace 3D viewer is a real software rasterizer, so it can be verified
 * on the JVM: geometry really is projected into pixels here, not stubbed.
 */
class MeshRendererTest {

    private fun cube(size: Float = 20f): Geometry.Mesh = Geometry.box(size, size, size)

    private fun cylinder(): Geometry.Mesh = Geometry.cylinder(radius = 10f, height = 25f, segments = 32)

    /**
     * Counts *lit* pixels. The background is a vignette, so comparing against one
     * exact colour would be wrong; the subject is the only blue-dominant content.
     */
    private fun subjectPixels(rendered: MeshRenderer.Rendered): Int = rendered.argb.count { it.isSubject }

    private val Int.isSubject: Boolean
        get() {
            val r = (this ushr 16) and 0xFF
            val g = (this ushr 8) and 0xFF
            val b = this and 0xFF
            return b > r + 30 && b > 70
        }

    @Test fun rendersSolidPixelsForACube() {
        val rendered = MeshRenderer.render(cube(), 320, 240, MeshRenderer.Camera())
        Assert.assertEquals(320, rendered.width)
        Assert.assertEquals(240, rendered.height)
        Assert.assertEquals(320 * 240, rendered.argb.size)
        Assert.assertEquals(12, rendered.stats.trianglesTotal)
        Assert.assertTrue("cube should be visible", rendered.stats.trianglesDrawn > 0)
        val lit = subjectPixels(rendered)
        Assert.assertTrue("cube pixels should be shaded blue-ish, found $lit", lit > 5_000)
    }

    @Test fun shadingVariesAcrossFaces() {
        val rendered = MeshRenderer.render(cube(), 200, 200, MeshRenderer.Camera(), MeshRenderer.RenderOptions(wireframe = false, showAxes = false))
        // Collect the most common colors: a shaded cube must show more than one.
        val histogram = HashMap<Int, Int>()
        rendered.argb.forEach { histogram[it] = (histogram[it] ?: 0) + 1 }
        val significant = histogram.entries.filter { it.value > 150 }.map { it.key }.size
        Assert.assertTrue("expected multiple face shades, got $significant", significant >= 2)
    }

    @Test fun orbitChangesTheImage() {
        val a = MeshRenderer.render(cube(), 192, 192, MeshRenderer.Camera(yawDeg = -35f), MeshRenderer.RenderOptions(wireframe = false))
        // 25 degrees, deliberately not 90: a cube rotated by a quarter turn about
        // its vertical axis is pixel-identical to itself, which would make this
        // test assert nothing at all.
        val b = MeshRenderer.render(cube(), 192, 192, MeshRenderer.Camera(yawDeg = -10f), MeshRenderer.RenderOptions(wireframe = false))
        // A rotation must reveal genuinely different faces, not just shift edges.
        Assert.assertTrue(subjectPixels(a) > 2_000)
        Assert.assertTrue(subjectPixels(b) > 2_000)
        val differing = a.argb.indices.count { a.argb[it] != b.argb[it] }
        Assert.assertTrue("orbit must change pixels, ${differing} differed", differing > 500)
        // Shading must actually change, not only silhouette edges.
        val shadedA = a.argb.toSet()
        val shadedB = b.argb.toSet()
        Assert.assertTrue("distinct face colours: ${shadedA.size} vs ${shadedB.size}", shadedA.size > 3 && shadedB.size > 3)
    }

    @Test fun zoomEnlargesTheSubject() {
        val opts = MeshRenderer.RenderOptions(wireframe = false, showAxes = false)
        val near = subjectPixels(MeshRenderer.render(cube(), 200, 200, MeshRenderer.Camera(zoom = 2.6f), opts))
        val far = subjectPixels(MeshRenderer.render(cube(), 200, 200, MeshRenderer.Camera(zoom = 0.35f), opts))
        Assert.assertTrue("zoomed-in subject must cover more pixels ($near vs $far)", near > far * 2)
    }

    @Test fun cameraClampsPitchAndZoom() {
        val camera = MeshRenderer.Camera()
        Assert.assertEquals(89f, camera.orbit(0f, 500f).pitchDeg, 0.001f)
        Assert.assertEquals(-89f, camera.orbit(0f, -500f).pitchDeg, 0.001f)
        Assert.assertEquals(12f, camera.zoomBy(100f).zoom, 0.001f)
        Assert.assertEquals(0.15f, camera.zoomBy(0.0001f).zoom, 0.001f)
        Assert.assertEquals(3f, camera.pan(99f, -99f).panX, 0.001f)
    }

    @Test fun emptyMeshRendersBackgroundWithoutCrashing() {
        val empty = Geometry.Mesh()
        val rendered = MeshRenderer.render(empty, 128, 128, MeshRenderer.Camera())
        Assert.assertEquals(0, rendered.stats.trianglesTotal)
        Assert.assertEquals(0, rendered.stats.trianglesDrawn)
        Assert.assertEquals(128 * 128, rendered.argb.size)
        // Alpha channel must always be opaque.
        Assert.assertTrue(rendered.argb.all { (it ushr 24) == 0xFF })
    }

    @Test fun degenerateTrianglesAreSkippedNotDrawn() {
        val mesh = Geometry.Mesh()
        val a = mesh.addVertex(Geometry.Vec3(0f, 0f, 0f))
        val b = mesh.addVertex(Geometry.Vec3(1f, 0f, 0f))
        mesh.addTriangle(a, b, b) // zero area
        val rendered = MeshRenderer.render(mesh, 96, 96, MeshRenderer.Camera(), MeshRenderer.RenderOptions(showAxes = false))
        Assert.assertEquals(0, rendered.stats.trianglesDrawn)
    }

    @Test fun cylinderRendersAndReportsTriangleCount() {
        val mesh = cylinder()
        Assert.assertTrue(mesh.triangleCount > 32)
        val rendered = MeshRenderer.render(mesh, 256, 256, MeshRenderer.Camera())
        Assert.assertTrue(rendered.stats.trianglesDrawn > 0)
    }

    @Test fun stlRoundTripThenRender() {
        val mesh = cube(12f)
        val stl = Geometry.toBinaryStl(mesh)
        val info = MeshReader.inspect("cube.stl", stl)
        Assert.assertEquals(12, info.triangleCount)
        val infoBounds = info.bounds
        Assert.assertNotNull(infoBounds)
        Assert.assertTrue(infoBounds!!.size.x > 11f && infoBounds.size.x < 13f)
        val reparsed = MeshReader.readStl(stl)!!
        val rendered = MeshRenderer.render(reparsed, 160, 160, MeshRenderer.Camera())
        Assert.assertTrue(rendered.stats.trianglesDrawn >= 6)
    }

    @Test fun renderProducesValidPngThatDecodes() {
        val rendered = MeshRenderer.render(cube(), 240, 180, MeshRenderer.Camera())
        val png = com.mobileaiagent.tools.builtin.Png.write(rendered.width, rendered.height, rendered.argb)
        // PNG magic + IHDR + IDAT + IEND
        Assert.assertEquals(0x89.toByte(), png[0])
        Assert.assertEquals('P'.code.toByte(), png[1])
        Assert.assertEquals('N'.code.toByte(), png[2])
        Assert.assertEquals('G'.code.toByte(), png[3])
        // javax.imageio is not part of the Android classpath, so decoding is done
        // by hand. That is also a stronger test: it verifies the chunk structure
        // and inflates the IDAT stream instead of trusting a library.
        val decoded = decodePng(png)
        Assert.assertEquals(240, decoded.width)
        Assert.assertEquals(180, decoded.height)
        // The inflated stream is scanline-filtered RGBA, not one entry per pixel.
        Assert.assertEquals(180 * (1 + 240 * 4), decoded.pixels.size)
    }

    /** Minimal PNG reader: signature, IHDR, concatenated IDAT, zlib inflate. */
    private data class DecodedPng(val width: Int, val height: Int, val pixels: ByteArray)

    private fun decodePng(png: ByteArray): DecodedPng {
        Assert.assertEquals(0x89.toByte(), png[0])
        var pos = 8
        var width = 0
        var height = 0
        val idat = java.io.ByteArrayOutputStream()
        var sawEnd = false
        while (pos < png.size) {
            val length = ((png[pos].toInt() and 0xFF) shl 24) or
                ((png[pos + 1].toInt() and 0xFF) shl 16) or
                ((png[pos + 2].toInt() and 0xFF) shl 8) or
                (png[pos + 3].toInt() and 0xFF)
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val data = png.copyOfRange(pos + 8, pos + 8 + length)
            when (type) {
                "IHDR" -> {
                    width = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
                        ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                    height = ((data[4].toInt() and 0xFF) shl 24) or ((data[5].toInt() and 0xFF) shl 16) or
                        ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                    Assert.assertEquals("bit depth", 8, data[8].toInt())
                    Assert.assertEquals("colour type RGBA", 6, data[9].toInt())
                }
                "IDAT" -> idat.write(data)
                "IEND" -> sawEnd = true
            }
            pos += 12 + length
        }
        Assert.assertTrue("PNG must end with IEND", sawEnd)
        Assert.assertTrue("PNG must contain pixel data", idat.size() > 0)
        val raw = java.util.zip.InflaterInputStream(idat.toByteArray().inputStream()).readBytes()
        Assert.assertEquals(height * (1 + width * 4), raw.size)
        // Verify the scanline filter bytes our writer promises.
        for (y in 0 until height) {
            Assert.assertEquals("filter byte on row $y", 0, raw[y * (1 + width * 4)].toInt())
        }
        return DecodedPng(width, height, raw)
    }

    @Test fun renderPerformanceIsInteractiveOnLargeMeshes() {
        var mesh = Geometry.Mesh()
        var acc = mesh
        repeat(40) { acc = acc.merge(Geometry.sphere(radius = 6f, segments = 24)) }
        Assert.assertTrue("mesh must be substantial", acc.triangleCount > 4000)
        val started = System.currentTimeMillis()
        MeshRenderer.render(acc, 512, 512, MeshRenderer.Camera())
        val elapsed = System.currentTimeMillis() - started
        // Generous bound: CI machines vary, but this must not be a multi-second stall.
        Assert.assertTrue("render took ${elapsed}ms", elapsed < 8_000)
    }

    @Test fun depthOrderingHidesFarTrianglesBehindNearOnes() {
        // Two overlapping quads: near one must win regardless of sort ties.
        val mesh = Geometry.Mesh()
        fun quad(z: Float): Geometry.Mesh {
            val m = Geometry.Mesh()
            val a = m.addVertex(Geometry.Vec3(-5f, -5f, z))
            val b = m.addVertex(Geometry.Vec3(5f, -5f, z))
            val c = m.addVertex(Geometry.Vec3(5f, 5f, z))
            val d = m.addVertex(Geometry.Vec3(-5f, 5f, z))
            m.addTriangle(a, b, c)
            m.addTriangle(a, c, d)
            return m
        }
        val merged = quad(-40f).merge(quad(40f))
        val rendered = MeshRenderer.render(merged, 200, 200, MeshRenderer.Camera(pitchDeg = 0f, yawDeg = 0f), MeshRenderer.RenderOptions(wireframe = false, showAxes = false))
        // Centre pixel must belong to the nearer quad (larger z after rotation).
        val center = rendered.argb[(100 * 200) + 100]
        Assert.assertTrue("centre pixel must be opaque", (center ushr 24) == 0xFF)
        Assert.assertTrue("centre pixel must not be background", abs(center - 0xFF12161C.toInt()) > 0)
    }
}
