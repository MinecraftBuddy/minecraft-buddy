package adris.altoclef.eventbus.events;

import net.minecraft.world.level.ChunkPos;

public class ChunkLoadEvent {
    // Soprano's chunk event only knows the position, and the position is all anybody here ever read off the chunk
    public ChunkPos chunkPos;

    public ChunkLoadEvent(ChunkPos chunkPos) {
        this.chunkPos = chunkPos;
    }
}
