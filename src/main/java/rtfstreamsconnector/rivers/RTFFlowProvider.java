package rtfstreamsconnector.rivers;

import dev.streamsreflowing.core.river.RiverFlowField;
import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverWarp;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import rtfstreamsconnector.util.RTFHelpers;

public class RTFFlowProvider {


    private RTFFlowProvider() {
    }

    public static double flowRadAt(RiverFlowField.BiomeClass biomeClass, int x, int z) {
        if (!(biomeClass instanceof BiomeClassWrapper wrapper))
            return Double.NaN;

        ServerLevel level = wrapper.level;

        return flowRadAt(level, x, z);
    }

    // Calculate the flow of an RTF river for a specified block in a specified level
    public static double flowRadAt(ServerLevel level, int x, int z) {
        // Get or build the river candidate list for this chunk
        ChunkRiverCache.CacheEntry cacheEntry = ChunkRiverCache.getCandidatesFor(level, x >> 4, z >> 4);
        if (cacheEntry == null)
            return Double.NaN;

        // Pick the candidate river whose bank band reaches this block, closest by clamped distance
        ChunkRiverCache.RiverCandidateInfo winner = null;
        float bestW = 0;
        float bestD2 = Float.MAX_VALUE;
        for (ChunkRiverCache.RiverCandidateInfo info : cacheEntry.candidates) {
            Fold fold = foldChain(info, x, z);
            Network leaf = info.riverChain[info.riverChainLength - 1];
            River leafRiver = leaf.riverCarver().river;

            float w = (fold.x - leafRiver.x1) * leafRiver.normX + (fold.z - leafRiver.z1) * leafRiver.normZ;
            float tOver = fold.t - NoiseUtil.clamp(fold.t, 0, 1);
            float d2 = w * w + tOver * tOver * leafRiver.length2;

            // Admission gate: the bank band of the river must actually reach this block
            float band = RiverCarverReflection.scaledSizeOf(leaf.riverCarver(), fold.t,
                RiverCarverReflection.banksWidth(leaf.riverCarver()));
            if (d2 <= band && d2 < bestD2) {
                winner = info;
                bestW = w;
                bestD2 = d2;
            }
        }

        // If no river band covers this block, skip it
        if (winner == null) {
            return Double.NaN;
        }

        Network bestNetwork = winner.riverChain[winner.riverChainLength - 1];

        // Cache the current block's distance and river
        RiverDistanceCache.put(level, x, z, bestNetwork, bestW);

        // Calculate the partial derivatives from the neighbors
        float gx = (getDistanceForCandidate(level, x + 1, z, winner) -
            getDistanceForCandidate(level, x - 1, z, winner)) / 2.f;
        float gz = (getDistanceForCandidate(level, x, z + 1, winner) -
            getDistanceForCandidate(level, x, z - 1, winner)) / 2.f;

        float mag = NoiseUtil.sqrt(gx * gx + gz * gz);
        if (mag < 1e-5f) {
            River river = bestNetwork.riverCarver().river;
            return Math.atan2(river.ndz, river.ndx);
        }

        return Math.atan2(gx, -gz);
    }

    // Signed distance from the winner's centerline at (x,z), reused from the cache when the
    // cached value belongs to the same river; otherwise recomputed by folding the candidate chain.
    private static float getDistanceForCandidate(ServerLevel level, int x, int z,
                                                 ChunkRiverCache.RiverCandidateInfo info) {
        Network leaf = info.riverChain[info.riverChainLength - 1];
        RiverDistanceCache cached = RiverDistanceCache.get(level, x, z);
        if (cached != null && cached.riverHash == RTFHelpers.riverHashOf(leaf)) {
            return cached.distance;
        }

        // Fold the candidate's chain: its rivermap domain warp first, then the chain of warps
        Fold fold = foldChain(info, x, z);

        River bestRiver = leaf.riverCarver().river;
        float w = (fold.x - bestRiver.x1) * bestRiver.normX + (fold.z - bestRiver.z1) * bestRiver.normZ;
        // Cache the value if the block is not cached. Do not overwrite the already existing entries.
        RiverDistanceCache.putIfAbsent(level, x, z, leaf, w);

        return w;
    }

    // Folds the query point through the candidate's domain warp and river chain;
    // returns the folded point and the parameter t of the leaf river
    private static Fold foldChain(ChunkRiverCache.RiverCandidateInfo info, float x, float z) {
        float warpedX = x + info.domainWarp.getOffsetX(x, z, 0);
        float warpedZ = z + info.domainWarp.getOffsetZ(x, z, 0);
        float t = 0;
        for (int i = 0; i < info.riverChainLength; i++) {
            Network network = info.riverChain[i];
            River river = network.riverCarver().river;
            RiverWarp warp = network.riverCarver().warp;
            t = Line.distanceOnLine(warpedX, warpedZ, river.x1, river.z1, river.x2, river.z2);
            long packedOffset = warp.getOffset(warpedX, warpedZ, t, river);
            warpedX += PosUtil.unpackLeftf(packedOffset);
            warpedZ += PosUtil.unpackRightf(packedOffset);
        }
        return new Fold(warpedX, warpedZ, t);
    }

    private record Fold(float x, float z, float t) {
    }

    // Wrap the biome classifier
    public static RiverFlowField.BiomeClass wrapClassifier(ServerLevel level,
                                                           RiverFlowField.BiomeClass biomeClass) {
        return new BiomeClassWrapper(level, biomeClass);
    }

    // Custom biome classifier and a link between the level and its biome classifier
    private record BiomeClassWrapper(ServerLevel level,
                                     RiverFlowField.BiomeClass delegate) implements RiverFlowField.BiomeClass {
        @Override
        public int classify(int x, int z) {
            return delegate.classify(x, z);
        }
    }
}
