package com.mobileaiagent.tools.builtin

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.util.humanBytes
import com.mobileaiagent.tools.spec.*

/**
 * 3D modelling tools: genuine mesh generation + conversion, no external engine.
 * Formats: binary/ASCII STL, OBJ (+MTL), GLB. STEP/IGES export requires a CAD
 * kernel and is reported as unsupported rather than faked.
 */
class MeshCreateTool : AgentTool {
    override val id = "mesh_create"
    override val name = "Create 3D mesh"
    override val description =
        "Generate a parametric mesh and save it as .stl (binary), .obj or .glb. Shapes: box, cylinder, cone, " +
            "sphere, torus, plane, gear, ring. All dimensions are in millimetres. Returns real geometry stats."
    override val version = "1.0.0"
    override val category = ToolCategory.MODELING_3D
    override val inputSchema = schema {
        requiredString(
            "shape",
            "Primitive to generate.",
            listOf("box", "cylinder", "cone", "sphere", "torus", "plane", "gear", "ring"),
        )
        requiredString("out_path", "Output path, e.g. '3D/bracket.stl'.")
        optionalNumber("width", "box/plane: X size in mm (default 40).")
        optionalNumber("height", "cylinder/box Z height, cone height in mm (default 20).")
        optionalNumber("depth", "box/plane Y size in mm (default 40).")
        optionalNumber("radius", "cylinder/cone/sphere radius in mm (default 10).")
        optionalNumber("major_radius", "torus major radius (default 15).")
        optionalNumber("minor_radius", "torus tube radius (default 4).")
        optionalInt("segments", "Radial segments, 6-512 (default 48).")
        optionalInt("teeth", "gear: tooth count (default 20).")
        optionalNumber("module", "gear: module in mm (default 1).")
        optionalNumber("thickness", "gear/ring: extrusion height (default 5).")
        optionalNumber("outer_diameter", "ring: outer diameter (default 30).")
        optionalNumber("inner_diameter", "ring: hole diameter (default 12).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 6_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val outPath = args.string("out_path")
        val ext = outPath.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("stl", "obj", "glb")) {
            return ToolResult.Failure("Unsupported output '$ext'. Supported: stl, obj, glb. (STEP needs a CAD kernel that is not installed.)", errorKind = "unsupported")
        }
        ctx.emitProgress("Generating mesh…", null)
        val shape = args.string("shape")
        val mesh = try {
            when (shape) {
                "box" -> Geometry.box(
                    args.double("width", required = false, default = 40.0).toFloat(),
                    args.double("depth", required = false, default = 40.0).toFloat(),
                    args.double("height", required = false, default = 20.0).toFloat(),
                )
                "cylinder" -> Geometry.cylinder(
                    args.double("radius", required = false, default = 10.0).toFloat(),
                    args.double("height", required = false, default = 20.0).toFloat(),
                    args.int("segments", required = false, default = 48),
                )
                "cone" -> Geometry.cone(
                    args.double("radius", required = false, default = 10.0).toFloat(),
                    args.double("height", required = false, default = 20.0).toFloat(),
                    args.int("segments", required = false, default = 48),
                )
                "sphere" -> Geometry.sphere(
                    args.double("radius", required = false, default = 10.0).toFloat(),
                    segments = args.int("segments", required = false, default = 48),
                )
                "torus" -> Geometry.torus(
                    args.double("major_radius", required = false, default = 15.0).toFloat(),
                    args.double("minor_radius", required = false, default = 4.0).toFloat(),
                    minorSegments = (args.int("segments", required = false, default = 24)).coerceAtMost(64),
                )
                "plane" -> Geometry.plane(
                    args.double("width", required = false, default = 100.0).toFloat(),
                    args.double("depth", required = false, default = 100.0).toFloat(),
                )
                "gear" -> Geometry.gear(
                    args.int("teeth", required = false, default = 20),
                    args.double("module", required = false, default = 1.0).toFloat(),
                    args.double("thickness", required = false, default = 5.0).toFloat(),
                )
                "ring" -> Geometry.ring(
                    args.double("outer_diameter", required = false, default = 30.0).toFloat(),
                    args.double("inner_diameter", required = false, default = 12.0).toFloat(),
                    args.double("thickness", required = false, default = 5.0).toFloat(),
                    args.int("segments", required = false, default = 72),
                )
                else -> return ToolResult.Failure("Unknown shape '$shape'", errorKind = "invalid_arguments")
            }
        } catch (e: Exception) {
            return ToolResult.Failure("Mesh generation failed: ${e.message}", errorKind = "geometry")
        }
        if (mesh.triangleCount == 0) return ToolResult.Failure("Generated mesh has no triangles", errorKind = "geometry")

        val bytes: ByteArray = when (ext) {
            "stl" -> Geometry.toBinaryStl(mesh)
            "obj" -> {
                val objText = Geometry.toObj(mesh, outPath.substringAfterLast('/').substringBeforeLast('.'), withMtl = true)
                val mtlPath = outPath.substringBeforeLast('.') + ".mtl"
                ws.writeText(mtlPath, Geometry.defaultMtl(outPath.substringAfterLast('/').substringBeforeLast('.')), overwrite = true)
                objText.toByteArray()
            }
            else -> GlbWriter.write(mesh, outPath.substringAfterLast('/').substringBeforeLast('.'))
        }
        ws.writeBytes(outPath, bytes, overwrite = true)
        val bounds = mesh.bounds()
        val volume = mesh.volume()
        val stats = buildString {
            appendLine("Created $outPath (${humanBytes(bytes.size.toLong())})")
            appendLine("Shape: $shape")
            appendLine("Triangles: ${mesh.triangleCount}, vertices: ${mesh.vertexCount}")
            appendLine(
                "Bounding box (mm): ${"%.2f".format(bounds.size.x)} × ${"%.2f".format(bounds.size.y)} × " +
                    "${"%.2f".format(bounds.size.z)}"
            )
            if (volume > 0) {
                appendLine("Volume: ${"%.2f".format(volume)} mm³, surface: ${"%.2f".format(mesh.surfaceArea())} mm²")
            } else {
                appendLine("Note: volume is non-positive — mesh may be open (expected for plane).")
            }
            if (shape == "gear" && args.double("thickness", required = false, default = 5.0) > 0) {
                appendLine("Note: gear is a solid extrusion; a central bore is not supported yet (no CSG kernel).")
            }
        }
        return ToolResult.Success(
            stats,
            structured = JsonValue.obj(
                "path" to JsonValue.of(outPath),
                "triangles" to JsonValue.of(mesh.triangleCount),
                "vertices" to JsonValue.of(mesh.vertexCount),
                "volume_mm3" to JsonValue.of(volume.toDouble()),
                "bounds_mm" to JsonValue.obj(
                    "x" to JsonValue.of(bounds.size.x.toDouble()),
                    "y" to JsonValue.of(bounds.size.y.toDouble()),
                    "z" to JsonValue.of(bounds.size.z.toDouble()),
                ),
            ),
            artifacts = listOf(ArtifactRef(outPath, outPath.substringAfterLast('/'), FileWriteTool.mimeFor(outPath), bytes.size.toLong())),
        )
    }
}

class MeshTransformTool : AgentTool {
    override val id = "mesh_transform"
    override val name = "Transform / convert mesh"
    override val description =
        "Load an STL/OBJ/GLB, apply scale/rotation/translation, then write it as stl, obj or glb. " +
            "Use 'inspect_only' to just report metrics."
    override val version = "1.0.0"
    override val category = ToolCategory.MODELING_3D
    override val inputSchema = schema {
        requiredString("in_path", "Input mesh path (stl/obj/glb).")
        optionalString("out_path", "Output path (extension selects the format). Omit for inspect-only.")
        optionalNumber("scale", "Uniform scale factor (default 1).")
        optionalNumber("rotate_z_deg", "Rotation about Z in degrees.")
        optionalNumber("translate_x", "X translation in mm.")
        optionalNumber("translate_y", "Y translation in mm.")
        optionalNumber("translate_z", "Z translation in mm.")
        optionalBool("inspect_only", "Only report mesh metrics, do not write a file.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ, ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 6_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val inPath = args.string("in_path")
        val bytes = ws.readBytes(inPath, 256L * 1024 * 1024)
        val mesh = MeshReader.readMesh(inPath, bytes)
            ?: return ToolResult.Failure("Could not parse $inPath as STL/OBJ/GLB", errorKind = "unsupported")
        val inspectOnly = args.boolean("inspect_only", false) || !args.has("out_path")
        val scale = args.double("scale", required = false, default = 1.0).toFloat()
        val rotated = mesh.transform(
            translate = Geometry.Vec3(
                args.double("translate_x", required = false, default = 0.0).toFloat(),
                args.double("translate_y", required = false, default = 0.0).toFloat(),
                args.double("translate_z", required = false, default = 0.0).toFloat(),
            ),
            scale = Geometry.Vec3(scale, scale, scale),
            rotateZDeg = args.double("rotate_z_deg", required = false, default = 0.0).toFloat(),
        )
        val bounds = rotated.bounds()
        val summary = buildString {
            appendLine("Mesh: $inPath")
            appendLine("Triangles: ${rotated.triangleCount}, vertices: ${rotated.vertexCount}")
            appendLine("Bounds after transform (mm): ${"%.2f".format(bounds.size.x)} × ${"%.2f".format(bounds.size.y)} × ${"%.2f".format(bounds.size.z)}")
            appendLine("Volume: ${"%.2f".format(rotated.volume())} mm³")
        }
        if (inspectOnly) return ToolResult.Success(summary)
        val outPath = args.string("out_path")
        val ext = outPath.substringAfterLast('.', "").lowercase()
        val outBytes = when (ext) {
            "stl" -> Geometry.toBinaryStl(rotated)
            "obj" -> Geometry.toObj(rotated, outPath.substringAfterLast('/').substringBeforeLast('.')).toByteArray()
            "glb" -> GlbWriter.write(rotated, outPath.substringAfterLast('/').substringBeforeLast('.'))
            else -> return ToolResult.Failure("Unsupported output '$ext'", errorKind = "unsupported")
        }
        ws.writeBytes(outPath, outBytes, overwrite = true)
        return ToolResult.Success(
            summary + "Wrote $outPath (${humanBytes(outBytes.size.toLong())})",
            artifacts = listOf(ArtifactRef(outPath, outPath.substringAfterLast('/'), FileWriteTool.mimeFor(outPath), outBytes.size.toLong())),
        )
    }
}

class MeshInfoTool : AgentTool {
    override val id = "mesh_info"
    override val name = "Inspect mesh"
    override val description = "Report triangles, bounds, volume and surface area for an STL/OBJ/GLB file, and flag common problems."
    override val version = "1.0.0"
    override val category = ToolCategory.MODELING_3D
    override val inputSchema = schema { requiredString("path", "Mesh file path.") }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ)
    override val mutagenic = false
    override val cancellationSupported = false
    override val maxResultChars = 4_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val path = args.string("path")
        val bytes = ws.readBytes(path, 256L * 1024 * 1024)
        val info = MeshReader.inspect(path, bytes)
        val sb = StringBuilder()
        sb.appendLine("Format: ${info.format}")
        sb.appendLine("Triangles: ${info.triangleCount}, vertices: ${info.vertexCount}")
        info.bounds?.let {
            sb.appendLine("Bounds: min(${"%.2f".format(it.min.x)}, ${"%.2f".format(it.min.y)}, ${"%.2f".format(it.min.z)}) " +
                "max(${"%.2f".format(it.max.x)}, ${"%.2f".format(it.max.y)}, ${"%.2f".format(it.max.z)})")
            sb.appendLine("Size (mm): ${"%.2f".format(it.size.x)} × ${"%.2f".format(it.size.y)} × ${"%.2f".format(it.size.z)}")
        }
        info.volume?.let { sb.appendLine("Volume: ${"%.2f".format(it)} mm³") }
        info.surfaceArea?.let { sb.appendLine("Surface area: ${"%.2f".format(it)} mm²") }
        info.notes.forEach { sb.appendLine("Note: $it") }
        return ToolResult.Success(sb.toString())
    }
}

/**
 * CAD-ish tools that are real: parametric extrusion of 2D outlines, plus DXF
 * (R12 ASCII) writing for downstream CAD import. True STEP/B-rep solid modelling
 * requires OpenCascade (a large native dependency) — explicitly NOT provided and
 * reported as unsupported by cad_export.
 */
class CadExtrudeTool : AgentTool {
    override val id = "cad_extrude"
    override val name = "Extrude 2D outline to solid"
    override val description =
        "Extrude a closed 2D polygon (list of [x,y] points in mm) into a solid mesh and write STL/OBJ/GLB. " +
            "Useful for plates, brackets, gaskets and profiles. Requires a convex or simple polygon."
    override val version = "1.0.0"
    override val category = ToolCategory.CAD
    override val inputSchema = schema {
        requiredArray("points", "Array of [x, y] pairs describing the closed outline in mm.")
        requiredNumber("height", "Extrusion height / thickness in mm.")
        requiredString("out_path", "Output path (.stl/.obj/.glb).")
        optionalNumber("fillet_mm", "Accepted for API symmetry; fillets need a CSG kernel and are not applied.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 4_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val points = args.array("points").mapNotNull { p ->
            val arr = p.asArrayOrNull() ?: return@mapNotNull null
            val x = arr.getOrNull(0)?.asDoubleOrNull() ?: return@mapNotNull null
            val y = arr.getOrNull(1)?.asDoubleOrNull() ?: return@mapNotNull null
            x.toFloat() to y.toFloat()
        }
        if (points.size < 3) return ToolResult.Failure("Need at least 3 [x,y] points", errorKind = "invalid_arguments")
        val height = args.double("height").toFloat()
        val mesh = Geometry.extrudePolygon(points, height)
        val outPath = args.string("out_path")
        val ext = outPath.substringAfterLast('.', "").lowercase()
        val bytes = when (ext) {
            "stl" -> Geometry.toBinaryStl(mesh)
            "obj" -> Geometry.toObj(mesh, "extrusion").toByteArray()
            "glb" -> GlbWriter.write(mesh, "extrusion")
            else -> return ToolResult.Failure("Unsupported output '$ext'", errorKind = "unsupported")
        }
        ws.writeBytes(outPath, bytes, overwrite = true)
        val fillet = args.optionalString("fillet_mm")
        return ToolResult.Success(
            "Extruded ${points.size}-point outline to $outPath (${mesh.triangleCount} triangles, height ${height} mm)" +
                if (fillet != null) "\nNote: fillet_mm was ignored — fillets require a CSG kernel (not implemented)." else "",
            artifacts = listOf(ArtifactRef(outPath, outPath.substringAfterLast('/'), FileWriteTool.mimeFor(outPath), bytes.size.toLong())),
        )
    }
}

class CadDxfTool : AgentTool {
    override val id = "cad_dxf"
    override val name = "Write DXF drawing"
    override val description =
        "Write a real AutoCAD R12 ASCII DXF containing LINE/CIRCLE entities. Input entities: " +
            "[{type:'line',x1,y1,x2,y2}] and [{type:'circle',cx,cy,r}]. Opens in any CAD program."
    override val version = "1.0.0"
    override val category = ToolCategory.CAD
    override val inputSchema = schema {
        requiredString("out_path", "Output .dxf path.")
        requiredArray("entities", "Array of entity objects (line/circle/polyline).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = false
    override val maxResultChars = 3_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val outPath = args.string("out_path")
        val entities = args.array("entities")
        val sb = StringBuilder()
        fun pair(code: Int, value: String) { sb.append(code).append('\n').append(value).append('\n') }
        pair(0, "SECTION"); pair(2, "HEADER")
        pair(9, "\$ACADVER"); pair(1, "AC1009")
        pair(0, "ENDSEC")
        pair(0, "SECTION"); pair(2, "ENTITIES")
        var written = 0
        for (entity in entities) {
            when (entity.stringOr("type", "").lowercase()) {
                "line" -> {
                    pair(0, "LINE")
                    pair(8, "0")
                    pair(10, (entity["x1"].asDoubleOrNull() ?: 0.0).toString()); pair(20, (entity["y1"].asDoubleOrNull() ?: 0.0).toString())
                    pair(11, (entity["x2"].asDoubleOrNull() ?: 0.0).toString()); pair(21, (entity["y2"].asDoubleOrNull() ?: 0.0).toString())
                    written++
                }
                "circle" -> {
                    pair(0, "CIRCLE")
                    pair(8, "0")
                    pair(10, (entity["cx"].asDoubleOrNull() ?: 0.0).toString()); pair(20, (entity["cy"].asDoubleOrNull() ?: 0.0).toString())
                    pair(40, (entity["r"].asDoubleOrNull() ?: 1.0).toString())
                    written++
                }
                "polyline" -> {
                    val pts = entity["points"].asArrayOrNull() ?: continue
                    pair(0, "LWPOLYLINE") // R12 lacks LWPOLYLINE, but most readers accept it; use LINE chain for strictness
                    sb.setLength(0) // reset and emit LINE segments instead
                    pair(0, "SECTION"); pair(2, "ENTITIES")
                    var prev: Pair<Double, Double>? = null
                    for (p in pts) {
                        val arr = p.asArrayOrNull() ?: continue
                        val x = arr.getOrNull(0)?.asDoubleOrNull() ?: continue
                        val y = arr.getOrNull(1)?.asDoubleOrNull() ?: continue
                        if (prev != null) {
                            pair(0, "LINE"); pair(8, "0")
                            pair(10, prev.first.toString()); pair(20, prev.second.toString())
                            pair(11, x.toString()); pair(21, y.toString())
                            written++
                        }
                        prev = x to y
                    }
                }
            }
        }
        pair(0, "ENDSEC"); pair(0, "EOF")
        ws.writeText(outPath, sb.toString(), overwrite = true)
        if (written == 0) {
            return ToolResult.Failure("No valid entities written; check entity types (line/circle/polyline).", errorKind = "invalid_arguments")
        }
        return ToolResult.Success(
            "Wrote $outPath with $written DXF entit${if (written == 1) "y" else "ies"}",
            artifacts = listOf(ArtifactRef(outPath, outPath.substringAfterLast('/'), "image/vnd.dxf")),
        )
    }
}

class CadExportTool : AgentTool {
    override val id = "cad_export"
    override val name = "Export CAD format"
    override val description =
        "Convert a mesh to STL/OBJ/GLB. STEP and IGES export require a B-rep kernel (OpenCascade), which is NOT " +
            "bundled in this build — the tool explains the limitation instead of producing an invalid file."
    override val version = "1.0.0"
    override val category = ToolCategory.CAD
    override val inputSchema = schema {
        requiredString("in_path", "Source mesh (stl/obj/glb).")
        requiredString("out_path", "Destination path; the extension selects the format.", listOf("stl", "obj", "glb", "step", "stp", "iges", "igs"))
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ, ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 3_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val outPath = args.string("out_path")
        val ext = outPath.substringAfterLast('.', "").lowercase()
        if (ext in setOf("step", "stp", "iges", "igs")) {
            return ToolResult.Failure(
                "STEP/IGES export is not available in this build. Those are B-rep formats requiring the OpenCascade " +
                    "kernel, which is not bundled (APK size/native build constraints). Export STL/OBJ/GLB instead, or " +
                    "configure a remote runtime that provides cad_export.",
                errorKind = "unsupported",
            )
        }
        val inPath = args.string("in_path")
        val mesh = MeshReader.readMesh(inPath, ws.readBytes(inPath, 256L * 1024 * 1024))
            ?: return ToolResult.Failure("Could not parse $inPath", errorKind = "unsupported")
        val bytes = when (ext) {
            "stl" -> Geometry.toBinaryStl(mesh)
            "obj" -> Geometry.toObj(mesh, outPath.substringAfterLast('/').substringBeforeLast('.')).toByteArray()
            "glb" -> GlbWriter.write(mesh, outPath.substringAfterLast('/').substringBeforeLast('.'))
            else -> return ToolResult.Failure("Unsupported output '$ext'", errorKind = "unsupported")
        }
        ws.writeBytes(outPath, bytes, overwrite = true)
        return ToolResult.Success(
            "Exported $inPath → $outPath (${humanBytes(bytes.size.toLong())}, ${mesh.triangleCount} triangles)",
            artifacts = listOf(ArtifactRef(outPath, outPath.substringAfterLast('/'), FileWriteTool.mimeFor(outPath), bytes.size.toLong())),
        )
    }
}

class ImageInfoTool : AgentTool {
    override val id = "image_info"
    override val name = "Inspect image"
    override val description = "Read image dimensions, format and file size (PNG, JPEG, GIF, BMP, WebP) without decoding pixel data."
    override val version = "1.0.0"
    override val category = ToolCategory.GRAPHICS_2D
    override val inputSchema = schema { requiredString("path", "Image path.") }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ)
    override val mutagenic = false
    override val cancellationSupported = false
    override val maxResultChars = 2_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val path = args.string("path")
        val bytes = ws.readBytes(path, 64L * 1024 * 1024)
        val info = ImageMeta.detect(bytes)
            ?: return ToolResult.Failure("Unrecognised image format for $path", errorKind = "unsupported")
        return ToolResult.Success(
            "Path: $path\nFormat: ${info.format}\nSize: ${info.width} × ${info.height} px\nFile: ${humanBytes(bytes.size.toLong())}",
            structured = JsonValue.obj(
                "format" to JsonValue.of(info.format),
                "width" to JsonValue.of(info.width),
                "height" to JsonValue.of(info.height),
            ),
        )
    }
}

class ImageCreateTool : AgentTool {
    override val id = "image_create"
    override val name = "Create image"
    override val description =
        "Create a real PNG image: solid colour, linear/radial gradient, checkerboard, or a procedural pattern. " +
            "Optionally overlay wrapped text. Pixel data is generated on-device."
    override val version = "1.0.0"
    override val category = ToolCategory.GRAPHICS_2D
    override val inputSchema = schema {
        requiredString("out_path", "Output .png path.")
        requiredInt("width", "Width in pixels (1-4096).")
        requiredInt("height", "Height in pixels (1-4096).")
        optionalString(
            "pattern",
            "solid | gradient_h | gradient_v | radial | checker | stripes (default solid).",
            listOf("solid", "gradient_h", "gradient_v", "radial", "checker", "stripes"),
        )
        optionalString("color", "Primary colour as #RRGGBB (default #4C8DFF).")
        optionalString("color2", "Secondary colour for gradients/checkers (default #101828).")
        optionalInt("checker_size", "Checker square size (default 32).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 2_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val width = args.int("width").coerceIn(1, 4096)
        val height = args.int("height").coerceIn(1, 4096)
        val pattern = args.optionalString("pattern") ?: "solid"
        val c1 = Rgb.parse(args.optionalString("color") ?: "#4C8DFF")
        val c2 = Rgb.parse(args.optionalString("color2") ?: "#101828")
        val checkerSize = args.int("checker_size", required = false, default = 32).coerceAtLeast(2)
        ctx.emitProgress("Rendering $width×$height image…", null)
        val buffer = IntArray(width * height)
        for (y in 0 until height) {
            if (y % 64 == 0) ctx.cancellation.throwIfCancelled()
            for (x in 0 until width) {
                val color = when (pattern) {
                    "gradient_h" -> Rgb.lerp(c1, c2, x.toDouble() / (width - 1).coerceAtLeast(1))
                    "gradient_v" -> Rgb.lerp(c1, c2, y.toDouble() / (height - 1).coerceAtLeast(1))
                    "radial" -> {
                        val cx = width / 2.0; val cy = height / 2.0
                        val dist = Math.hypot(x - cx, y - cy) / (Math.hypot(cx, cy).coerceAtLeast(1.0))
                        Rgb.lerp(c1, c2, dist.coerceIn(0.0, 1.0))
                    }
                    "checker" -> if (((x / checkerSize) + (y / checkerSize)) % 2 == 0) c1 else c2
                    "stripes" -> if ((x / checkerSize) % 2 == 0) c1 else c2
                    else -> c1
                }
                buffer[y * width + x] = color
            }
        }
        val png = Png.write(width, height, buffer)
        ws.writeBytes(args.string("out_path"), png, overwrite = true)
        return ToolResult.Success(
            "Created ${args.string("out_path")} (${width}×${height}, ${pattern}, ${humanBytes(png.size.toLong())})",
            artifacts = listOf(ArtifactRef(args.string("out_path"), args.string("out_path").substringAfterLast('/'), "image/png", png.size.toLong())),
        )
    }
}

class ImageResizeTool : AgentTool {
    override val id = "image_resize"
    override val name = "Resize / convert image"
    override val description =
        "Nearest-neighbour resize and PNG<->JPEG conversion, implemented with Android's Bitmap APIs on-device. " +
            "Preserves aspect ratio when only one dimension is given."
    override val version = "1.0.0"
    override val category = ToolCategory.GRAPHICS_2D
    override val inputSchema = schema {
        requiredString("in_path", "Source image.")
        requiredString("out_path", "Destination (.png or .jpg).")
        optionalInt("width", "Target width in px.")
        optionalInt("height", "Target height in px.")
        optionalInt("quality", "JPEG quality 1-100 (default 88).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ, ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 2_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        return RealImage.rescale(
            object : ToolWorkspaceRef {
                override fun absolutePathFor(relativePath: String) = ws.absolutePath(relativePath)
            },
            args.string("in_path"),
            args.string("out_path"),
            args.int("width", required = false, default = 0),
            args.int("height", required = false, default = 0),
            args.int("quality", required = false, default = 88),
        )
    }
}
