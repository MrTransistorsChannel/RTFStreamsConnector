package rtfstreamsconnector.util;

import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverWarp;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import rtfstreamsconnector.rivers.RiverWarpReflection;

import java.util.concurrent.ConcurrentHashMap;

public final class RTFHelpers {
    private static final ConcurrentHashMap<ServerLevel, Continent> CONTINENT_PROVIDER_CACHE = new ConcurrentHashMap<>();

    private RTFHelpers() {}

    // Compute a hash of river's unique properties
    public static int riverHashOf(Network network) {
        if (network == null) return 0;

        River river = network.riverCarver().river;
        RiverWarp warp = network.riverCarver().warp;

        int hash = RiverWarpReflection.seedOf(warp);
        hash = hash * 31 + Float.floatToRawIntBits(river.x1);
        hash = hash * 31 + Float.floatToRawIntBits(river.z1);
        hash = hash * 31 + Float.floatToRawIntBits(river.x2);
        hash = hash * 31 + Float.floatToRawIntBits(river.z2);
        hash ^= (hash >>> 16);
        hash *= 0x85ebca6b;
        return hash;
    }

    // Get the generator context of a world
    // Since the connector requires RTF to be installed, all RandomState instances
    // are promoted to RTFRandomState by a mixin, so we can just cast to RandomState RTFRandomState
    public static GeneratorContext generatorContextOf(ServerLevel level) {
        return ((RTFRandomState) (Object) level.getChunkSource().randomState()).generatorContext();
    }

    // Get the rivermap in the region containing (x,z)
    public static Rivermap getRivermapAt(ServerLevel level, int x, int z) {
        Continent continent = continentOf(level);
        long continentCenter = continent.getNearestCenter(x, z);
        return continent.getRivermap(PosUtil.unpackLeft(continentCenter), PosUtil.unpackRight(continentCenter));
    }

    // Cache the continent provider for cheap rivermap access.
    public static Continent continentOf(ServerLevel level) {
        return CONTINENT_PROVIDER_CACHE.computeIfAbsent(level, l -> {
            GeneratorContext ctx = RTFHelpers.generatorContextOf(l);
            return ctx.generator.getHeightmap().continent();
        });
    }

    // Remove continent provider for a specified level from the cache
    public static void dropContinent(ServerLevel level) {
        CONTINENT_PROVIDER_CACHE.remove(level);
    }
}
