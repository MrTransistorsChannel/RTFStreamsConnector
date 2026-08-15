package rtfstreamsconnector.rivers;

import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarver;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverWarp;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
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
    private static final int MAX_CHAIN_DEPTH = 4;

    // Vertex probe radius to cover the whole chunk by 4 probes - half a diagonal (squared)
    private static final float VERTEX_COVER_RADIUS2 = 128.f;

    private ChunkRiverCache() {}

    // Get a list of candidate rivers touching the chunk.
    // Queries the vertex probe cache and deduplicates vertex candidates
    public static CacheEntry getCandidatesForChunk(ServerLevel level, int cx, int cz) {
        Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());
        CacheEntry entry = cache.CHUNK_CACHE.computeIfAbsent(PosUtil.pack(cx, cz), packedChunkPos -> {
            cache.CHUNK_QUEUE.add(packedChunkPos);

            int chunkX = PosUtil.unpackLeft(packedChunkPos);
            int chunkZ = PosUtil.unpackRight(packedChunkPos);

            // HashSet deduplicates automatically via RiverCandidateInfo.equals/hashCode (by riverHash)
            HashSet<RiverCandidateInfo> candidateSet = new HashSet<>();
            // Query this chunk's probe (NW corner) and three adjacent chunks
            for (int vx = chunkX; vx <= chunkX + 1; vx++) {
                for (int vz = chunkZ; vz <= chunkZ + 1; vz++) {
                    int vertexChunkX = vx;
                    int vertexChunkZ = vz;
                    CacheEntry vertexEntry = cache.VERTEX_CACHE.computeIfAbsent(PosUtil.pack(vertexChunkX, vertexChunkZ), packedVertexPos -> {
                        cache.VERTEX_QUEUE.add(packedVertexPos);
                        return findVertexCandidates(level, vertexChunkX, vertexChunkZ);
                    });
                    candidateSet.addAll(vertexEntry.candidates);
                }
            }

            CacheEntry chunkEntry = new CacheEntry();
            chunkEntry.candidates.addAll(candidateSet);
            return chunkEntry;
        });
        evictLRU(cache.CHUNK_CACHE, cache.CHUNK_QUEUE);
        evictLRU(cache.VERTEX_CACHE, cache.VERTEX_QUEUE);
        return entry;
    }

    // Releases ServerLevel handle and lazily drops the cache
    public static void untrackLevel(ServerLevel level) {
        CACHES.remove(level);
    }

    private static CacheEntry findVertexCandidates(ServerLevel level, int cx, int cz) {
        // Get vertex coords from chunk coords
        int vx = cx << 4;
        int vz = cz << 4;
        // Apply the regional domain warp to the query point
        Rivermap rivermap = RTFHelpers.getRivermapAt(level, vx, vz);
        Domain domainWarp = RivermapReflection.riverWarpOf(rivermap);
        float warpedX = vx + domainWarp.getOffsetX(vx, vz, 0);
        float warpedZ = vz + domainWarp.getOffsetZ(vx, vz, 0);

        // Only root rivers have configured AABBs, enclosing the root and all of its tributaries
        ArrayList<Network> rootNetworks = new ArrayList<>();
        for (Network network : RivermapReflection.networksOf(rivermap)) {
            if (network.contains(warpedX, warpedZ)) {
                rootNetworks.add(network);
            }
        }

        // Pre-compute the mouth modifier - it only depends on the world coordinates
        float edge = RTFHelpers.continentOf(level).getEdgeValue(vx, vz);
        float mouthModifier = NoiseUtil.map(edge, 0.0F, 0.5F, 0.5F);
        mouthModifier *= mouthModifier;

        CacheEntry candidates = new CacheEntry();
        findCandidatesRecursively(candidates, domainWarp, rootNetworks.toArray(new Network[0]), mouthModifier, new Network[4], 0, warpedX, warpedZ);
        return candidates;
    }

    // Depth-first walk of the river tree
    // The chain depth is bounded by the river fork tree; exceeding it means the traversal broke
    // or upstream changed its generation (in this case the subtree is skipped)
    private static void findCandidatesRecursively(CacheEntry candidates, Domain domainWarp, Network[] networks, float mouthModifier, Network[] chain,
                                                  int chainLength, float warpedX, float warpedZ) {
        for (Network network : networks) {
            if (chainLength == MAX_CHAIN_DEPTH) {
                RTFStreamsConnectorMod.LOGGER.error("River chain exceeds depth {}", MAX_CHAIN_DEPTH);
                continue;
            }
            chain[chainLength] = network;

            // Fold the entering point through this node's own warp
            RiverCarver carver = network.riverCarver();
            River river = carver.river;
            RiverWarp warp = carver.warp;
            float nodeX = warpedX;
            float nodeZ = warpedZ;
            float t = ((nodeX - river.x1) * river.ndx + (nodeZ - river.z1) * river.ndz) / river.length;
            long packedOffset = warp.getOffset(nodeX, nodeZ, t, river);
            nodeX += PosUtil.unpackLeftf(packedOffset);
            nodeZ += PosUtil.unpackRightf(packedOffset);


            // Warped point in river's coordinate system
            float w = (nodeX - river.x1) * river.normX + (nodeZ - river.z1) * river.normZ;
            float l = (nodeX - river.x1) * river.ndx + (nodeZ - river.z1) * river.ndz;

            float lOver = l - NoiseUtil.clamp(l, 0, river.length);
            // River envelope - warped tube, capped off with half-circles
            float d2 = w * w + lOver * lOver;

            // For rivers inside the chunk 8√2 (half a diagonal) is the minimum distance to have at least 1 vertex catch a river.
            // For rivers outside the chunk, √(8² + halfWidth²) captures any river that is closer than halfWidth to the chunk border.
            // This makes the squared admission distance max(8² + halfWidth², 128)
            float banksWidth2 = Math.min(carver.config.bankWidth * carver.config.bankWidth / mouthModifier, RiverCarverReflection.valleyWidth(carver).max());
            float radius2 = Math.max(64 + banksWidth2, VERTEX_COVER_RADIUS2);
            if (d2 <= radius2) {
                candidates.candidates.add(new RiverCandidateInfo(chain, chainLength + 1, domainWarp));
            }

            // Process the children
            findCandidatesRecursively(candidates, domainWarp, network.children(), mouthModifier, chain, chainLength + 1, nodeX, nodeZ);
        }
    }

    private static void evictLRU(ConcurrentHashMap<Long, ?> entries, ConcurrentLinkedQueue<Long> queue) {
        while (entries.size() > CACHE_MAX_SIZE) {
            Long evicted = queue.poll();
            if (evicted == null) break;
            entries.remove(evicted);
        }
    }

    // Per-level cache object
    private static final class Cache {
        private final ConcurrentHashMap<Long, CacheEntry> VERTEX_CACHE = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> VERTEX_QUEUE = new ConcurrentLinkedQueue<>();
        private final ConcurrentHashMap<Long, CacheEntry> CHUNK_CACHE = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> CHUNK_QUEUE = new ConcurrentLinkedQueue<>();
    }

    // Cache entry object
    public static final class CacheEntry {
        public ArrayList<RiverCandidateInfo> candidates = new ArrayList<>();
    }

    // Immutable snapshot of a candidate river: the chain of Networks for per-block refolding,
    // the domain warp of its rivermap, and the geometric hash used for deduplication
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
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            return this.riverHash == ((RiverCandidateInfo) o).riverHash;
        }
    }
}