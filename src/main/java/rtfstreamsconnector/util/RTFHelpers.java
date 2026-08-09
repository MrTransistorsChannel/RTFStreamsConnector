package rtfstreamsconnector.util;

import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;

public final class RTFHelpers {
    private RTFHelpers() {}

    // Get the generator context of a world. Since the connector requires RTF to be installed, all RandomState instances
    // are promoted to RTFRandomState by a mixin, so we can just cast to RandomState RTFRandomState
    public static GeneratorContext generatorContextOf(ServerLevel level) {
        //Object randomState = level.getChunkSource().randomState();
        return ((RTFRandomState) (Object) level.getChunkSource().randomState()).generatorContext();
    }
}
