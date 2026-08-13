package rtfstreamsconnector.util;

import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.cell.continent.Continent;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverWarp;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import rtfstreamsconnector.RTFStreamsConnectorMod;
import rtfstreamsconnector.rivers.RiverWarpReflection;
import rtfstreamsconnector.rivers.RivermapReflection;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class RTFHelpers {
    private static final ConcurrentHashMap<ServerLevel, Continent> CONTINENT_PROVIDER_CACHE = new ConcurrentHashMap<>();

    private RTFHelpers() {
    }

    public static SearchDataHandle findNearestRiver(ServerLevel level, int x, int z) {
        // Apply global region coordinate warp for rivers
        Rivermap rivermap = RTFHelpers.getRivermapAt(level, x, z);
        Domain riverWarp = RivermapReflection.riverWarpOf(rivermap);
        float warpedX = x + riverWarp.getOffsetX(x, z, 0);
        float warpedZ = z + riverWarp.getOffsetZ(x, z, 0);

        // Skip river networks which don't capture the query point with their AABB
        // Note: only root rivers have configured AABBs. They enclose the root river and all of its tributaries
        ArrayList<Network> rootNetworks = new ArrayList<>();
        for (Network network : RivermapReflection.networksOf(rivermap)) {
            if (network.contains(warpedX, warpedZ))
                rootNetworks.add(network);
        }

        // Recursively traverse each river network
        SearchDataHandle dataHandle = new SearchDataHandle();
        traverseNetworkTree(dataHandle, rootNetworks.toArray(new Network[0]), warpedX, warpedZ, warpedX, warpedZ);
        return dataHandle;
    }

    private static void traverseNetworkTree(SearchDataHandle dataHandle, Network[] networks, float x,
                                            float z,
                                            float warpedX, float warpedZ) {
        for (Network network : networks) {
            // Push the node onto the stack
            dataHandle.push(network);

            // Warp the coordinates
            River river = network.riverCarver().river;
            RiverWarp warp = network.riverCarver().warp;
            float t = Line.distanceOnLine(warpedX, warpedZ, river.x1, river.z1, river.x2, river.z2);
            long packedOffset = warp.getOffset(warpedX, warpedZ, t, river);
            float offsetX = PosUtil.unpackLeftf(packedOffset);
            float offsetZ = PosUtil.unpackRightf(packedOffset);

            if (warp.test(t)) {
                // Calculate the signed distance to the river's centerline via a dot product
                float w = (warpedX - river.x1 + offsetX) * river.normX + (warpedZ - river.z1 + offsetZ) * river.normZ;

                float t_over = t - NoiseUtil.clamp(t, 0, 1);
                if (w * w + t_over * t_over < dataHandle.bestW * dataHandle.bestW) {
                    dataHandle.bestW = w;
                    dataHandle.saveStackToBest();
                }
            }


            // Recursively traverse the children
            traverseNetworkTree(dataHandle, network.children(), x, z, warpedX + offsetX, warpedZ + offsetZ);
            // Pop the already processed node from the stack
            dataHandle.pop();
        }
    }

    // Compute a hash of unique properties of a river
    public static int riverHashOf(Network network) {
        if (network == null)
            return 0;

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

    // Encapsulates the river tree traversal stack and the bestChain candidate
    public static final class SearchDataHandle {
        private static final int STACK_MAX_SIZE = 4;
        private final Network[] stack = new Network[STACK_MAX_SIZE];
        private int stackHead;
        public final Network[] bestChain = new Network[STACK_MAX_SIZE];
        public int bestChainLength;
        float bestW = Float.MAX_VALUE;

        public void push(Network network) {
            if (stackHead == STACK_MAX_SIZE) {
                RTFStreamsConnectorMod.LOGGER.error("River Chain Stack overflow!");
                return;
            }
            stack[stackHead++] = network;
        }

        public void pop() {
            if (stackHead == 0) {
                RTFStreamsConnectorMod.LOGGER.error("River Chain Stack underflow!");
                return;
            }
            stackHead--;
        }

        public void saveStackToBest() {
            System.arraycopy(stack, 0, bestChain, 0, stackHead);
            bestChainLength = stackHead;
        }

        public Network getBestNetwork() {
            return bestChain[bestChainLength - 1];
        }

    }
}
