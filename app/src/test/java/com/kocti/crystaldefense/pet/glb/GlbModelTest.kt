package com.kocti.crystaldefense.pet.glb

import com.kocti.crystaldefense.pet.Species
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class GlbModelTest {
    private fun load(asset: String) = GlbParser.parse(File("src/main/assets/$asset").readBytes())

    private val modelForms = Species.entries.flatMap { it.forms }.map { it.model }

    @Test
    fun everyFormModelLoadsAndHasItsClips() {
        assertTrue(modelForms.isNotEmpty())
        for (source in modelForms) {
            val model = load(source.asset)
            for (clip in listOf(source.idleClip, source.walkClip, source.celebrateClip, source.attackClip, source.hitClip, source.deathClip)) {
                assertTrue("${source.asset}: $clip of ${model.clips.keys}", clip in model.clips)
            }
            assertTrue(source.asset, model.jointNodes.size <= 50)
            assertTrue(source.asset, model.triangleCount in 1..10_000)
        }
    }

    @Test
    fun verticesReferenceExistingJointsAndWeightsSumToOne() {
        for (source in modelForms) {
            val model = load(source.asset)
            for (primitive in model.primitives) {
                val vertices = primitive.positions.size / 3
                assertEquals(vertices * 4, primitive.joints.size)
                for (v in 0 until vertices) {
                    var sum = 0f
                    for (k in 0..3) {
                        sum += primitive.weights[v * 4 + k]
                        assertTrue(primitive.joints[v * 4 + k].toInt() < model.jointNodes.size)
                    }
                    assertEquals(source.asset, 1f, sum, 0.01f)
                }
                assertTrue(primitive.indices.all { it in 0 until vertices })
            }
        }
    }

    @Test
    fun colorsAreConvertedToScreenGamma() {
        val model = load(modelForms.first().asset)
        // Линейные цвета в файле тёмные: без преобразования дракон был бы почти чёрным.
        assertTrue(model.primitives.first().color.max() > 0.5f)
    }

    @Test
    fun animationActuallyMovesTheSkeleton() {
        for (source in modelForms) {
            val animator = SkeletonAnimator(load(source.asset))
            animator.play(source.walkClip)
            animator.update(0f)
            val first = animator.jointMatrices.copyOf()
            animator.update(animator.clip!!.duration * 0.4f)
            assertTrue(source.asset, first.indices.any { abs(first[it] - animator.jointMatrices[it]) > 1e-3f })
            assertFalse(source.asset, animator.jointMatrices.any { it.isNaN() })
        }
    }

    @Test
    fun boundsAreFiniteAndHaveHeight() {
        for (source in modelForms) {
            val animator = SkeletonAnimator(load(source.asset))
            animator.play(source.idleClip)
            animator.update(0f)
            val bounds = animator.computeBounds()
            assertTrue(source.asset, bounds[4] - bounds[1] > 0f)
            assertTrue(source.asset, bounds.all { it.isFinite() })
        }
    }

    @Test
    fun clipLoopsInsteadOfRunningPastItsEnd() {
        val source = modelForms.first()
        val animator = SkeletonAnimator(load(source.asset))
        animator.play(source.idleClip)
        val duration = animator.clip!!.duration
        animator.update(duration * 0.25f)
        val quarter = animator.jointMatrices.copyOf()
        animator.update(duration)
        for (i in quarter.indices) assertEquals(quarter[i], animator.jointMatrices[i], 1e-2f)
    }

    @Test
    fun oneShotClipHoldsItsLastPose() {
        for (source in modelForms) {
            val animator = SkeletonAnimator(load(source.asset))
            animator.play(source.deathClip, loop = false)
            val duration = animator.clip!!.duration
            animator.update(duration * 0.5f)
            assertFalse(source.asset, animator.finished)
            animator.update(duration)
            assertTrue(source.asset, animator.finished)
            val held = animator.jointMatrices.copyOf()
            animator.update(duration)
            for (i in held.indices) assertEquals(source.asset, held[i], animator.jointMatrices[i], 1e-4f)
        }
    }

    @Test
    fun restartPlaysTheSameClipFromTheBeginning() {
        val source = modelForms.first()
        val animator = SkeletonAnimator(load(source.asset))
        animator.play(source.attackClip, loop = false)
        animator.update(animator.clip!!.duration * 2f)
        assertTrue(animator.finished)
        animator.play(source.attackClip, loop = false, restart = true)
        assertFalse(animator.finished)
    }

    @Test
    fun switchingClipsBlendsSmoothly() {
        val source = modelForms.first()
        val animator = SkeletonAnimator(load(source.asset))
        animator.play(source.idleClip)
        animator.update(0.3f)
        val before = animator.jointMatrices.copyOf()
        animator.play(source.walkClip)
        animator.update(0.001f)
        // Через миллисекунду после переключения поза почти не изменилась.
        for (i in before.indices) assertEquals(before[i], animator.jointMatrices[i], 2f)
    }

    @Test
    fun rejectsFileThatIsNotGlb() {
        val failed = runCatching { GlbParser.parse(ByteArray(32)) }.isFailure
        assertTrue(failed)
    }
}
