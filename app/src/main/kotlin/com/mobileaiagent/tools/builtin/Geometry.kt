package com.mobileaiagent.tools.builtin

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mesh generation + serialization, implemented from scratch so it works on any
 * Android device with zero native dependencies.
 *
 * Supported output: binary STL, ASCII STL, Wavefront OBJ(+MTL) and glTF binary
 * (.glb). These are real, standards-compliant files that open in Blender,
 * Windows 3D Viewer, PrusaSlicer, etc. — verified by MeshWriterTest round-trips.
 */
object Geometry {

    data class Vec3(val x: Float, val y: Float, val z: Float) {
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
        fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
        fun length() = sqrt(x * x + y * y + z * z)
        fun normalized(): Vec3 {
            val l = length()
            return if (l < 1e-9f) Vec3(0f, 0f, 0f) else Vec3(x / l, y / l, z / l)
        }
    }

    data class Bounding(val min: Vec3, val max: Vec3) {
        val size: Vec3 get() = max - min
        val center: Vec3 get() = Vec3((min.x + max.x) / 2, (min.y + max.y) / 2, (min.z + max.z) / 2)
    }

    class Mesh(
        val positions: MutableList<Vec3> = mutableListOf(),
        val triangles: MutableList<IntArray> = mutableListOf(),
        val normals: MutableList<Vec3> = mutableListOf(),
        val name: String = "mesh",
    ) {
        val vertexCount: Int get() = positions.size
        val triangleCount: Int get() = triangles.size

        fun addVertex(v: Vec3): Int { positions.add(v); return positions.size - 1 }

        fun addTriangle(a: Int, b: Int, c: Int) { triangles.add(intArrayOf(a, b, c)) }

        fun computeNormals(): List<Vec3> = triangles.map { t ->
            val n = (positions[t[1]] - positions[t[0]]).cross(positions[t[2]] - positions[t[0]]).normalized()
            n
        }

        fun bounds(): Bounding {
            if (positions.isEmpty()) return Bounding(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (p in positions) {
                if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y; if (p.z < minZ) minZ = p.z
                if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y; if (p.z > maxZ) maxZ = p.z
            }
            return Bounding(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
        }

        /** Signed volume via the divergence theorem (positive == outward-facing, watertight). */
        fun volume(): Float {
            var vol = 0f
            for (t in triangles) {
                val a = positions[t[0]]; val b = positions[t[1]]; val c = positions[t[2]]
                vol += a.dot(b.cross(c)) / 6f
            }
            return vol
        }

        fun surfaceArea(): Float {
            var area = 0f
            for (t in triangles) {
                val a = positions[t[0]]; val b = positions[t[1]]; val c = positions[t[2]]
                area += (b - a).cross(c - a).length() / 2f
            }
            return area
        }

        fun transform(translate: Vec3 = Vec3(0f, 0f, 0f), scale: Vec3 = Vec3(1f, 1f, 1f), rotateZDeg: Float = 0f): Mesh {
            val rad = Math.toRadians(rotateZDeg.toDouble())
            val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
            val out = Mesh(name = name)
            for (p in positions) {
                val sx = p.x * scale.x; val sy = p.y * scale.y; val sz = p.z * scale.z
                val rx = sx * cosR - sy * sinR
                val ry = sx * sinR + sy * cosR
                out.addVertex(Vec3(rx + translate.x, ry + translate.y, sz + translate.z))
            }
            triangles.forEach { out.addTriangle(it[0], it[1], it[2]) }
            return out
        }

        fun merge(other: Mesh): Mesh {
            val out = Mesh(name = name)
            positions.forEach { out.addVertex(it) }
            triangles.forEach { out.addTriangle(it[0], it[1], it[2]) }
            val offset = positions.size
            other.positions.forEach { out.addVertex(it) }
            other.triangles.forEach { out.addTriangle(it[0] + offset, it[1] + offset, it[2] + offset) }
            return out
        }
    }

    // ------------------------------------------------------------- primitives

    fun box(width: Float, height: Float, depth: Float, center: Vec3 = Vec3(0f, 0f, 0f)): Mesh {
        val m = Mesh(name = "box")
        val hw = width / 2; val hh = height / 2; val hd = depth / 2
        val c = center
        val corners = listOf(
            Vec3(c.x - hw, c.y - hh, c.z - hd), Vec3(c.x + hw, c.y - hh, c.z - hd),
            Vec3(c.x + hw, c.y + hh, c.z - hd), Vec3(c.x - hw, c.y + hh, c.z - hd),
            Vec3(c.x - hw, c.y - hh, c.z + hd), Vec3(c.x + hw, c.y - hh, c.z + hd),
            Vec3(c.x + hw, c.y + hh, c.z + hd), Vec3(c.x - hw, c.y + hh, c.z + hd),
        )
        corners.forEach { m.addVertex(it) }
        // Outward-facing windings (CCW seen from outside).
        val faces = listOf(
            intArrayOf(0, 3, 2), intArrayOf(0, 2, 1), // -Z (bottom)
            intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), // +Z (top)
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), // -Y
            intArrayOf(3, 7, 6), intArrayOf(3, 6, 2), // +Y
            intArrayOf(0, 4, 7), intArrayOf(0, 7, 3), // -X
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), // +X
        )
        faces.forEach { m.addTriangle(it[0], it[1], it[2]) }
        return m
    }

    fun cylinder(radius: Float, height: Float, segments: Int = 48, centerZ: Float = 0f, taperTop: Float = 1f): Mesh {
        val m = Mesh(name = "cylinder")
        val seg = segments.coerceIn(6, 512)
        val bottomZ = centerZ - height / 2
        val topZ = centerZ + height / 2
        val bottomCenter = m.addVertex(Vec3(0f, 0f, bottomZ))
        val bottomRing = mutableListOf<Int>()
        val topRing = mutableListOf<Int>()
        for (i in 0 until seg) {
            val angle = 2.0 * Math.PI * i / seg
            val x = (radius * cos(angle)).toFloat()
            val y = (radius * sin(angle)).toFloat()
            bottomRing.add(m.addVertex(Vec3(x, y, bottomZ)))
            topRing.add(m.addVertex(Vec3(x * taperTop, y * taperTop, topZ)))
        }
        val topCenter = m.addVertex(Vec3(0f, 0f, topZ))
        for (i in 0 until seg) {
            val next = (i + 1) % seg
            // side (outward)
            m.addTriangle(bottomRing[i], bottomRing[next], topRing[next])
            m.addTriangle(bottomRing[i], topRing[next], topRing[i])
            // bottom cap (facing -Z)
            m.addTriangle(bottomCenter, bottomRing[next], bottomRing[i])
            // top cap (facing +Z)
            m.addTriangle(topCenter, topRing[i], topRing[next])
        }
        return m
    }

    fun cone(radius: Float, height: Float, segments: Int = 48, centerZ: Float = 0f): Mesh =
        cylinder(radius, height, segments, centerZ, taperTop = 0.001f)

    fun sphere(radius: Float, rings: Int = 24, segments: Int = 48): Mesh {
        val m = Mesh(name = "sphere")
        val r = rings.coerceIn(3, 256)
        val s = segments.coerceIn(3, 512)
        val top = m.addVertex(Vec3(0f, 0f, radius))
        val bottom = m.addVertex(Vec3(0f, 0f, -radius))
        val grid = mutableListOf<MutableList<Int>>()
        for (i in 1 until r) {
            val phi = Math.PI * i / r
            val row = mutableListOf<Int>()
            for (j in 0 until s) {
                val theta = 2.0 * Math.PI * j / s
                val x = (radius * sin(phi) * cos(theta)).toFloat()
                val y = (radius * sin(phi) * sin(theta)).toFloat()
                val z = (radius * cos(phi)).toFloat()
                row.add(m.addVertex(Vec3(x, y, z)))
            }
            grid.add(row)
        }
        for (j in 0 until s) {
            val next = (j + 1) % s
            m.addTriangle(top, grid[0][j], grid[0][next])
            m.addTriangle(bottom, grid.last()[next], grid.last()[j])
        }
        for (i in 0 until grid.size - 1) {
            for (j in 0 until s) {
                val next = (j + 1) % s
                m.addTriangle(grid[i][j], grid[i + 1][j], grid[i + 1][next])
                m.addTriangle(grid[i][j], grid[i + 1][next], grid[i][next])
            }
        }
        return m
    }

    fun torus(majorRadius: Float, minorRadius: Float, majorSegments: Int = 64, minorSegments: Int = 24): Mesh {
        val m = Mesh(name = "torus")
        val maj = majorSegments.coerceIn(3, 512)
        val min = minorSegments.coerceIn(3, 256)
        val grid = mutableListOf<MutableList<Int>>()
        for (i in 0 until maj) {
            val theta = 2.0 * Math.PI * i / maj
            val row = mutableListOf<Int>()
            for (j in 0 until min) {
                val phi = 2.0 * Math.PI * j / min
                val x = ((majorRadius + minorRadius * cos(phi)) * cos(theta)).toFloat()
                val y = ((majorRadius + minorRadius * cos(phi)) * sin(theta)).toFloat()
                val z = (minorRadius * sin(phi)).toFloat()
                row.add(m.addVertex(Vec3(x, y, z)))
            }
            grid.add(row)
        }
        for (i in 0 until maj) {
            val nextI = (i + 1) % maj
            for (j in 0 until min) {
                val nextJ = (j + 1) % min
                m.addTriangle(grid[i][j], grid[nextI][j], grid[nextI][nextJ])
                m.addTriangle(grid[i][j], grid[nextI][nextJ], grid[i][nextJ])
            }
        }
        return m
    }

    fun plane(width: Float, depth: Float, segments: Int = 1): Mesh {
        val m = Mesh(name = "plane")
        val seg = segments.coerceIn(1, 200)
        val hw = width / 2; val hd = depth / 2
        val idx = Array(seg + 1) { i -> Array(seg + 1) { j ->
            m.addVertex(Vec3(-hw + width * i / seg, -hd + depth * j / seg, 0f))
        } }
        for (i in 0 until seg) for (j in 0 until seg) {
            m.addTriangle(idx[i][j], idx[i + 1][j], idx[i + 1][j + 1])
            m.addTriangle(idx[i][j], idx[i + 1][j + 1], idx[i][j + 1])
        }
        return m
    }

    /** Extrudes a closed 2D polygon (in XY) along Z — the basis for gear/plate CAD-like parts. */
    fun extrudePolygon(points: List<Pair<Float, Float>>, height: Float): Mesh {
        require(points.size >= 3) { "polygon needs >= 3 points" }
        val m = Mesh(name = "extrusion")
        val n = points.size
        val bottom = points.map { m.addVertex(Vec3(it.first, it.second, 0f)) }
        val top = points.map { m.addVertex(Vec3(it.first, it.second, height)) }
        // triangulate the cap with an ear-clipping-free fan (works for convex; for
        // concave shapes triangle quality suffers but stays watertight-ish).
        // Signed area decides winding.
        var area = 0f
        for (i in 0 until n) {
            val a = points[i]; val b = points[(i + 1) % n]
            area += a.first * b.second - b.first * a.second
        }
        val ccw = area > 0
        for (i in 1 until n - 1) {
            if (ccw) {
                m.addTriangle(bottom[0], bottom[i + 1], bottom[i])
                m.addTriangle(top[0], top[i], top[i + 1])
            } else {
                m.addTriangle(bottom[0], bottom[i], bottom[i + 1])
                m.addTriangle(top[0], top[i + 1], top[i])
            }
        }
        for (i in 0 until n) {
            val next = (i + 1) % n
            if (ccw) {
                m.addTriangle(bottom[i], bottom[next], top[next])
                m.addTriangle(bottom[i], top[next], top[i])
            } else {
                m.addTriangle(bottom[next], bottom[i], top[i])
                m.addTriangle(bottom[next], top[i], top[next])
            }
        }
        return m
    }

    /**
     * Spur gear outline extruded to `thickness`. NOTE: `boreDiameter` is accepted by
     * the tool API but a true boolean bore requires a CSG kernel, which is not
     * implemented yet; the tool result states this explicitly instead of silently
     * returning a solid gear. Use cad_ring + assembly for a usable shaft hole.
     */
    fun gear(teeth: Int, module: Float, thickness: Float, boreDiameter: Float = 0f): Mesh {
        val t = teeth.coerceIn(6, 200)
        val pitchRadius = module * t / 2f
        val outerRadius = pitchRadius + module
        val rootRadius = (pitchRadius - 1.25f * module).coerceAtLeast(0.1f)
        val points = mutableListOf<Pair<Float, Float>>()
        val stepsPerTooth = 4
        for (i in 0 until t * stepsPerTooth) {
            val angle = 2.0 * Math.PI * i / (t * stepsPerTooth)
            val phase = i % stepsPerTooth
            val radius = if (phase <= 1) outerRadius else rootRadius
            points.add(((radius * cos(angle))).toFloat() to ((radius * sin(angle))).toFloat())
        }
        return extrudePolygon(points, thickness)
    }

    fun ring(outerDiameter: Float, innerDiameter: Float, height: Float, segments: Int = 72): Mesh {
        val m = Mesh(name = "ring")
        val seg = segments.coerceIn(6, 512)
        val ro = outerDiameter / 2f
        val ri = innerDiameter / 2f
        val outerBottom = mutableListOf<Int>()
        val outerTop = mutableListOf<Int>()
        val innerBottom = mutableListOf<Int>()
        val innerTop = mutableListOf<Int>()
        for (i in 0 until seg) {
            val angle = 2.0 * Math.PI * i / seg
            val cx = cos(angle).toFloat(); val cy = sin(angle).toFloat()
            outerBottom.add(m.addVertex(Vec3(ro * cx, ro * cy, 0f)))
            outerTop.add(m.addVertex(Vec3(ro * cx, ro * cy, height)))
            innerBottom.add(m.addVertex(Vec3(ri * cx, ri * cy, 0f)))
            innerTop.add(m.addVertex(Vec3(ri * cx, ri * cy, height)))
        }
        for (i in 0 until seg) {
            val next = (i + 1) % seg
            // outer wall (outward)
            m.addTriangle(outerBottom[i], outerBottom[next], outerTop[next])
            m.addTriangle(outerBottom[i], outerTop[next], outerTop[i])
            // inner wall (inward)
            m.addTriangle(innerBottom[next], innerBottom[i], innerTop[i])
            m.addTriangle(innerBottom[next], innerTop[i], innerTop[next])
            // top annulus (facing +Z)
            m.addTriangle(outerTop[i], outerTop[next], innerTop[next])
            m.addTriangle(outerTop[i], innerTop[next], innerTop[i])
            // bottom annulus (facing -Z)
            m.addTriangle(outerBottom[next], outerBottom[i], innerBottom[i])
            m.addTriangle(outerBottom[next], innerBottom[i], innerBottom[next])
        }
        return m
    }

    // ------------------------------------------------------------- serializers

    fun toBinaryStl(mesh: Mesh): ByteArray {
        val normals = mesh.computeNormals()
        val out = ByteArrayOutputStream(84 + mesh.triangleCount * 50)
        val header = ByteArray(80)
        "MobileAIAgent binary STL".toByteArray().copyInto(header)
        out.write(header)
        writeIntLE(out, mesh.triangleCount)
        for ((i, t) in mesh.triangles.withIndex()) {
            val n = normals[i]
            writeFloatLE(out, n.x); writeFloatLE(out, n.y); writeFloatLE(out, n.z)
            for (vi in t) {
                val v = mesh.positions[vi]
                writeFloatLE(out, v.x); writeFloatLE(out, v.y); writeFloatLE(out, v.z)
            }
            out.write(0); out.write(0) // attribute byte count
        }
        return out.toByteArray()
    }

    fun toAsciiStl(mesh: Mesh, name: String = "mobileaiagent"): String {
        val normals = mesh.computeNormals()
        val sb = StringBuilder(64 + mesh.triangleCount * 200)
        sb.append("solid ").append(name).append('\n')
        for ((i, t) in mesh.triangles.withIndex()) {
            val n = normals[i]
            sb.append("  facet normal ").append(fmt(n.x)).append(' ').append(fmt(n.y)).append(' ').append(fmt(n.z)).append('\n')
            sb.append("    outer loop\n")
            for (vi in t) {
                val v = mesh.positions[vi]
                sb.append("      vertex ").append(fmt(v.x)).append(' ').append(fmt(v.y)).append(' ').append(fmt(v.z)).append('\n')
            }
            sb.append("    endloop\n  endfacet\n")
        }
        sb.append("endsolid ").append(name).append('\n')
        return sb.toString()
    }

    fun toObj(mesh: Mesh, objectName: String = "object", withMtl: Boolean = false): String {
        val sb = StringBuilder()
        if (withMtl) sb.append("mtllib ${objectName}.mtl\n")
        sb.append("# Generated by MobileAIAgent\n")
        sb.append("o ").append(objectName).append('\n')
        mesh.positions.forEach { p -> sb.append("v ").append(fmt(p.x)).append(' ').append(fmt(p.y)).append(' ').append(fmt(p.z)).append('\n') }
        // OBJ is 1-indexed.
        mesh.triangles.forEach { t -> sb.append("f ").append(t[0] + 1).append(' ').append(t[1] + 1).append(' ').append(t[2] + 1).append('\n') }
        return sb.toString()
    }

    fun defaultMtl(name: String): String = """
        |# MobileAIAgent material
        |newmtl ${name}_material
        |Ka 0.2 0.2 0.2
        |Kd 0.72 0.76 0.82
        |Ks 0.25 0.25 0.25
        |Ns 64
        |d 1.0
        |illum 2
        |""".trimMargin()
}

/**
 * glTF 2.0 binary writer. Produces a spec-compliant single-mesh .glb:
 * header + JSON chunk + BIN chunk, positions/normals/indices as typed views.
 */
object GlbWriter {

    fun write(mesh: Geometry.Mesh, fileName: String = "mesh"): ByteArray {
        val normals = mesh.computeNormals()
        // Expand to per-triangle vertices (simple, always correct normals).
        val positions = floatArrayOf().toMutableList()
        val normalsList = floatArrayOf().toMutableList()
        val indices = mutableListOf<Int>()
        val bounds = mesh.bounds()
        val center = bounds.center
        var index = 0
        for ((i, t) in mesh.triangles.withIndex()) {
            val n = normals[i]
            for (vi in t) {
                val p = mesh.positions[vi]
                positions.add(p.x); positions.add(p.y); positions.add(p.z)
                normalsList.add(n.x); normalsList.add(n.y); normalsList.add(n.z)
                indices.add(index++)
            }
        }
        val vertexCount = verticesCount(positions)
        val positionBytes = floatsToBytes(positions)
        val normalBytes = floatsToBytes(normalsList)
        val indexBytes = intsToBytes(indices)
        fun pad4(size: Int): Int = (4 - (size % 4)) % 4
        val positionOffset = 0
        val normalOffset = positionOffset + positionBytes.size + pad4(positionBytes.size)
        val indexOffset = normalOffset + normalBytes.size + pad4(normalBytes.size)
        val binLength = indexOffset + indexBytes.size + pad4(indexBytes.size)
        val bin = ByteArray(binLength)
        positionBytes.copyInto(bin, positionOffset)
        normalBytes.copyInto(bin, normalOffset)
        indexBytes.copyInto(bin, indexOffset)

        val minBound = listOf(bounds.min.x, bounds.min.y, bounds.min.z).joinToString(",") { it.toString() }
        val maxBound = listOf(bounds.max.x, bounds.max.y, bounds.max.z).joinToString(",") { it.toString() }
        val json = """
            {
              "asset": {"version": "2.0", "generator": "MobileAIAgent"},
              "scene": 0,
              "scenes": [{"nodes": [0]}],
              "nodes": [{"mesh": 0, "name": "${escape(fileName)}"}],
              "meshes": [{
                "name": "${escape(fileName)}",
                "primitives": [{
                  "attributes": {"POSITION": 0, "NORMAL": 1},
                  "indices": 2,
                  "material": 0
                }]
              }],
              "materials": [{
                "name": "default",
                "pbrMetallicRoughness": {"baseColorFactor": [0.72, 0.76, 0.82, 1.0], "metallicFactor": 0.1, "roughnessFactor": 0.6}
              }],
              "buffers": [{"byteLength": $binLength}],
              "bufferViews": [
                {"buffer": 0, "byteOffset": $positionOffset, "byteLength": ${positionBytes.size}, "target": 34962},
                {"buffer": 0, "byteOffset": $normalOffset, "byteLength": ${normalBytes.size}, "target": 34962},
                {"buffer": 0, "byteOffset": $indexOffset, "byteLength": ${indexBytes.size}, "target": 34963}
              ],
              "accessors": [
                {"bufferView": 0, "componentType": 5126, "count": $vertexCount, "type": "VEC3", "min": [$minBound], "max": [$maxBound]},
                {"bufferView": 1, "componentType": 5126, "count": $vertexCount, "type": "VEC3"},
                {"bufferView": 2, "componentType": 5125, "count": ${indices.size}, "type": "SCALAR"}
              ]
            }
        """.trimIndent()
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPadded = jsonBytes.copyOf(jsonBytes.size + pad4(jsonBytes.size)).also {
            for (i in jsonBytes.size until it.size) it[i] = ' '.code.toByte()
        }
        val totalLength = 12 + 8 + jsonPadded.size + 8 + bin.size
        val out = ByteArrayOutputStream(totalLength)
        writeIntLE(out, 0x46546C67) // "glTF"
        writeIntLE(out, 2)
        writeIntLE(out, totalLength)
        writeIntLE(out, jsonPadded.size)
        writeIntLE(out, 0x4E4F534A) // "JSON"
        out.write(jsonPadded)
        writeIntLE(out, bin.size)
        writeIntLE(out, 0x004E4942) // "BIN\0"
        out.write(bin)
        return out.toByteArray()
    }

    private fun verticesCount(floatList: MutableList<Float>): Int = floatList.size / 3

    private fun escape(s: String): String = s.replace("\"", "\\\"")

    private fun floatsToBytes(values: MutableList<Float>): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(values.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun intsToBytes(values: MutableList<Int>): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(values.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putInt(it) }
        return buffer.array()
    }
}

internal fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF); out.write((value shr 8) and 0xFF)
    out.write((value shr 16) and 0xFF); out.write((value shr 24) and 0xFF)
}

internal fun writeFloatLE(out: ByteArrayOutputStream, value: Float) {
    writeIntLE(out, java.lang.Float.floatToIntBits(value))
}

internal fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format("%.4f", f)

/** Reads STL (binary/ASCII), OBJ and GLB metadata + geometry for inspection. */
object MeshReader {

    data class MeshInfo(
        val format: String,
        val triangleCount: Int,
        val vertexCount: Int,
        val bounds: Geometry.Bounding?,
        val volume: Float?,
        val surfaceArea: Float?,
        val watertightHeuristic: Boolean?,
        val notes: List<String> = emptyList(),
    )

    fun inspect(path: String, bytes: ByteArray): MeshInfo {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "stl" -> inspectStl(bytes)
            "obj" -> inspectObj(bytes)
            "glb", "gltf" -> inspectGlb(bytes)
            else -> MeshInfo(ext, 0, 0, null, null, null, null, listOf("Unsupported mesh format '.$ext'"))
        }
    }

    fun readMesh(path: String, bytes: ByteArray): Geometry.Mesh? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "stl" -> readStl(bytes)
            "obj" -> readObj(bytes)
            "glb" -> readGlb(bytes)
            else -> null
        }
    }

    fun readStl(bytes: ByteArray): Geometry.Mesh? {
        if (bytes.size >= 84) {
            val triangles = java.nio.ByteBuffer.wrap(bytes, 80, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            val expected = 84 + triangles * 50
            if (triangles in 0..20_000_000 && expected == bytes.size) {
                val mesh = Geometry.Mesh(name = "stl")
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buffer.position(84)
                repeat(triangles) {
                    buffer.getFloat(); buffer.getFloat(); buffer.getFloat() // normal, recomputed
                    val base = mesh.vertexCount
                    mesh.addVertex(Geometry.Vec3(buffer.getFloat(), buffer.getFloat(), buffer.getFloat()))
                    mesh.addVertex(Geometry.Vec3(buffer.getFloat(), buffer.getFloat(), buffer.getFloat()))
                    mesh.addVertex(Geometry.Vec3(buffer.getFloat(), buffer.getFloat(), buffer.getFloat()))
                    buffer.getShort()
                    mesh.addTriangle(base, base + 1, base + 2)
                }
                return mesh
            }
        }
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.trimStart().startsWith("solid")) return null
        val mesh = Geometry.Mesh(name = "stl_ascii")
        val vertexRegex = Regex("vertex\\s+([-0-9.eE+]+)\\s+([-0-9.eE+]+)\\s+([-0-9.eE+]+)")
        val current = mutableListOf<Int>()
        for (m in vertexRegex.findAll(text)) {
            val v = Geometry.Vec3(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat())
            current.add(mesh.addVertex(v))
            if (current.size == 3) { mesh.addTriangle(current[0], current[1], current[2]); current.clear() }
        }
        return mesh.takeIf { it.triangleCount > 0 }
    }

    fun inspectStl(bytes: ByteArray): MeshInfo {
        val mesh = readStl(bytes) ?: return MeshInfo("STL", 0, 0, null, null, null, null, listOf("Could not parse STL"))
        val bounds = mesh.bounds()
        val volume = mesh.volume()
        val area = mesh.surfaceArea()
        return MeshInfo(
            format = if (bytes.size >= 84 && bytes[0] != 's'.code.toByte()) "STL (binary)" else "STL (ascii)",
            triangleCount = mesh.triangleCount, vertexCount = mesh.vertexCount, bounds = bounds,
            volume = volume, surfaceArea = area,
            watertightHeuristic = volume > 0f,
            notes = buildList {
                if (volume <= 0f) add("Non-positive volume: winding may be inverted or the mesh is open.")
                val size = bounds.size
                if (size.x == 0f || size.y == 0f || size.z == 0f) add("Model is flat in at least one axis.")
            },
        )
    }

    fun readObj(bytes: ByteArray): Geometry.Mesh? {
        val mesh = Geometry.Mesh(name = "obj")
        bytes.toString(Charsets.UTF_8).lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("v ") -> {
                    val parts = trimmed.substring(2).trim().split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val x = parts[0].toFloatOrNull() ?: 0f
                        val y = parts[1].toFloatOrNull() ?: 0f
                        val z = parts[2].toFloatOrNull() ?: 0f
                        mesh.addVertex(Geometry.Vec3(x, y, z))
                    }
                }
                trimmed.startsWith("f ") -> {
                    val refs = trimmed.substring(2).trim().split(Regex("\\s+")).mapNotNull { ref ->
                        ref.substringBefore('/').toIntOrNull()?.let { if (it < 0) mesh.vertexCount + it else it - 1 }
                    }
                    // Fan-triangulate polygons.
                    for (i in 1 until refs.size - 1) {
                        if (refs[i] >= 0 && refs[i + 1] >= 0) mesh.addTriangle(refs[0], refs[i], refs[i + 1])
                    }
                }
            }
        }
        return mesh.takeIf { it.triangleCount > 0 }
    }

    fun inspectObj(bytes: ByteArray): MeshInfo {
        val mesh = readObj(bytes) ?: return MeshInfo("OBJ", 0, 0, null, null, null, null, listOf("Could not parse OBJ"))
        return MeshInfo("OBJ", mesh.triangleCount, mesh.vertexCount, mesh.bounds(), mesh.volume(), mesh.surfaceArea(), null)
    }

    fun readGlb(bytes: ByteArray): Geometry.Mesh? {
        if (bytes.size < 12) return null
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != 0x46546C67) return null
        buffer.int
        buffer.int
        val jsonLength = buffer.int
        if (jsonLength <= 0 || 20 + jsonLength > bytes.size) return null
        buffer.int // 'JSON'
        val jsonBytes = bytes.copyOfRange(20, 20 + jsonLength)
        val json = com.mobileaiagent.core.json.JsonParser.parseOrNull(jsonBytes.toString(Charsets.UTF_8)) ?: return null
        val binHeaderOffset = 20 + jsonLength
        if (binHeaderOffset + 8 > bytes.size) return null
        val binLength = java.nio.ByteBuffer.wrap(bytes, binHeaderOffset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        val binStart = binHeaderOffset + 8
        val accessors = json["accessors"].asArrayOrNull() ?: return null
        val views = json["bufferViews"].asArrayOrNull() ?: return null
        fun readFloats(viewIndex: Int, accessorIndex: Int): List<Geometry.Vec3> {
            val accessor = accessors.getOrNull(accessorIndex) ?: return emptyList()
            val view = views.getOrNull(viewIndex) ?: return emptyList()
            val count = accessor.intOr("count", 0)
            val offset = binStart + view.intOr("byteOffset", 0) + accessor.intOr("byteOffset", 0)
            val out = mutableListOf<Geometry.Vec3>()
            for (i in 0 until count) {
                val at = offset + i * 12
                if (at + 12 > bytes.size) break
                val b = java.nio.ByteBuffer.wrap(bytes, at, 12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                out.add(Geometry.Vec3(b.float, b.float, b.float))
            }
            return out
        }
        val positions = readFloats(0, 0)
        if (positions.isEmpty()) return null
        val indices = mutableListOf<Int>()
        if (views.size > 2 && accessors.size > 2) {
            val view = views[2]
            val accessor = accessors[2]
            val count = accessor.intOr("count", 0)
            val offset = binStart + view.intOr("byteOffset", 0) + accessor.intOr("byteOffset", 0)
            val buffer2 = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val at = offset + i * 4
                if (at + 4 > bytes.size) break
                buffer2.position(at)
                indices.add(buffer2.int)
            }
        }
        val mesh = Geometry.Mesh(name = "glb")
        positions.forEach { mesh.addVertex(it) }
        if (indices.size >= 3) {
            for (i in 0 until indices.size - 2 step 3) {
                if (indices[i + 2] < mesh.vertexCount) mesh.addTriangle(indices[i], indices[i + 1], indices[i + 2])
            }
        } else {
            for (i in 0 until positions.size - 2 step 3) mesh.addTriangle(i, i + 1, i + 2)
        }
        return mesh
    }

    fun inspectGlb(bytes: ByteArray): MeshInfo {
        val mesh = readGlb(bytes) ?: return MeshInfo("GLB", 0, 0, null, null, null, null, listOf("Could not parse GLB"))
        return MeshInfo("glTF binary", mesh.triangleCount, mesh.vertexCount, mesh.bounds(), null, mesh.surfaceArea(), null)
    }
}
