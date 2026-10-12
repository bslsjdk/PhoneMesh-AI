package com.bslsjdk.phonemesh

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Metadata for a layer-aligned model shard stored on one cluster node.
 *
 * This describes a distribution plan; it does not itself split GGUF files or
 * make an inference backend load partial weights. A backend must explicitly
 * support the file layout before this manifest can be used for inference.
 */
data class ModelShardManifest(
    val schemaVersion: Int = 1,
    val modelId: String,
    val modelRevision: String,
    val architecture: String,
    val quantization: String,
    val totalLayers: Int,
    val shards: List<ModelShard>
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (schemaVersion != 1) errors += "Unsupported manifest schema version: $schemaVersion"
        if (modelId.isBlank()) errors += "modelId must not be blank"
        if (modelRevision.isBlank()) errors += "modelRevision must not be blank"
        if (architecture.isBlank()) errors += "architecture must not be blank"
        if (quantization.isBlank()) errors += "quantization must not be blank"
        if (totalLayers <= 0) errors += "totalLayers must be positive"
        if (shards.isEmpty()) errors += "At least one shard is required"

        val duplicateIds = shards.groupBy { it.shardId }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) errors += "Duplicate shard IDs: ${duplicateIds.joinToString()}"
        val duplicateFiles = shards.groupBy { it.fileName }.filterValues { it.size > 1 }.keys
        if (duplicateFiles.isNotEmpty()) errors += "Duplicate shard file names: ${duplicateFiles.joinToString()}"

        val ordered = shards.sortedBy { it.firstLayerInclusive }
        var nextLayer = 0
        for (shard in ordered) {
            if (shard.shardId.isBlank()) errors += "Shard ID must not be blank"
            if (shard.fileName.isBlank() || shard.fileName.contains("/") || shard.fileName.indexOf(92.toChar()) >= 0 ||
                shard.fileName == "." || shard.fileName == "..") {
                errors += "Shard ${shard.shardId}: fileName must be a plain file name"
            }
            if (shard.targetNodeId.isBlank()) errors += "Shard ${shard.shardId}: targetNodeId must not be blank"
            if (shard.firstLayerInclusive < 0 ||
                shard.lastLayerExclusive <= shard.firstLayerInclusive ||
                shard.lastLayerExclusive > totalLayers) {
                errors += "Shard ${shard.shardId}: invalid layer range [${shard.firstLayerInclusive}, ${shard.lastLayerExclusive})"
            }
            if (shard.byteSize <= 0L) errors += "Shard ${shard.shardId}: byteSize must be positive"
            if (!SHA256_PATTERN.matches(shard.sha256)) errors += "Shard ${shard.shardId}: sha256 must be 64 hexadecimal characters"
            if (shard.estimatedPeakRuntimeBytes !in 1L..RuntimeBudget.MAX_BYTES) {
                errors += "Shard ${shard.shardId}: estimated runtime peak must be between 1 byte and 4 GiB"
            }
            if (shard.firstLayerInclusive != nextLayer) {
                errors += if (shard.firstLayerInclusive < nextLayer) {
                    "Layer overlap before shard ${shard.shardId}"
                } else {
                    "Layer gap before shard ${shard.shardId}: expected layer $nextLayer, got ${shard.firstLayerInclusive}"
                }
            }
            nextLayer = maxOf(nextLayer, shard.lastLayerExclusive)
        }
        if (ordered.isNotEmpty() && nextLayer != totalLayers) {
            errors += "Layer coverage ends at $nextLayer, but totalLayers is $totalLayers"
        }
        return errors.distinct()
    }

    fun requireValid() {
        val errors = validate()
        require(errors.isEmpty()) { errors.joinToString(separator = "; ") }
    }

    companion object {
        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
    }
}

data class ModelShard(
    val shardId: String,
    val fileName: String,
    val firstLayerInclusive: Int,
    val lastLayerExclusive: Int,
    val byteSize: Long,
    val sha256: String,
    val targetNodeId: String,
    /** Estimated whole-process peak for this node while this shard is active. */
    val estimatedPeakRuntimeBytes: Long
)

/**
 * File helpers use bounded memory: the checksum is calculated in 1 MiB blocks,
 * never by loading the whole model file into a byte array.
 */
object ModelShardFiles {
    private const val HASH_BUFFER_BYTES = 1024 * 1024

    fun sha256(file: File): String {
        require(file.isFile) { "Not a regular file: ${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun verify(file: File, expectedBytes: Long, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        if (!Regex("^[0-9a-fA-F]{64}$").matches(expectedSha256)) return false
        return sha256(file).equals(expectedSha256, ignoreCase = true)
    }
}
