package com.kocti.crystaldefense.pet.glb

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** Часть модели с одним цветом. Вершины уже разложены по отдельным массивам для шейдера. */
class GlbPrimitive(
    val positions: FloatArray,
    val normals: FloatArray,
    val joints: FloatArray,
    val weights: FloatArray,
    val indices: IntArray,
    /** Цвет в гамме экрана: в файле он хранится линейным, а шейдер работает без преобразования. */
    val color: FloatArray,
)

class GlbNode(val parent: Int, val translation: FloatArray, val rotation: FloatArray, val scale: FloatArray)

enum class ChannelPath { TRANSLATION, ROTATION, SCALE }

class GlbChannel(val node: Int, val path: ChannelPath, val times: FloatArray, val values: FloatArray)

class GlbClip(val name: String, val duration: Float, val channels: List<GlbChannel>)

class GlbModel(
    val nodes: List<GlbNode>,
    /** Порядок обхода, в котором родитель всегда идёт раньше потомков. */
    val nodeOrder: IntArray,
    val jointNodes: IntArray,
    val inverseBind: FloatArray,
    val primitives: List<GlbPrimitive>,
    val clips: Map<String, GlbClip>,
) {
    val triangleCount: Int get() = primitives.sumOf { it.indices.size / 3 }
}

/** Читает только то, что есть в наших моделях: скелет, цветные материалы и линейные анимации. */
object GlbParser {
    private const val GLB_MAGIC = 0x46546C67
    private const val FLOAT = 5126
    private const val UNSIGNED_BYTE = 5121
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val GAMMA = 1f / 2.2f

    fun parse(bytes: ByteArray): GlbModel {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getInt(0) == GLB_MAGIC) { "Not a GLB file" }
        val jsonLength = buffer.getInt(12)
        val json = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))
        // Бинарный блок идёт за JSON: 8 байт заголовка блока, затем данные.
        val reader = AccessorReader(json, buffer, 20 + jsonLength + 8)

        val nodes = parseNodes(json)
        val joints = JointTable(json, reader)
        val primitives = parsePrimitives(json, reader, joints)
        return GlbModel(
            nodes = nodes,
            nodeOrder = traversalOrder(nodes),
            jointNodes = joints.nodes(),
            inverseBind = joints.inverseBind(),
            primitives = primitives,
            clips = parseClips(json, reader),
        )
    }

    /**
     * Предмет в руке (например, трезубец Демона) привязан теми же костями, но с другими матрицами.
     * Шейдер принимает один список костей, поэтому у дополнительных привязок добавляются только реально нужные.
     */
    private class JointTable(json: JSONObject, reader: AccessorReader) {
        private val skinJoints: List<IntArray>
        private val skinMatrices: List<FloatArray>
        private val nodeList = mutableListOf<Int>()
        private val matrixList = mutableListOf<FloatArray>()
        private val remapped = HashMap<Long, Int>()

        init {
            val skins = json.getJSONArray("skins")
            skinJoints = List(skins.length()) { skins.getJSONObject(it).getJSONArray("joints").toIntArray() }
            skinMatrices = List(skins.length()) { reader.floats(skins.getJSONObject(it).getInt("inverseBindMatrices")) }
            skinJoints[0].indices.forEach { index(0, it) }
        }

        fun index(skin: Int, joint: Int): Int = remapped.getOrPut(skin.toLong() shl 32 or joint.toLong()) {
            val node = skinJoints[skin][joint]
            val matrix = skinMatrices[skin].copyOfRange(joint * 16, joint * 16 + 16)
            // Привязки часто повторяют одну и ту же кость: дубликат занял бы лишний слот в шейдере.
            val existing = nodeList.indices.firstOrNull { nodeList[it] == node && matrixList[it].contentEquals(matrix) }
            existing ?: run {
                nodeList += node
                matrixList += matrix
                nodeList.size - 1
            }
        }

        fun nodes() = nodeList.toIntArray()

        fun inverseBind(): FloatArray = FloatArray(matrixList.size * 16) { matrixList[it / 16][it % 16] }
    }

    private fun parseNodes(json: JSONObject): List<GlbNode> {
        val array = json.getJSONArray("nodes")
        val parents = IntArray(array.length()) { -1 }
        for (i in 0 until array.length()) {
            val children = array.getJSONObject(i).optJSONArray("children") ?: continue
            for (c in 0 until children.length()) parents[children.getInt(c)] = i
        }
        return List(array.length()) { i ->
            val node = array.getJSONObject(i)
            require(!node.has("matrix")) { "Node matrices are not supported" }
            GlbNode(
                parent = parents[i],
                translation = node.optJSONArray("translation")?.toFloatArray() ?: floatArrayOf(0f, 0f, 0f),
                rotation = node.optJSONArray("rotation")?.toFloatArray() ?: floatArrayOf(0f, 0f, 0f, 1f),
                scale = node.optJSONArray("scale")?.toFloatArray() ?: floatArrayOf(1f, 1f, 1f),
            )
        }
    }

    private fun traversalOrder(nodes: List<GlbNode>): IntArray {
        val children = List(nodes.size) { mutableListOf<Int>() }
        val order = ArrayList<Int>(nodes.size)
        val stack = ArrayDeque<Int>()
        nodes.forEachIndexed { i, node -> if (node.parent >= 0) children[node.parent] += i else stack.addLast(i) }
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            order += next
            children[next].forEach(stack::addLast)
        }
        return order.toIntArray()
    }

    private fun parsePrimitives(json: JSONObject, reader: AccessorReader, jointTable: JointTable): List<GlbPrimitive> {
        val materials = json.optJSONArray("materials")
        val meshes = json.getJSONArray("meshes")
        val meshSkins = HashMap<Int, Int>()
        val nodes = json.getJSONArray("nodes")
        for (n in 0 until nodes.length()) {
            val node = nodes.getJSONObject(n)
            if (node.has("mesh")) meshSkins[node.getInt("mesh")] = node.getInt("skin")
        }
        val result = mutableListOf<GlbPrimitive>()
        for (m in 0 until meshes.length()) {
            val skin = meshSkins[m] ?: continue
            val primitives = meshes.getJSONObject(m).getJSONArray("primitives")
            for (p in 0 until primitives.length()) {
                val primitive = primitives.getJSONObject(p)
                val attributes = primitive.getJSONObject("attributes")
                val weights = reader.floats(attributes.getInt("WEIGHTS_0"))
                val joints = reader.floats(attributes.getInt("JOINTS_0"))
                for (i in joints.indices) {
                    // Кости с нулевым весом не влияют на вершину, и для них не нужен лишний слот.
                    joints[i] = if (weights[i] > 0f) jointTable.index(skin, joints[i].toInt()).toFloat() else 0f
                }
                result += GlbPrimitive(
                    positions = reader.floats(attributes.getInt("POSITION")),
                    normals = reader.floats(attributes.getInt("NORMAL")),
                    joints = joints,
                    weights = weights,
                    indices = reader.ints(primitive.getInt("indices")),
                    color = primitiveColor(materials, primitive.optInt("material", -1)),
                )
            }
        }
        return result
    }

    private fun primitiveColor(materials: JSONArray?, index: Int): FloatArray {
        val factor = materials?.optJSONObject(index)
            ?.optJSONObject("pbrMetallicRoughness")
            ?.optJSONArray("baseColorFactor")
            ?: return floatArrayOf(1f, 1f, 1f)
        return FloatArray(3) { factor.getDouble(it).toFloat().coerceIn(0f, 1f).pow(GAMMA) }
    }

    private fun parseClips(json: JSONObject, reader: AccessorReader): Map<String, GlbClip> {
        val animations = json.optJSONArray("animations") ?: return emptyMap()
        val clips = LinkedHashMap<String, GlbClip>()
        for (a in 0 until animations.length()) {
            val animation = animations.getJSONObject(a)
            val samplers = animation.getJSONArray("samplers")
            val channels = animation.getJSONArray("channels")
            val parsed = ArrayList<GlbChannel>(channels.length())
            var duration = 0f
            for (c in 0 until channels.length()) {
                val channel = channels.getJSONObject(c)
                val target = channel.getJSONObject("target")
                val path = when (target.getString("path")) {
                    "translation" -> ChannelPath.TRANSLATION
                    "rotation" -> ChannelPath.ROTATION
                    "scale" -> ChannelPath.SCALE
                    else -> continue
                }
                val sampler = samplers.getJSONObject(channel.getInt("sampler"))
                val times = reader.floats(sampler.getInt("input"))
                duration = maxOf(duration, times.last())
                parsed += GlbChannel(target.getInt("node"), path, times, reader.floats(sampler.getInt("output")))
            }
            // Blender пишет имена как «CharacterArmature|Walk», для кода важна только вторая часть.
            val name = animation.getString("name").substringAfterLast('|')
            clips[name] = GlbClip(name, duration, parsed)
        }
        return clips
    }

    private fun JSONArray.toIntArray() = IntArray(length()) { getInt(it) }

    private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }

    private class AccessorReader(json: JSONObject, private val buffer: ByteBuffer, private val binaryStart: Int) {
        private val accessors = json.getJSONArray("accessors")
        private val views = json.getJSONArray("bufferViews")

        fun floats(index: Int): FloatArray {
            val layout = layout(index)
            val normalized = accessors.getJSONObject(index).optBoolean("normalized", false)
            return FloatArray(layout.count * layout.components) { i ->
                val raw = read(layout, i)
                when {
                    layout.type == FLOAT -> buffer.getFloat(offsetOf(layout, i))
                    normalized && layout.type == UNSIGNED_BYTE -> raw / 255f
                    normalized && layout.type == UNSIGNED_SHORT -> raw / 65535f
                    else -> raw.toFloat()
                }
            }
        }

        fun ints(index: Int): IntArray {
            val layout = layout(index)
            return IntArray(layout.count * layout.components) { read(layout, it) }
        }

        private fun read(layout: Layout, i: Int): Int {
            val offset = offsetOf(layout, i)
            return when (layout.type) {
                UNSIGNED_BYTE -> buffer.get(offset).toInt() and 0xFF
                UNSIGNED_SHORT -> buffer.getShort(offset).toInt() and 0xFFFF
                UNSIGNED_INT -> buffer.getInt(offset)
                else -> 0
            }
        }

        private fun offsetOf(layout: Layout, i: Int): Int {
            val element = i / layout.components
            return layout.start + element * layout.stride + (i % layout.components) * layout.componentSize
        }

        private fun layout(index: Int): Layout {
            val accessor = accessors.getJSONObject(index)
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val type = accessor.getInt("componentType")
            val components = when (accessor.getString("type")) {
                "SCALAR" -> 1
                "VEC2" -> 2
                "VEC3" -> 3
                "VEC4" -> 4
                "MAT4" -> 16
                else -> error("Unsupported accessor type")
            }
            val size = when (type) {
                FLOAT, UNSIGNED_INT -> 4
                UNSIGNED_SHORT -> 2
                UNSIGNED_BYTE -> 1
                else -> error("Unsupported component type $type")
            }
            return Layout(
                start = binaryStart + view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0),
                count = accessor.getInt("count"),
                components = components,
                type = type,
                componentSize = size,
                stride = view.optInt("byteStride", components * size),
            )
        }

        private class Layout(
            val start: Int,
            val count: Int,
            val components: Int,
            val type: Int,
            val componentSize: Int,
            val stride: Int,
        )
    }
}
