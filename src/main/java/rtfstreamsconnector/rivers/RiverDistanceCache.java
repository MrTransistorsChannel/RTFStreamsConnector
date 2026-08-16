package rtfstreamsconnector.rivers;

import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import rtfstreamsconnector.util.RTFHelpers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class RiverDistanceCache {
    private static final ConcurrentHashMap<ServerLevel, Cache> CACHES = new ConcurrentHashMap<>();
    private static final int CACHE_MAX_SIZE = 16384;

    private RiverDistanceCache() {}

    public static void putIfAbsent(ServerLevel level, int x, int z, Network network, float distance) {
        Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());
        cache.entries.computeIfAbsent(PosUtil.pack(x, z), (packedPos) -> {
            cache.puts.incrementAndGet();
            cache.queue.add(packedPos);
            return new CacheEntry(network, distance);
        });
        evictLRU(cache);
    }

    public static void put(ServerLevel level, int x, int z, Network network, float distance) {
        Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());
        cache.entries.put(PosUtil.pack(x, z), new CacheEntry(network, distance));
        cache.puts.incrementAndGet();
        // Reinsert to the queue tail if already exists
        cache.queue.remove(PosUtil.pack(x, z));
        cache.queue.add(PosUtil.pack(x, z));
        evictLRU(cache);
    }

    // Returns the cached entry only when it belongs to the queried river (riverHash match).
    // Counts a hit only in that case; on mismatch the caller must recompute the fold.
    public static CacheEntry get(ServerLevel level, int x, int z, int riverHash) {
        Cache cache = CACHES.get(level);
        if (cache == null) return null;
        CacheEntry entry = cache.entries.get(PosUtil.pack(x, z));
        cache.gets.incrementAndGet();
        if (entry != null && entry.riverHash == riverHash) {
            cache.hits.incrementAndGet();
            return entry;
        }
        cache.misses.incrementAndGet();
        return null;
    }

    // Releases ServerLevel handle and lazily drops the cache
    public static void untrackLevel(ServerLevel level) {
        CACHES.remove(level);
    }

    private static void evictLRU(Cache cache) {
        while (cache.entries.size() > CACHE_MAX_SIZE) {
            Long evicted = cache.queue.poll();
            if (evicted == null) break;
            if (cache.entries.remove(evicted) != null) cache.evictions.incrementAndGet();
        }
    }

    // Snapshot of the per-level cache counters (for /rtfconnector cachestats)
    public static final class CacheStats {
        public final long size, queued, gets, hits, misses, puts, evictions;

        private CacheStats(Cache cache) {
            this.size = cache.entries.size();
            this.queued = cache.queue.size();
            this.gets = cache.gets.get();
            this.hits = cache.hits.get();
            this.misses = cache.misses.get();
            this.puts = cache.puts.get();
            this.evictions = cache.evictions.get();
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
        cache.gets.set(0);
        cache.hits.set(0);
        cache.misses.set(0);
        cache.puts.set(0);
        cache.evictions.set(0);
    }

    public static int trackedLevels() {
        return CACHES.size();
    }

    // Per-level cache object
    private static final class Cache {
        private final ConcurrentHashMap<Long, CacheEntry> entries = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();

        // Stats counters for /rtfconnector cachestats
        final AtomicLong gets = new AtomicLong();
        final AtomicLong hits = new AtomicLong();
        final AtomicLong misses = new AtomicLong();
        final AtomicLong puts = new AtomicLong();
        final AtomicLong evictions = new AtomicLong();
    }

    // Cache entry object
    public static final class CacheEntry {
        public int riverHash;
        public float distance;

        public CacheEntry(Network network, float distance) {
            this.riverHash = RTFHelpers.riverHashOf(network);
            this.distance = distance;
        }
    }
}
