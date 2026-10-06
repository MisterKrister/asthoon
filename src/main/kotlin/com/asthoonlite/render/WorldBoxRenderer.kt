package com.asthoonlite.render

import com.asthoonlite.AsthoonLite
import com.asthoonlite.QuietMode
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.MeshData
import com.mojang.blaze3d.vertex.VertexFormat
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MappableRingBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import org.joml.Matrix4fc
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import java.util.Optional
import java.util.OptionalDouble
import java.util.OptionalInt
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Central world-space box renderer.
 *
 * This is the same custom-RenderPipeline technique EtherwarpOverlay was
 * already using (see its class doc — built against Fabric's 26.1.2
 * "Rendering in the World" docs), pulled out into one shared object so
 * every feature that wants a highlighted block/entity box (etherwarp
 * target, star-mob ESP, the higher/lower blaze order) queues into the same
 * buffer and pipeline instead of each standing up its own GPU buffer/ring
 * buffer/render pass. Queue boxes during LevelRenderEvents.END_EXTRACTION
 * (call queueFilled/queueOutline from your own END_EXTRACTION handler,
 * registered AFTER `WorldBoxRenderer.register()` runs so the frame's queue
 * has already been cleared), then this draws everything queued in one pass.
 */
object WorldBoxRenderer {

    data class Box(
        val x1: Double, val y1: Double, val z1: Double,
        val x2: Double, val y2: Double, val z2: Double,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val throughWalls: Boolean = false
    )

    data class Quad(
        val x1: Double, val y1: Double, val z1: Double,
        val x2: Double, val y2: Double, val z2: Double,
        val x3: Double, val y3: Double, val z3: Double,
        val x4: Double, val y4: Double, val z4: Double,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val throughWalls: Boolean = false
    )

    // Filled quads to draw this frame. Outline boxes are expanded into a
    // set of thin filled "slab" quads along each edge (12 per box) rather
    // than needing a separate LINES-mode pipeline — cheap, and renders at
    // any zoom without needing GL line-width support.
    private val filledQueue = ArrayList<Box>()
    private val quadQueue = ArrayList<Quad>()

    private val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "pipeline/world_box"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .build()
    )

    // NoammAddons uses the same approach for its phase/through-wall ESP:
    // removing the depth-stencil state makes the highlight render even when
    // the mob is behind dungeon blocks.
    private val THROUGH_WALLS_PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(AsthoonLite.MOD_ID, "pipeline/world_box_through_walls"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(Optional.empty())
            .build()
    )

    private val ALLOCATOR = ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE)
    private val COLOR_MODULATOR = Vector4f(1f, 1f, 1f, 1f)
    private val MODEL_OFFSET = Vector3f()
    private val TEXTURE_MATRIX = org.joml.Matrix4f()
    private var buffer: BufferBuilder? = null
    private var vertexBuffer: MappableRingBuffer? = null

    fun register() {
        LevelRenderEvents.END_EXTRACTION.register {
            filledQueue.clear()
            quadQueue.clear()
        }
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(::renderAndDraw)
    }

    /** Call from MixinGameRenderer#close (see EtherwarpOverlay's original note). */
    fun close() {
        ALLOCATOR.close()
        vertexBuffer?.close()
        vertexBuffer = null
    }

    fun queueFilled(
        x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float, throughWalls: Boolean = false
    ) {
        if (QuietMode.suppressing()) return
        filledQueue.add(Box(x1, y1, z1, x2, y2, z2, r, g, b, a, throughWalls))
    }

    /** Thin filled slabs along all 12 edges of the box — a cheap "outline". */
    fun queueOutline(
        x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float, thickness: Double = 0.02,
        throughWalls: Boolean = false
    ) {
        if (QuietMode.suppressing()) return
        val t = thickness
        // 4 verticals
        edge(x1, y1, z1, x1 + t, y2, z1 + t, r, g, b, a, throughWalls)
        edge(x2 - t, y1, z1, x2, y2, z1 + t, r, g, b, a, throughWalls)
        edge(x1, y1, z2 - t, x1 + t, y2, z2, r, g, b, a, throughWalls)
        edge(x2 - t, y1, z2 - t, x2, y2, z2, r, g, b, a, throughWalls)
        // 4 bottom edges
        edge(x1, y1, z1, x2, y1 + t, z1 + t, r, g, b, a, throughWalls)
        edge(x1, y1, z2 - t, x2, y1 + t, z2, r, g, b, a, throughWalls)
        edge(x1, y1, z1, x1 + t, y1 + t, z2, r, g, b, a, throughWalls)
        edge(x2 - t, y1, z1, x2, y1 + t, z2, r, g, b, a, throughWalls)
        // 4 top edges
        edge(x1, y2 - t, z1, x2, y2, z1 + t, r, g, b, a, throughWalls)
        edge(x1, y2 - t, z2 - t, x2, y2, z2, r, g, b, a, throughWalls)
        edge(x1, y2 - t, z1, x1 + t, y2, z2, r, g, b, a, throughWalls)
        edge(x2 - t, y2 - t, z1, x2, y2, z2, r, g, b, a, throughWalls)
    }

    fun queueLine(
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float,
        thickness: Double = 0.04,
        throughWalls: Boolean = false
    ) {
        if (QuietMode.suppressing()) return
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-5) return

        val t = thickness / 2.0
        val (ux, uy, uz) = if (abs(dx) < abs(dy) && abs(dx) < abs(dz)) {
            val uLen = sqrt(dy * dy + dz * dz).coerceAtLeast(1e-5)
            Triple(0.0, -dz / uLen * t, dy / uLen * t)
        } else {
            val uLen = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-5)
            Triple(-dz / uLen * t, 0.0, dx / uLen * t)
        }

        val cx = dy * uz - dz * uy
        val cy = dz * ux - dx * uz
        val cz = dx * uy - dy * ux
        val cLen = sqrt(cx * cx + cy * cy + cz * cz).coerceAtLeast(1e-5)
        val vx = cx / cLen * t
        val vy = cy / cLen * t
        val vz = cz / cLen * t

        val p1_0 = Triple(x1 + ux + vx, y1 + uy + vy, z1 + uz + vz)
        val p1_1 = Triple(x1 - ux + vx, y1 - uy + vy, z1 - uz + vz)
        val p1_2 = Triple(x1 - ux - vx, y1 - uy - vy, z1 - uz - vz)
        val p1_3 = Triple(x1 + ux - vx, y1 + uy - vy, z1 + uz - vz)

        val p2_0 = Triple(x2 + ux + vx, y2 + uy + vy, z2 + uz + vz)
        val p2_1 = Triple(x2 - ux + vx, y2 - uy + vy, z2 - uz + vz)
        val p2_2 = Triple(x2 - ux - vx, y2 - uy - vy, z2 - uz - vz)
        val p2_3 = Triple(x2 + ux - vx, y2 + uy - vy, z2 + uz - vz)

        quadQueue.add(Quad(p1_0.first, p1_0.second, p1_0.third, p1_1.first, p1_1.second, p1_1.third, p2_1.first, p2_1.second, p2_1.third, p2_0.first, p2_0.second, p2_0.third, r, g, b, a, throughWalls))
        quadQueue.add(Quad(p1_1.first, p1_1.second, p1_1.third, p1_2.first, p1_2.second, p1_2.third, p2_2.first, p2_2.second, p2_2.third, p2_1.first, p2_1.second, p2_1.third, r, g, b, a, throughWalls))
        quadQueue.add(Quad(p1_2.first, p1_2.second, p1_2.third, p1_3.first, p1_3.second, p1_3.third, p2_3.first, p2_3.second, p2_3.third, p2_2.first, p2_2.second, p2_2.third, r, g, b, a, throughWalls))
        quadQueue.add(Quad(p1_3.first, p1_3.second, p1_3.third, p1_0.first, p1_0.second, p1_0.third, p2_0.first, p2_0.second, p2_0.third, p2_3.first, p2_3.second, p2_3.third, r, g, b, a, throughWalls))
    }

    private fun edge(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, r: Float, g: Float, b: Float, a: Float, throughWalls: Boolean) {
        filledQueue.add(Box(x1, y1, z1, x2, y2, z2, r, g, b, a, throughWalls))
    }

    private fun renderAndDraw(context: LevelRenderContext) {
        // Second gate: quiet mode can flip between the queue pass and here,
        // and a frame queued before it flipped must not be drawn.
        if (QuietMode.suppressing()) return
        if (filledQueue.isEmpty() && quadQueue.isEmpty()) return

        renderBoxes(context, throughWalls = false)
        drawBuffer(Minecraft.getInstance(), PIPELINE)

        renderBoxes(context, throughWalls = true)
        drawBuffer(Minecraft.getInstance(), THROUGH_WALLS_PIPELINE)
    }

    private fun renderBoxes(context: LevelRenderContext, throughWalls: Boolean) {
        val matrices = context.poseStack()
        val camera = context.levelState().cameraRenderState.pos
        val boxes = filledQueue.asSequence().filter { it.throughWalls == throughWalls }.toList()
        val quads = quadQueue.asSequence().filter { it.throughWalls == throughWalls }.toList()
        if (boxes.isEmpty() && quads.isEmpty()) return

        matrices.pushPose()
        matrices.translate(-camera.x, -camera.y, -camera.z)

        if (buffer == null) {
            buffer = BufferBuilder(ALLOCATOR, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        }
        val pose = matrices.last().pose()
        for (box in boxes) {
            addFilledBox(pose, buffer!!, box)
        }
        for (quad in quads) {
            addQuad(pose, buffer!!, quad)
        }

        matrices.popPose()
    }

    private fun addQuad(
        pose: org.joml.Matrix4fc,
        builder: BufferBuilder,
        quad: Quad
    ) {
        builder.addVertex(pose, quad.x1.toFloat(), quad.y1.toFloat(), quad.z1.toFloat()).setColor(quad.r, quad.g, quad.b, quad.a)
        builder.addVertex(pose, quad.x2.toFloat(), quad.y2.toFloat(), quad.z2.toFloat()).setColor(quad.r, quad.g, quad.b, quad.a)
        builder.addVertex(pose, quad.x3.toFloat(), quad.y3.toFloat(), quad.z3.toFloat()).setColor(quad.r, quad.g, quad.b, quad.a)
        builder.addVertex(pose, quad.x4.toFloat(), quad.y4.toFloat(), quad.z4.toFloat()).setColor(quad.r, quad.g, quad.b, quad.a)
    }

    private fun addFilledBox(
        pose: org.joml.Matrix4fc,
        builder: BufferBuilder,
        box: Box
    ) {
        val x1 = box.x1.toFloat(); val y1 = box.y1.toFloat(); val z1 = box.z1.toFloat()
        val x2 = box.x2.toFloat(); val y2 = box.y2.toFloat(); val z2 = box.z2.toFloat()
        val r = box.r; val g = box.g; val b = box.b; val a = box.a

        fun v(x: Float, y: Float, z: Float) = builder.addVertex(pose, x, y, z).setColor(r, g, b, a)

        // Bottom
        v(x1, y1, z1); v(x2, y1, z1); v(x2, y1, z2); v(x1, y1, z2)
        // Top
        v(x1, y2, z2); v(x2, y2, z2); v(x2, y2, z1); v(x1, y2, z1)
        // North
        v(x1, y1, z1); v(x1, y2, z1); v(x2, y2, z1); v(x2, y1, z1)
        // South
        v(x2, y1, z2); v(x2, y2, z2); v(x1, y2, z2); v(x1, y1, z2)
        // West
        v(x1, y1, z2); v(x1, y2, z2); v(x1, y2, z1); v(x1, y1, z1)
        // East
        v(x2, y1, z1); v(x2, y2, z1); v(x2, y2, z2); v(x2, y1, z2)
    }

    private fun drawBuffer(client: Minecraft, pipeline: RenderPipeline) {
        val built = buffer?.buildOrThrow() ?: return
        buffer = null
        val drawParameters = built.drawState()
        val format = drawParameters.format()

        val vertices = upload(drawParameters, format, built)
        draw(client, built, drawParameters, vertices, format, pipeline)

        vertexBuffer?.rotate()
    }

    private fun upload(drawParameters: MeshData.DrawState, format: VertexFormat, built: MeshData): GpuBuffer {
        val vertexBufferSize = drawParameters.vertexCount() * format.vertexSize

        if (vertexBuffer == null || vertexBuffer!!.size() < vertexBufferSize) {
            vertexBuffer?.close()
            vertexBuffer = MappableRingBuffer(
                { "${AsthoonLite.MOD_ID} world box render pipeline" },
                GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_MAP_WRITE,
                vertexBufferSize
            )
        }

        val commandEncoder: CommandEncoder = RenderSystem.getDevice().createCommandEncoder()
        commandEncoder.mapBuffer(
            vertexBuffer!!.currentBuffer().slice(0, built.vertexBuffer().remaining().toLong()), false, true
        ).use { mapped -> MemoryUtil.memCopy(built.vertexBuffer(), mapped.data()) }

        return vertexBuffer!!.currentBuffer()
    }

    private fun draw(
        client: Minecraft, built: MeshData, drawParameters: MeshData.DrawState,
        vertices: GpuBuffer, format: VertexFormat, pipeline: RenderPipeline
    ) {
        val indices: GpuBuffer
        val indexType: VertexFormat.IndexType

        if (pipeline.vertexFormatMode == VertexFormat.Mode.QUADS) {
            built.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting())
            indices = pipeline.vertexFormat.uploadImmediateIndexBuffer(built.indexBuffer()!!)
            indexType = built.drawState().indexType()
        } else {
            val shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.vertexFormatMode)
            indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount())
            indexType = shapeIndexBuffer.type()
        }

        val dynamicTransforms: GpuBufferSlice = RenderSystem.getDynamicUniforms()
            .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX)

        RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            { "${AsthoonLite.MOD_ID} world box render pass" },
            client.mainRenderTarget.colorTextureView!!, OptionalInt.empty(),
            client.mainRenderTarget.depthTextureView!!, OptionalDouble.empty()
        ).use { renderPass: RenderPass ->
            renderPass.setPipeline(pipeline)
            RenderSystem.bindDefaultUniforms(renderPass)
            renderPass.setUniform("DynamicTransforms", dynamicTransforms)
            renderPass.setVertexBuffer(0, vertices)
            renderPass.setIndexBuffer(indices, indexType)
            renderPass.drawIndexed(0, 0, drawParameters.indexCount(), 1)
        }

        built.close()
    }
}
