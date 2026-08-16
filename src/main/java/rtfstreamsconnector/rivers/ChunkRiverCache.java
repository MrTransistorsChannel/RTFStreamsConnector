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
import java.util.concurrent.atomic.AtomicLong;

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
        // Stats: the flag is set inside computeIfAbsent only when the entry was missing
        boolean[] chunkMissed = new boolean[1];
        CacheEntry entry = cache.CHUNK_CACHE.computeIfAbsent(PosUtil.pack(cx, cz), packedChunkPos -> {
            chunkMissed[0] = true;
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
                    boolean[] vertexMissed = new boolean[1];
                    CacheEntry vertexEntry = cache.VERTEX_CACHE.computeIfAbsent(PosUtil.pack(vertexChunkX, vertexChunkZ), packedVertexPos -> {
                        vertexMissed[0] = true;
                        cache.VERTEX_QUEUE.add(packedVertexPos);
                        return findVertexCandidates(level, vertexChunkX, vertexChunkZ);
                    });
                    if (vertexMissed[0]) cache.vertexMisses.incrementAndGet(); else cache.vertexHits.incrementAndGet();
                    candidateSet.addAll(vertexEntry.candidates);
                }
            }

            CacheEntry chunkEntry = new CacheEntry();
            chunkEntry.candidates.addAll(candidateSet);
            return chunkEntry;
        });
        if (chunkMissed[0]) cache.chunkMisses.incrementAndGet(); else cache.chunkHits.incrementAndGet();
        evictLRU(cache.CHUNK_CACHE, cache.CHUNK_QUEUE, cache.chunkEvictions);
        evictLRU(cache.VERTEX_CACHE, cache.VERTEX_QUEUE, cache.vertexEvictions);
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

    private static void evictLRU(ConcurrentHashMap<Long, ?> entries, ConcurrentLinkedQueue<Long> queue, AtomicLong evictedCounter) {
        while (entries.size() > CACHE_MAX_SIZE) {
            Long evicted = queue.poll();
            if (evicted == null) break;
            if (entries.remove(evicted) != null) evictedCounter.incrementAndGet();
        }
    }

    // Snapshot of the per-level cache counters and sizes (for /rtfconnector cachestats)
    public static final class CacheStats {
        public final long vertexSize, vertexQueued, vertexCandidates, vertexHits, vertexMisses, vertexEvictions;
        public final long chunkSize, chunkQueued, chunkCandidates, chunkHits, chunkMisses, chunkEvictions;

        private CacheStats(Cache cache) {
            this.vertexSize = cache.VERTEX_CACHE.size();
            this.vertexQueued = cache.VERTEX_QUEUE.size();
            this.vertexHits = cache.vertexHits.get();
            this.vertexMisses = cache.vertexMisses.get();
            this.vertexEvictions = cache.vertexEvictions.get();
            long vc = 0;
            for (CacheEntry e : cache.VERTEX_CACHE.values()) vc += e.candidates.size();
            this.vertexCandidates = vc;

            this.chunkSize = cache.CHUNK_CACHE.size();
            this.chunkQueued = cache.CHUNK_QUEUE.size();
            this.chunkHits = cache.chunkHits.get();
            this.chunkMisses = cache.chunkMisses.get();
            this.chunkEvictions = cache.chunkEvictions.get();
            long cc = 0;
            for (CacheEntry e : cache.CHUNK_CACHE.values()) cc += e.candidates.size();
            this.chunkCandidates = cc;
        }
    }

    public static CacheStats getCacheStats(ServerLevel level) {
        Cache cache = CACHES.get(level);
        if (cache == null) return null;
        return new CacheStats(cache);
    }

    public static void resetCacheStats(ServerLevel level) {
        Cache cache = CACHES.get(level);
        if (cache == null) return;
        cache.vertexHits.set(0);
        cache.vertexMisses.set(0);
        cache.vertexEvictions.set(0);
        cache.chunkHits.set(0);
        cache.chunkMisses.set(0);
        cache.chunkEvictions.set(0);
    }

    public static int trackedLevels() {
        return CACHES.size();
    }

    // Per-level cache object
    private static final class Cache {
        private final ConcurrentHashMap<Long, CacheEntry> VERTEX_CACHE = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> VERTEX_QUEUE = new ConcurrentLinkedQueue<>();
        private final ConcurrentHashMap<Long, CacheEntry> CHUNK_CACHE = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> CHUNK_QUEUE = new ConcurrentLinkedQueue<>();

        // Stats counters for /rtfconnector cachestats
        final AtomicLong vertexHits = new AtomicLong();
        final AtomicLong vertexMisses = new AtomicLong();
        final AtomicLong vertexEvictions = new AtomicLong();
        final AtomicLong chunkHits = new AtomicLong();
        final AtomicLong chunkMisses = new AtomicLong();
        final AtomicLong chunkEvictions = new AtomicLong();
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