package rtfstreamsconnector.rivers;

import net.minecraft.server.level.ServerLevel;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import rtfstreamsconnector.util.RTFHelpers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class RiverDistanceCache {
    public int riverHash;
    public float distance;

    private static final int CACHE_MAX_SIZE = 16384;
    private static final ConcurrentHashMap<ServerLevel, Cache> CACHES = new ConcurrentHashMap<>();

    // Per-level cache object
    private static final class Cache {
        private final ConcurrentHashMap<Long, RiverDistanceCache> entries = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();
    }

    // Non-static objects are only used as entries in the cache
    private RiverDistanceCache(Network network, float distance) {
        this.riverHash = RTFHelpers.riverHashOf(network);
        this.distance = distance;
    }

    public static void putIfAbsent(ServerLevel level, int x, int z, Network network, float distance) {
        Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());

        cache.entries.computeIfAbsent(PosUtil.pack(x, z), (packedPos) -> {
            cache.queue.add(packedPos);
            return new RiverDistanceCache(network, distance);
        });
        evictLRU(cache);
    }

    public static void put(ServerLevel level, int x, int z, Network network, float distance) {
        Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());
        cache.entries.put(PosUtil.pack(x, z), new RiverDistanceCache(network, distance));
        // Reinsert to the queue tail if already exists
        cache.queue.remove(PosUtil.pack(x, z));
        cache.queue.add(PosUtil.pack(x, z));
        evictLRU(cache);
    }

    public static RiverDistanceCache get(ServerLevel level, int x, int z) {
        Cache cache = CACHES.get(level);
        if (cache == null)
            return null;
        return cache.entries.get(PosUtil.pack(x, z));
    }

    // Releases ServerLevel handle and lazily drops the cache
    public static void untrackLevel(ServerLevel level) {
        CACHES.remove(level);
    }

    private static void evictLRU(Cache cache) {
        while (cache.entries.size() > CACHE_MAX_SIZE) {
            Long evicted = cache.queue.poll();
            if (evicted == null)
                break;
            cache.entries.remove(evicted);
        }
    }
}
