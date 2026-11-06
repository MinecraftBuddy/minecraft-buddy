package adris.altoclef.util.serialization;

import java.util.List;
import net.minecraft.world.phys.Vec3;

public class Vec3dDeserializer extends AbstractVectorDeserializer<Vec3, Double> {
    @Override
    protected String getTypeName() {
        return "Vec3d";
    }

    @Override
    protected String[] getComponents() {
        // the old one only listed x and y here, so a vec3 could never actually load. nobody noticed
        return new String[]{"x", "y", "z"};
    }

    @Override
    protected Double parseUnit(String unit) throws Exception {
        return Double.parseDouble(unit);
    }

    @Override
    protected Vec3 deserializeFromUnits(List<Double> units) {
        return new Vec3(units.get(0), units.get(1), units.get(2));
    }
}
