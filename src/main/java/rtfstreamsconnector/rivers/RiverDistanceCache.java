package rtfstreamsconnector.rivers;

import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import rtfstreamsconnector.util.RTFHelpers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class RiverDistanceCache {
    private static final ConcurrentHashMap<ServerLevel, Cache> CACHES = new ConcurrentHashMap<>();
    private static final int CACHE_MAX_SIZE = 16384;

    private RiverDistanceCache() {}

    public static void putIfAbsent(ServerLevel level, int x, int z, Network network, float distance) {
        Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());
        cache.entries.computeIfAbsent(PosUtil.pack(x, z), (packedPos) -> {
            cache.queue.add(packedPos);
            return new CacheEntry(network, distance);
        });
        evictLRU(cache);
    }

    public static void put(ServerLevel level, int x, int z, Network network, float distance) {
        Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());
        cache.entries.put(PosUtil.pack(x, z), new CacheEntry(network, distance));
        // Reinsert to the queue tail if already exists
        cache.queue.remove(PosUtil.pack(x, z));
        cache.queue.add(PosUtil.pack(x, z));
        evictLRU(cache);
    }

    public static CacheEntry get(ServerLevel level, int x, int z) {
        Cache cache = CACHES.get(level);
        if (cache == null) return null;
        return cache.entries.get(PosUtil.pack(x, z));
    }

    // Releases ServerLevel handle and lazily drops the cache
    public static void untrackLevel(ServerLevel level) {
        CACHES.remove(level);
    }

    private static void evictLRU(Cache cache) {
        while (cache.entries.size() > CACHE_MAX_SIZE) {
            Long evicted = cache.queue.poll();
            if (evicted == null) break;
            cache.entries.remove(evicted);
        }
    }

    // Per-level cache object
    private static final class Cache {
        private final ConcurrentHashMap<Long, CacheEntry> entries = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();
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
