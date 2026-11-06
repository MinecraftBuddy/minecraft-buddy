package adris.altoclef.util.serialization;

import java.util.List;
import net.minecraft.world.level.ChunkPos;

public class ChunkPosDeserializer extends AbstractVectorDeserializer<ChunkPos, Integer> {
    @Override
    protected String getTypeName() {
        return "ChunkPos";
    }

    @Override
    protected String[] getComponents() {
        return new String[]{"x", "z"};
    }

    @Override
    protected Integer parseUnit(String unit) throws Exception {
        return Integer.parseInt(unit);
    }

    @Override
    protected ChunkPos deserializeFromUnits(List<Integer> units) {
        return new ChunkPos(units.get(0), units.get(1));
    }
}
