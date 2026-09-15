// FILE: app/src/main/java/me/grey/picquery/common/Algorithm.kt
package me.grey.picquery.common

import kotlin.math.sqrt

fun calculateSimilarity(vectorA: FloatArray, vectorB: FloatArray): Double {
    var dotProduct = 0.0f
    var normA = 0.0f
    var normB = 0.0f

    for (i in vectorA.indices) {
        val a = vectorA[i]
        val b = vectorB[i]
        dotProduct += a * b
        normA += a * a
        normB += b * b
    }

    val denominator = sqrt(normA) * sqrt(normB)
    return (if (denominator != 0.0f) dotProduct / denominator else 0.0f).toDouble()
}

/**
 * Fast dot-product. Equivalent to cosine similarity when both inputs are
 * L2-normalized (which is how new embeddings are stored and how query
 * features are prepped before search).
 */
fun dotProduct(vectorA: FloatArray, vectorB: FloatArray): Double {
    var dot = 0.0f
    for (i in vectorA.indices) {
        dot += vectorA[i] * vectorB[i]
    }
    return dot.toDouble()
}

/**
 * In-place-ish L2 normalization. Returns the same array if already unit-norm.
 */
fun l2Normalize(vector: FloatArray): FloatArray {
    var sum = 0.0
    for (v in vector) sum += v.toDouble() * v.toDouble()
    val norm = sqrt(sum).toFloat()
    if (norm == 0f || norm == 1f) return vector
    for (i in vector.indices) vector[i] = vector[i] / norm
    return vector
}