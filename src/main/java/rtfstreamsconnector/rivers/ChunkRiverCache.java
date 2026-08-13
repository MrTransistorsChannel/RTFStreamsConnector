package rtfstreamsconnector.rivers;

import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverWarp;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import rtfstreamsconnector.RTFStreamsConnectorMod;
import rtfstreamsconnector.util.RTFHelpers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChunkRiverCache {
    private static final ConcurrentHashMap<ServerLevel, Cache> CACHES = new ConcurrentHashMap<>();
    private static final int CACHE_MAX_SIZE = 4096;

    // Distance from a chunk corner to the farthest block of its 16x16 chunk: half a diagonal
    private static final float VERTEX_COVER_RADIUS = (float) (8.0 * Math.sqrt(2.0));
    // Margin for the warp field's triangle-inequality violation between the vertex and chunk blocks
    private static final float WARP_SLACK = 8.0F;

    // Deduplicated candidate rivers of a chunk: the 4 corner vertex probes merged by geometric river hash.
    // Only the chunk's own NW vertex is probed here; the other 3 corners are owned by neighboring
    // chunks, so each vertex is traversed exactly once and shared by the 4 chunks around it.
    public static CacheEntry getCandidatesFor(ServerLevel level, int cx, int cz) {
        Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());
        return cache.CHUNK_CACHE.computeIfAbsent(PosUtil.pack(cx, cz), packedChunkPos -> {
            cache.CHUNK_QUEUE.add(packedChunkPos);

            int chunkX = PosUtil.unpackLeft(packedChunkPos);
            int chunkZ = PosUtil.unpackRight(packedChunkPos);

            // HashSet deduplicates automatically via RiverCandidateInfo.equals/hashCode (by riverHash)
            HashSet<RiverCandidateInfo> candidateSet = new HashSet<>();

            for (int vx = chunkX; vx <= chunkX + 1; vx++) {
                for (int vz = chunkZ; vz <= chunkZ + 1; vz++) {
                    int vertexChunkX = vx;
                    int vertexChunkZ = vz;
                    CacheEntry vertexEntry = cache.VERTEX_CACHE.computeIfAbsent(
                        PosUtil.pack(vertexChunkX, vertexChunkZ),
                        packedVertexPos -> {
                            cache.VERTEX_QUEUE.add(packedVertexPos);
                            // The vertex probe position is the NW corner block of its owning chunk
                            return findRiverCandidates(level, vertexChunkX << 4, vertexChunkZ << 4);
                        });
                    candidateSet.addAll(vertexEntry.candidates);
                }
            }

            // Evict old cache entries
            evictLRU(cache.VERTEX_CACHE, cache.VERTEX_QUEUE);
            evictLRU(cache.CHUNK_CACHE, cache.CHUNK_QUEUE);

            CacheEntry chunkEntry = new CacheEntry();
            chunkEntry.candidates.addAll(candidateSet);
            return chunkEntry;
        });
    }

    // Probes a single vertex (block position (x, z)): folds it through each river chain
    // and admits every river whose band envelope reaches the vertex
    private static CacheEntry findRiverCandidates(ServerLevel level, int x, int z) {
        // Apply the regional domain warp to the query point
        Rivermap rivermap = RTFHelpers.getRivermapAt(level, x, z);
        Domain domainWarp = RivermapReflection.riverWarpOf(rivermap);
        float warpedX = x + domainWarp.getOffsetX(x, z, 0);
        float warpedZ = z + domainWarp.getOffsetZ(x, z, 0);

        // Only root rivers have configured AABBs, enclosing the root and all of its tributaries
        ArrayList<Network> rootNetworks = new ArrayList<>();
        for (Network network : RivermapReflection.networksOf(rivermap)) {
            if (network.contains(warpedX, warpedZ)) {
                rootNetworks.add(network);
            }
        }

        CacheEntry candidates = new CacheEntry();
        findCandidatesRecursively(new Network[4], 0, candidates, domainWarp,
            rootNetworks.toArray(new Network[0]), warpedX, warpedZ);
        return candidates;
    }

    // Depth-first walk of the river tree; 'chain' holds the current ancestor path (root first).
    // The chain depth is bounded by the river fork tree; exceeding it means the traversal broke
    // or upstream changed its generation, so the affected subtree is skipped rather than
    // cached with a truncated chain.
    private static final int MAX_CHAIN_DEPTH = 4;

    private static void findCandidatesRecursively(Network[] chain, int chainLength, CacheEntry candidates,
                                                  Domain domainWarp, Network[] networks,
                                                  float warpedX, float warpedZ) {
        for (Network network : networks) {
            if (chainLength == MAX_CHAIN_DEPTH) {
                RTFStreamsConnectorMod.LOGGER.error("River chain exceeds depth {}", MAX_CHAIN_DEPTH);
                continue;
            }
            chain[chainLength] = network;

            // Fold the entering point through this node's own warp
            River river = network.riverCarver().river;
            RiverWarp warp = network.riverCarver().warp;
            float t = Line.distanceOnLine(warpedX, warpedZ, river.x1, river.z1, river.x2, river.z2);
            long packedOffset = warp.getOffset(warpedX, warpedZ, t, river);
            float offsetX = PosUtil.unpackLeftf(packedOffset);
            float offsetZ = PosUtil.unpackRightf(packedOffset);

            if (warp != RiverWarp.NONE) {
                // Signed lateral distance to the axis, and clamped distance to the segment
                float w = (warpedX - river.x1 + offsetX) * river.normX
                    + (warpedZ - river.z1 + offsetZ) * river.normZ;
                float tOver = t - NoiseUtil.clamp(t, 0, 1);
                float d2 = w * w + tOver * tOver * river.length2;

                // Admission envelope: (half bank width + vertex cover 8√2 + warp slack)².
                // Far-axis rivers self-eliminate by the clamped distance term.
                float radius = network.riverCarver().config.bankWidth
                    + VERTEX_COVER_RADIUS + WARP_SLACK;
                if (d2 <= radius * radius) {
                    // The chain includes the admitted river itself (ancestors + current node)
                    candidates.candidates.add(new RiverCandidateInfo(chain, chainLength + 1, domainWarp));
                }
            }

            // Descend into children with the (ancestor + own) folded point
            findCandidatesRecursively(chain, chainLength + 1, candidates, domainWarp,
                network.children(), warpedX + offsetX, warpedZ + offsetZ);
        }
    }

    // Releases the level handle and drops the cache on level unload
    public static void untrackLevel(ServerLevel level) {
        CACHES.remove(level);
    }

    private static void evictLRU(ConcurrentHashMap<Long, ?> entries, ConcurrentLinkedQueue<Long> queue) {
        while (entries.size() > CACHE_MAX_SIZE) {
            Long evicted = queue.poll();
            if (evicted == null) {
                break;
            }
            entries.remove(evicted);
        }
    }

    private static final class Cache {
        private final ConcurrentHashMap<Long, CacheEntry> VERTEX_CACHE = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> VERTEX_QUEUE = new ConcurrentLinkedQueue<>();
        private final ConcurrentHashMap<Long, CacheEntry> CHUNK_CACHE = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> CHUNK_QUEUE = new ConcurrentLinkedQueue<>();
    }

    public static final class CacheEntry {
        public ArrayList<RiverCandidateInfo> candidates = new ArrayList<>();
    }

    // Immutable snapshot of a candidate river: the chain of Networks for per-block refolding,
    // the domain warp of its rivermap, and the geometric hash used for dedup/validation
    public static final class RiverCandidateInfo {
        public Network[] riverChain = new Network[MAX_CHAIN_DEPTH];
        public int riverChainLength;
        public Domain domainWarp;
        public int riverHash;

        public RiverCandidateInfo(Network[] riverChain, int chainLength, Domain domainWarp) {
            System.arraycopy(riverChain, 0, this.riverChain, 0, chainLength);
            this.riverChainLength = chainLength;
            this.domainWarp = domainWarp;
            this.riverHash = RTFHelpers.riverHashOf(riverChain[chainLength - 1]);
        }

        @Override
        public int hashCode() {
            return this.riverHash;
        }

        // Two candidates with the same riverHash reference the same river
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            RiverCandidateInfo that = (RiverCandidateInfo) o;
            return this.riverHash == that.riverHash;
        }
    }
}