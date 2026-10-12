# Model shard manifest (v1)

PhoneMesh uses a manifest to describe **already-created, layer-aligned shard files**. The manifest is metadata, not a shard converter and not an inference engine. A valid manifest does not prove that a particular GGUF model can be split or loaded by the selected backend.

## Runtime memory rule

Every node must remain at or below 4 GiB measured process/runtime peak. Do not add the phones' RAM together and treat it as one address space. Each node's estimate must include the model layers resident on that node, KV cache for the planned context and concurrency, compute/work buffers, native runtime overhead, and app overhead. Weight file size alone is not a runtime estimate.

The Kotlin validator in `ModelShardManifest.kt` rejects:
- unsupported schema versions and missing model identity fields;
- duplicate shard IDs or file names;
- invalid layer ranges, overlaps, gaps, and incomplete layer coverage;
- invalid byte lengths or SHA-256 strings;
- a per-shard/node runtime peak estimate outside 1 byte through 4 GiB.

## Suggested JSON shape

The current Kotlin data classes define the canonical field names. A serialized manifest can follow this shape:

```json
{
  "schemaVersion": 1,
  "modelId": "publisher/model-name",
  "modelRevision": "immutable-revision-or-file-hash",
  "architecture": "model-architecture",
  "quantization": "Q4_K_M",
  "totalLayers": 32,
  "shards": [
    {
      "shardId": "gtneo6-layers-00-19",
      "fileName": "model-layers-00-19.gguf",
      "firstLayerInclusive": 0,
      "lastLayerExclusive": 20,
      "byteSize": 1234567890,
      "sha256": "replace-with-the-real-64-character-sha256",
      "targetNodeId": "gtneo6",
      "estimatedPeakRuntimeBytes": 3000000000
    },
    {
      "shardId": "x50-layers-20-31",
      "fileName": "model-layers-20-31.gguf",
      "firstLayerInclusive": 20,
      "lastLayerExclusive": 32,
      "byteSize": 987654321,
      "sha256": "replace-with-the-real-64-character-sha256",
      "targetNodeId": "x50",
      "estimatedPeakRuntimeBytes": 3000000000
    }
  ]
}
```

The numbers above are illustrative only, not measured sizes or a valid ready-to-load 9B model. Replace every file size, digest, layer boundary, and memory estimate with values produced by the actual model conversion and device measurements.

## Safe file verification

`ModelShardFiles.sha256(file)` reads a file incrementally with a 1 MiB buffer. It does not load the entire model into RAM. `ModelShardFiles.verify(...)` checks both exact byte length and SHA-256.

For transfer, write incoming bytes to a temporary file using a bounded buffer, verify length and digest, and only then atomically rename the file to its final name. A future resumable protocol must authenticate the peer, bind the transfer to a manifest/shard ID, and verify the final complete shard. Do not expose an unauthenticated inference or file-transfer port to other networks.

## Still required before real inference

1. Choose and pin a native backend revision that actually supports Android and layer/model-file partitioning.
2. Verify whether the backend accepts separate layer files or requires a backend-specific tensor/weight conversion. Do not split a GGUF file at arbitrary byte offsets and assume it is a valid layer shard.
3. Generate shards with the backend's supported converter, if available.
4. Measure process peak memory and tokens/s on each physical phone, including the intended context length.
5. Test a single phone first, then two-node layer pipeline. Layer pipeline can improve throughput under concurrent requests but does not guarantee lower single-token latency.
