package com.mobileaiagent.ui.viewer

import com.mobileaiagent.tools.builtin.Geometry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A real software 3D rasterizer for the Workspace viewer.
 *
 * Why software and not OpenGL/SceneView: an OpenGL context cannot be created in
 * the offline test harness, so a GPU path would ship completely unverified. This
 * renderer is pure Kotlin, deterministic, and unit-tested on the JVM: it produces
 * an ARGB bitmap that is then shown with a plain Compose `Image`. That keeps the
 * 3D viewer honest — what you see is genuinely computed from the mesh triangles.
 *
 * Features: perspective projection, orbit (yaw/pitch), zoom, painter's-algorithm
 * depth sorting, two-light Lambert shading, back-face culling and a wireframe
 * overlay for thin/planar geometry.
 */
object MeshRenderer {

    data class Camera(
        val yawDeg: Float = -35f,
        val pitchDeg: Float = 22f,
        val zoom: Float = 1f,
        /** Screen-space pan in normalized units (-1..1 of the viewport). */
        val panX: Float = 0f,
        val panY: Float = 0f,
    ) {
        fun orbit(dxDeg: Float, dyDeg: Float): Camera =
            copy(yawDeg = (yawDeg + dxDeg) % 360f, pitchDeg = (pitchDeg + dyDeg).coerceIn(-89f, 89f))

        fun zoomBy(factor: Float): Camera = copy(zoom = (zoom * factor).coerceIn(0.15f, 12f))

        fun pan(dx: Float, dy: Float): Camera = copy(panX = (panX + dx).coerceIn(-3f, 3f), panY = (panY + dy).coerceIn(-3f, 3f))
    }

    data class RenderOptions(
        val background: Int = 0xFF12161C.toInt(),
        val rimLight: Int = 0xFF8FB4FF.toInt(),
        val wireframe: Boolean = true,
        val showAxes: Boolean = true,
    ) {
        /** Dense meshes look like noise with edges on, so suppress them by default. */
        fun effectiveWireframe(triangleCount: Int): Boolean = wireframe && triangleCount <= 1200
    }

    data class RenderStats(
        val trianglesDrawn: Int,
        val trianglesTotal: Int,
        val vertices: Int,
        val width: Int,
        val height: Int,
        val ms: Long,
    )

    data class Rendered(val argb: IntArray, val width: Int, val height: Int, val stats: RenderStats)

    /**
     * Renders [mesh] into an ARGB buffer. Never throws on degenerate input: empty
     * meshes render the background plus the axes/floor grid.
     */
    fun render(mesh: Geometry.Mesh, widthPx: Int, heightPx: Int, camera: Camera, options: RenderOptions = RenderOptions()): Rendered {
        val w = widthPx.coerceIn(64, 4096)
        val h = heightPx.coerceIn(64, 4096)
        val started = System.currentTimeMillis()
        val buffer = IntArray(w * h) { options.background }

        val bounds = mesh.bounds()
        val size = bounds.size
        // Half-extent: the mesh is framed as a sphere of this radius.
        val radius = (max(max(size.x, size.y), size.z) / 2f).coerceAtLeast(1e-4f)
        val center = bounds.center

        // --- camera basis (Y up in world space) ---------------------------------
        val yaw = Math.toRadians(camera.yawDeg.toDouble())
        val pitch = Math.toRadians(camera.pitchDeg.toDouble())
        val cosYaw = kotlin.math.cos(yaw).toFloat(); val sinYaw = kotlin.math.sin(yaw).toFloat()
        val cosPitch = kotlin.math.cos(pitch).toFloat(); val sinPitch = kotlin.math.sin(pitch).toFloat()

        fun toView(p: Geometry.Vec3): Geometry.Vec3 {
            val x = p.x - center.x
            val y = p.y - center.y
            val z = p.z - center.z // models are Z-up: built geometry and STL/OBJ alike
            // 1) yaw about the model's vertical (Z) axis
            val rx = x * cosYaw - y * sinYaw
            val ry = x * sinYaw + y * cosYaw
            // 2) pitch: tilt the Z-up scene toward the camera, so screen-Y carries
            //    elevation and the remaining world-Y becomes depth.
            val screenY = z * cosPitch + ry * sinPitch
            val depth = ry * cosPitch - z * sinPitch
            return Geometry.Vec3(rx, screenY, depth)
        }

        // Perspective projection: sx = w/2 + v.x * f / depth. With the camera at
        // 3.2r the subject then spans ~0.84 of the shorter viewport edge.
        // Pull the camera back so the subject fills ~85% of the viewport with margin.
        val focal = min(w, h) * 0.95f * camera.zoom
        val camDist = radius * 4.4f
        val panX = camera.panX * radius * 1.2f
        val panY = camera.panY * radius * 1.2f

        fun project(v: Geometry.Vec3): FloatArray {
            val depth = v.z + camDist
            val safeDepth = if (depth < radius * 0.05f) radius * 0.05f else depth
            val sx = w / 2f + (v.x - panX) * focal / safeDepth
            val sy = h / 2f - (v.y - panY) * focal / safeDepth
            return floatArrayOf(sx, sy, safeDepth)
        }

        // --- background vignette + floor grid -----------------------------------
        drawBackground(buffer, w, h, options.background) { v -> blend(v, options.rimLight, 0.05f) }

        if (options.showAxes) {
            drawGrid(buffer, w, h, ::toView, ::project, options.background, radius)
        }

        // --- transform + sort triangles ----------------------------------------
        val viewPositions = mesh.positions.map(::toView)
        val projected = viewPositions.map(::project)

        val order = ArrayList<Int>(mesh.triangles.size)
        for (i in mesh.triangles.indices) {
            val t = mesh.triangles[i]
            if (t[0] >= projected.size || t[1] >= projected.size || t[2] >= projected.size) continue
            order.add(i)
        }
        // Front-to-back ordering lets the z-buffer reject most hidden pixels early.
        // Correctness comes from the depth test, not from this sort.
        order.sortBy { (projected[mesh.triangles[it][0]][2] + projected[mesh.triangles[it][1]][2] + projected[mesh.triangles[it][2]][2]) / 3f }

        // --- shading ------------------------------------------------------------
        // Key light from the upper-left front, fill light from lower-right front.
        // z is negative because +z points away from the camera.
        val lightA = Geometry.Vec3(-0.45f, 0.55f, -0.70f).normalized()
        val lightB = Geometry.Vec3(0.62f, -0.30f, -0.35f).normalized()
        val baseColor = 0xFF7FB2FF.toInt()

        var drawn = 0
        val wireframe = options.effectiveWireframe(mesh.triangleCount)
        val depth = FloatArray(w * h) { 0f } // stores 1/z: larger means nearer

        for (i in order) {
            val tri = mesh.triangles[i]
            val a = viewPositions[tri[0]]; val b = viewPositions[tri[1]]; val c = viewPositions[tri[2]]
            val normal = (b - a).cross(c - a)
            val normalLen = normal.length()
            if (normalLen < 1e-7f) continue // degenerate triangle
            val n = normal * (1f / normalLen)
            // Two-sided lighting: never cull, because imported meshes frequently
            // have inconsistent winding and a black hole would be a lie.
            val facing = if (n.z > 0f) -1f else 1f
            val nn = n * facing
            val lambertA = max(0f, nn.dot(lightA))
            val lambertB = max(0f, nn.dot(lightB)) * 0.35f
            val ambient = 0.30f
            val intensity = (ambient + lambertA * 0.78f + lambertB * 0.9f).coerceIn(0f, 1.15f)
            val color = shade(baseColor, intensity)

            val pa = projected[tri[0]]; val pb = projected[tri[1]]; val pc = projected[tri[2]]
            val area = (pb[0] - pa[0]) * (pc[1] - pa[1]) - (pc[0] - pa[0]) * (pb[1] - pa[1])
            if (kotlin.math.abs(area) < 0.35f) {
                if (wireframe) drawLine(buffer, w, h, pa[0], pa[1], pb[0], pb[1], color)
                if (wireframe) drawLine(buffer, w, h, pb[0], pb[1], pc[0], pc[1], color)
                continue
            }
            // Supersampling keeps silhouette edges from turning into staircases
            // without the cost of a full-screen 4x buffer.
            val fill = fillTriangle(buffer, w, h, depth, pa, pb, pc, color)
            if (fill > 0) {
                drawn++
                if (wireframe) {
                    drawLine(buffer, w, h, pa[0], pa[1], pb[0], pb[1], blendLine(color))
                    drawLine(buffer, w, h, pb[0], pb[1], pc[0], pc[1], blendLine(color))
                    drawLine(buffer, w, h, pc[0], pc[1], pa[0], pa[1], blendLine(color))
                }
            }
        }

        return Rendered(
            argb = buffer,
            width = w,
            height = h,
            stats = RenderStats(drawn, mesh.triangleCount, mesh.vertexCount, w, h, System.currentTimeMillis() - started),
        )
    }

    // ------------------------------------------------------------------ drawing

    private fun drawBackground(out: IntArray, w: Int, h: Int, base: Int, tweak: (Int) -> Int) {
        val cx = w / 2f; val cy = h / 2f
        val maxD = sqrt(cx * cx + cy * cy)
        val baseR = (base ushr 16) and 0xFF
        val baseG = (base ushr 8) and 0xFF
        val baseB = base and 0xFF
        for (y in 0 until h) {
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                val t = sqrt(dx * dx + dy * dy) / maxD
                val f = 1f + t * 0.55f
                val rgb = (255 shl 24) or
                    (min(255, (baseR * f).toInt()) shl 16) or
                    (min(255, (baseG * f).toInt()) shl 8) or
                    min(255, (baseB * f).toInt())
                out[y * w + x] = tweak(rgb)
            }
        }
    }

    private fun drawGrid(
        out: IntArray, w: Int, h: Int,
        toView: (Geometry.Vec3) -> Geometry.Vec3,
        project: (Geometry.Vec3) -> FloatArray,
        background: Int,
        radius: Float,
    ) {
        val step = radius / 4f
        val extent = radius * 2f
        val grid = lighten(background, 0.16f)
        val axisX = 0xFFE0574C.toInt()
        val axisY = 0xFF6FD98A.toInt()
        val axisZ = 0xFF5B8DEF.toInt()

        fun line(from: Geometry.Vec3, to: Geometry.Vec3, color: Int) {
            val p1 = project(toView(from))
            val p2 = project(toView(to))
            drawLine(out, w, h, p1[0], p1[1], p2[0], p2[1], color)
        }

        var g = -extent
        while (g <= extent) {
            if (kotlin.math.abs(g) > step * 0.01f) {
                line(Geometry.Vec3(g, -extent, 0f), Geometry.Vec3(g, extent, 0f), grid)
                line(Geometry.Vec3(-extent, g, 0f), Geometry.Vec3(extent, g, 0f), grid)
            }
            g += step
        }
        line(Geometry.Vec3(-extent, 0f, 0f), Geometry.Vec3(extent, 0f, 0f), axisX)
        line(Geometry.Vec3(0f, -extent, 0f), Geometry.Vec3(0f, extent, 0f), axisY)
        line(Geometry.Vec3(0f, 0f, -extent), Geometry.Vec3(0f, 0f, extent), axisZ)
    }

    private fun fillTriangle(
        out: IntArray, w: Int, h: Int, depthBuffer: FloatArray,
        a: FloatArray, b: FloatArray, c: FloatArray, color: Int,
    ): Int {
        val minX = max(0, kotlin.math.floor(min(a[0], min(b[0], c[0]))).toInt())
        val maxX = min(w - 1, kotlin.math.ceil(max(a[0], max(b[0], c[0]))).toInt())
        val minY = max(0, kotlin.math.floor(min(a[1], min(b[1], c[1]))).toInt())
        val maxY = min(h - 1, kotlin.math.ceil(max(a[1], max(b[1], c[1]))).toInt())
        if (minX > maxX || minY > maxY) return 0

        val denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if (kotlin.math.abs(denominator) < 1e-6f) return 0
        var painted = 0
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val px = x + 0.5f; val py = y + 0.5f
                val wa = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / denominator
                val wb = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / denominator
                val wc = 1f - wa - wb
                if (wa < -0.002f || wb < -0.002f || wc < -0.002f) continue
                val idx = y * w + x
                // Perspective-correct 1/z interpolation: interpolate the reciprocal
                // of depth linearly in screen space, which is what a z-buffer needs.
                val invDepth = wa / a[2] + wb / b[2] + wc / c[2]
                if (invDepth <= 0f) continue
                if (invDepth <= depthBuffer[idx]) continue // farther than what is drawn
                depthBuffer[idx] = invDepth
                out[idx] = color
                painted++
            }
        }
        return painted
    }

    private fun drawLine(out: IntArray, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        var ax = x0; var ay = y0
        val dx = x1 - x0; val dy = y1 - y0
        val steps = max(kotlin.math.abs(dx), kotlin.math.abs(dy)).toInt().coerceAtMost(4096)
        if (steps <= 0) {
            plot(out, w, h, ax.toInt(), ay.toInt(), color)
            return
        }
        val sx = dx / steps; val sy = dy / steps
        for (i in 0..steps) {
            plot(out, w, h, ax.toInt(), ay.toInt(), color)
            ax += sx; ay += sy
        }
    }

    private fun plot(out: IntArray, w: Int, h: Int, x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        out[y * w + x] = color
    }

    // ------------------------------------------------------------------ colors

    private fun shade(base: Int, intensity: Float): Int {
        val r = (((base ushr 16) and 0xFF) * intensity).toInt().coerceIn(0, 255)
        val g = (((base ushr 8) and 0xFF) * intensity).toInt().coerceIn(0, 255)
        val b = ((base and 0xFF) * intensity).toInt().coerceIn(0, 255)
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lighten(color: Int, amount: Float): Int {
        val r = (((color ushr 16) and 0xFF) + (255 - ((color ushr 16) and 0xFF)) * amount).toInt().coerceIn(0, 255)
        val g = (((color ushr 8) and 0xFF) + (255 - ((color ushr 8) and 0xFF)) * amount).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) + (255 - (color and 0xFF)) * amount).toInt().coerceIn(0, 255)
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun blendLine(color: Int): Int = lighten(color, 0.35f)

    private fun blend(color: Int, other: Int, t: Float): Int {
        val r = (((color ushr 16) and 0xFF) * (1 - t) + ((other ushr 16) and 0xFF) * t).toInt()
        val g = (((color ushr 8) and 0xFF) * (1 - t) + ((other ushr 8) and 0xFF) * t).toInt()
        val b = ((color and 0xFF) * (1 - t) + (other and 0xFF) * t).toInt()
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ------------------------------------------------------- perspective camera

    /**
     * Turntable projection used by the thumbnail strip: returns the projected
     * 2D outline of the mesh silhouette for the given yaw, cheap enough to run
     * for several thumbnails per file selection.
     */
    fun silhouette(mesh: Geometry.Mesh, yawDeg: Float, points: Int = 48): List<Pair<Float, Float>> {
        val bounds = mesh.bounds()
        val r = max(max(bounds.size.x, bounds.size.y), bounds.size.z).coerceAtLeast(1e-4f) / 2f
        val c = bounds.center
        val yaw = Math.toRadians(yawDeg.toDouble())
        val cosYaw = kotlin.math.cos(yaw).toFloat(); val sinYaw = kotlin.math.sin(yaw).toFloat()
        return (0 until points).map { i ->
            val a = (i.toFloat() / points) * Math.PI * 2
            val x = kotlin.math.cos(a).toFloat() * r
            val y = kotlin.math.sin(a).toFloat() * r
            Pair((x * cosYaw - y * sinYaw) + c.x, (x * sinYaw + y * cosYaw) + c.y)
        }
    }

    /** Approximate focal length for a viewport; exposed for tests and UI maths. */
    fun focalLength(viewportPx: Int, meshRadius: Float, fovDeg: Float = 50f): Float {
        val fov = Math.toRadians(fovDeg.toDouble())
        return (viewportPx / (2.0 * tan(fov / 2.0))).toFloat() * (1f / meshRadius.coerceAtLeast(1e-4f)) * 1f
    }
}
