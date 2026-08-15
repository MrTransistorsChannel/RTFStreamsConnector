package rtfstreamsconnector.rivers;

import dev.streamsreflowing.core.river.RiverFlowField;
import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarver;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverWarp;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import rtfstreamsconnector.util.RTFHelpers;

public class RTFFlowProvider {

    private RTFFlowProvider() {}

    // The actual flow provider for the Streams mixin
    public static double flowRadAt(RiverFlowField.BiomeClass biomeClass, int x, int z) {
        if (!(biomeClass instanceof BiomeClassWrapper wrapper)) return Double.NaN;

        ServerLevel level = wrapper.level;
        return flowRadAt(level, x, z);
    }

    // Calculate the flow of an RTF river for a specified block in a specified level
    public static double flowRadAt(ServerLevel level, int x, int z) {
        // Get or build the river candidate list for this chunk
        ChunkRiverCache.CacheEntry cacheEntry = ChunkRiverCache.getCandidatesForChunk(level, x >> 4, z >> 4);
        if (cacheEntry == null) return Double.NaN;

        // Pre-compute the mouth modifier - it only depends on the world coordinates
        float edge = RTFHelpers.continentOf(level).getEdgeValue(x, z);
        float mouthModifier = NoiseUtil.map(edge, 0.0F, 0.5F, 0.5F);
        mouthModifier *= mouthModifier;

        // Find the closest (by clamped squared distance) river of this chunk's candidates
        ChunkRiverCache.RiverCandidateInfo winner = null;
        float bestW = 0;
        float bestD2 = Float.MAX_VALUE;
        for (ChunkRiverCache.RiverCandidateInfo info : cacheEntry.candidates) {
            long packedXZ = foldWarpChain(info, x, z);
            float foldedX = PosUtil.unpackLeftf(packedXZ);
            float foldedZ = PosUtil.unpackRightf(packedXZ);
            Network network = info.riverChain[info.riverChainLength - 1];
            RiverCarver carver = network.riverCarver();
            River river = carver.river;

            // Warped point in river's coordinate system
            float w = (foldedX - river.x1) * river.normX + (foldedZ - river.z1) * river.normZ;
            float l = (foldedX - river.x1) * river.ndx + (foldedZ - river.z1) * river.ndz;
            float lOver = l - NoiseUtil.clamp(l, 0, river.length);
            float d2 = w * w + lOver * lOver;

            // Admission gate: the bank band of the river must actually reach this block
            // because chunk candidates are selected by the maximum river width
            float scaledBW = RiverCarverReflection.scaledSizeOf(carver, l / river.length, RiverCarverReflection.banksWidth(network.riverCarver()));
            float banksWidth2 = Math.min(scaledBW / mouthModifier, RiverCarverReflection.valleyWidth(carver).max());
            if (d2 <= banksWidth2 && d2 < bestD2) {
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
        float gx = (getDistanceToCandidate(level, x + 1, z, winner) - getDistanceToCandidate(level, x - 1, z, winner)) / 2.f;
        float gz = (getDistanceToCandidate(level, x, z + 1, winner) - getDistanceToCandidate(level, x, z - 1, winner)) / 2.f;

        // Fall back to the axis direction if the gradient is zero
        // TODO: test if this can actually happen, shouldn't be possible since the field is monotonic (maybe under extreme warp it is still possible)
        float mag = NoiseUtil.sqrt(gx * gx + gz * gz);
        if (mag < 1e-5f) {
            River river = bestNetwork.riverCarver().river;
            return Math.atan2(river.ndz, river.ndx);
        }

        return Math.atan2(gx, -gz);
    }

    // Wrap the biome classifier to link it to a ServerLevel
    public static RiverFlowField.BiomeClass wrapClassifier(ServerLevel level, RiverFlowField.BiomeClass biomeClass) {
        return new BiomeClassWrapper(level, biomeClass);
    }

    // Signed distance from the winner's centerline at (x,z), reused from the cache when the
    // cached value belongs to the same river, otherwise recomputed by folding the candidate chain.
    private static float getDistanceToCandidate(ServerLevel level, int x, int z, ChunkRiverCache.RiverCandidateInfo info) {
        Network network = info.riverChain[info.riverChainLength - 1];
        RiverDistanceCache.CacheEntry cached = RiverDistanceCache.get(level, x, z);
        if (cached != null && cached.riverHash == RTFHelpers.riverHashOf(network)) {
            return cached.distance;
        }

        // Fold the candidate's warp chain
        long packedXZ = foldWarpChain(info, x, z);
        float foldedX = PosUtil.unpackLeftf(packedXZ);
        float foldedZ = PosUtil.unpackRightf(packedXZ);

        River bestRiver = network.riverCarver().river;
        float w = (foldedX - bestRiver.x1) * bestRiver.normX + (foldedZ - bestRiver.z1) * bestRiver.normZ;
        // Cache the value if the block is not cached. Do not overwrite the already existing entries.
        RiverDistanceCache.putIfAbsent(level, x, z, network, w);

        return w;
    }

    // Folds the query point through the candidate's domain warp and river chain warps. Returns packed float(x,z)
    private static long foldWarpChain(ChunkRiverCache.RiverCandidateInfo info, float x, float z) {
        float warpedX = x + info.domainWarp.getOffsetX(x, z, 0);
        float warpedZ = z + info.domainWarp.getOffsetZ(x, z, 0);
        for (int i = 0; i < info.riverChainLength; i++) {
            Network network = info.riverChain[i];
            River river = network.riverCarver().river;
            RiverWarp warp = network.riverCarver().warp;
            float t = Line.distanceOnLine(warpedX, warpedZ, river.x1, river.z1, river.x2, river.z2);
            // Like RTF's Network.carve: only apply the offset while the projection lands inside the axis (t in [0,1])
            if (warp.test(t)) {
                long packedOffset = warp.getOffset(warpedX, warpedZ, t, river);
                warpedX += PosUtil.unpackLeftf(packedOffset);
                warpedZ += PosUtil.unpackRightf(packedOffset);
            }
        }
        return PosUtil.packf(warpedX, warpedZ);
    }

    // Wrapper for linking a level to its biome classifier. Can be used to modify the classifier
    private record BiomeClassWrapper(ServerLevel level, RiverFlowField.BiomeClass delegate) implements RiverFlowField.BiomeClass {
        @Override
        public int classify(int x, int z) {
            return delegate.classify(x, z);
        }
    }
}
