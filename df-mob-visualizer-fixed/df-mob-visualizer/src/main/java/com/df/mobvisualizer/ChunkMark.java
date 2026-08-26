package com.df.mobvisualizer;

/**
 * One persistent discovery in the chunk history.
 *
 * The mark represents a chunk, not an entity.  It intentionally contains no
 * entity id or live entity reference, so unloading the entity/chunk cannot
 * remove the visual history.
 */
public record ChunkMark(
        int chunkX,
        int chunkZ,
        int color,
        String type,
        long discoveredAt
) {
}