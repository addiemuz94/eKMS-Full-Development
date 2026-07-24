package com.ekms.terminal.hardware.face

import kotlin.math.sqrt

/**
 * Ported unchanged from `../eKMSHardwareTester`'s `face/FaceTemplateEnrollmentSession.kt` —
 * decision-independent (5-sample averaging into one normalized template applies regardless of
 * which liveness approach gates entry into this session). Deliberately handles only numeric
 * SFace embeddings; camera frames, photos, and video never enter this class.
 */
class FaceTemplateEnrollmentSession(
    private val requiredSamples: Int = DEFAULT_REQUIRED_SAMPLES,
) {
    data class Progress(val capturedSamples: Int, val requiredSamples: Int) {
        val remainingSamples: Int get() = (requiredSamples - capturedSamples).coerceAtLeast(0)
        val isComplete: Boolean get() = capturedSamples >= requiredSamples
    }

    companion object {
        const val DEFAULT_REQUIRED_SAMPLES = 5
    }

    private val normalizedSamples = mutableListOf<FloatArray>()
    private var embeddingDimension: Int? = null

    init {
        require(requiredSamples > 0) { "Required sample count must be positive." }
    }

    @Synchronized
    fun addSample(embedding: FloatArray): Progress {
        check(!isComplete()) { "Enrollment already has $requiredSamples samples." }
        require(embedding.isNotEmpty()) { "Face embedding must not be empty." }

        val expectedDimension = embeddingDimension
        if (expectedDimension == null) {
            embeddingDimension = embedding.size
        } else {
            require(embedding.size == expectedDimension) {
                "Embedding dimensions do not match. Expected $expectedDimension, received ${embedding.size}."
            }
        }

        normalizedSamples += normalize(embedding)
        return progress()
    }

    @Synchronized
    fun progress(): Progress = Progress(capturedSamples = normalizedSamples.size, requiredSamples = requiredSamples)

    @Synchronized
    fun isComplete(): Boolean = normalizedSamples.size >= requiredSamples

    /** Returns an L2-normalized average of all collected samples. Call only after [isComplete]. */
    @Synchronized
    fun buildTemplate(): FloatArray {
        check(isComplete()) { "Need ${progress().remainingSamples} more face sample(s) before saving." }

        val dimension = checkNotNull(embeddingDimension)
        val average = FloatArray(dimension)
        normalizedSamples.forEach { sample -> sample.indices.forEach { index -> average[index] += sample[index] } }
        average.indices.forEach { index -> average[index] /= normalizedSamples.size.toFloat() }
        return normalize(average)
    }

    @Synchronized
    fun reset() {
        normalizedSamples.clear()
        embeddingDimension = null
    }

    private fun normalize(values: FloatArray): FloatArray {
        var sumOfSquares = 0.0
        values.forEach { value -> sumOfSquares += value.toDouble() * value.toDouble() }
        val magnitude = sqrt(sumOfSquares)
        require(magnitude > 0.0) { "Face embedding magnitude must be greater than zero." }
        return FloatArray(values.size) { index -> (values[index] / magnitude).toFloat() }
    }
}
