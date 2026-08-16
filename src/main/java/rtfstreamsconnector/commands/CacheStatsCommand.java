package rtfstreamsconnector.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import rtfstreamsconnector.rivers.ChunkRiverCache;
import rtfstreamsconnector.rivers.RiverDistanceCache;

// Debug command for the three cache layers of the river flow pipeline.
// Usage:
//   /rtfconnector cachestats       - stats of vertex / chunk / distance caches of the current level
//   /rtfconnector cachestats reset - zero the counters (element counts are not affected)
public final class CacheStatsCommand {

    // Rough per-element byte estimates for the memory line (rationale in DEV-NOTES.md)
    private static final long MAP_ENTRY_BYTES = 96;        // CHM node + queue node + object headers
    private static final long CANDIDATE_BYTES = 128;       // RiverCandidateInfo + riverChain array
    private static final long DISTANCE_ENTRY_BYTES = 96;   // distance CacheEntry + CHM node + queue node

    private CacheStatsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtfconnector").requires(source -> source.hasPermission(2))
            .then(Commands.literal("cachestats")
                .executes(CacheStatsCommand::executeStats)
                .then(Commands.literal("reset")
                    .executes(CacheStatsCommand::executeReset))));
    }

    private static int executeStats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> stats(source.getLevel()), false);
        return 1;
    }

    private static int executeReset(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ChunkRiverCache.resetCacheStats(source.getLevel());
        RiverDistanceCache.resetCacheStats(source.getLevel());
        source.sendSuccess(() -> Component.literal("Cache stats counters reset.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static MutableComponent stats(ServerLevel level) {
        MutableComponent msg = Component.literal("---- Cache stats ----\n").withStyle(ChatFormatting.GREEN);

        ChunkRiverCache.CacheStats chunk = ChunkRiverCache.getCacheStats(level);
        RiverDistanceCache.CacheStats dist = RiverDistanceCache.getCacheStats(level);
        if (chunk == null && dist == null) {
            msg.append(Component.literal("No caches are tracked for this level yet.\n").withStyle(ChatFormatting.RED));
            return msg;
        }

        if (chunk != null) {
            appendCacheSection(msg, "Vertex cache", chunk.vertexSize, chunk.vertexQueued, chunk.vertexCandidates,
                chunk.vertexHits + chunk.vertexMisses, chunk.vertexHits, chunk.vertexMisses, chunk.vertexEvictions,
                chunk.vertexSize * MAP_ENTRY_BYTES + chunk.vertexCandidates * CANDIDATE_BYTES);
            appendCacheSection(msg, "Chunk cache", chunk.chunkSize, chunk.chunkQueued, chunk.chunkCandidates,
                chunk.chunkHits + chunk.chunkMisses, chunk.chunkHits, chunk.chunkMisses, chunk.chunkEvictions,
                chunk.chunkSize * MAP_ENTRY_BYTES + chunk.chunkCandidates * CANDIDATE_BYTES);
        }
        if (dist != null) {
            appendCacheSection(msg, "Distance cache", dist.size, dist.queued, 0,
                dist.gets, dist.hits, dist.misses, dist.evictions,
                dist.size * DISTANCE_ENTRY_BYTES);
        }

        Runtime rt = Runtime.getRuntime();
        msg.append(Component.literal(String.format(
            "Levels tracked: chunk=%d  distance=%d  |  JVM heap: %d MB used / %d MB total / %d MB max\n",
            ChunkRiverCache.trackedLevels(), RiverDistanceCache.trackedLevels(),
            (rt.totalMemory() - rt.freeMemory()) >> 20, rt.totalMemory() >> 20, rt.maxMemory() >> 20))
            .withStyle(ChatFormatting.GRAY));

        return msg;
    }

    // One colored block for a single cache layer
    private static void appendCacheSection(MutableComponent msg, String name,
                                           long size, long queued, long candidates,
                                           long queries, long hits, long misses, long evictions,
                                           long estimatedBytes) {
        msg.append(Component.literal("== " + name + " ==\n").withStyle(ChatFormatting.AQUA));
        msg.append(Component.literal(String.format("  elements: %d  queued: %d\n", size, queued)).withStyle(ChatFormatting.GRAY));
        if (candidates != 0) {
            msg.append(Component.literal(String.format("  candidates: %d\n", candidates)).withStyle(ChatFormatting.GRAY));
        }
        msg.append(Component.literal(String.format("  queries: %d  hits: %d  misses: %d  hit rate: %s\n",
            queries, hits, misses,
            queries == 0 ? "n/a" : String.format("%.1f%%", 100.0 * hits / queries)))
            .withStyle(ChatFormatting.YELLOW));
        msg.append(Component.literal(String.format("  evictions: %d\n", evictions)).withStyle(ChatFormatting.GRAY));
        msg.append(Component.literal(String.format("  est. memory: ~%.2f MB (rough)\n", estimatedBytes / 1048576.0))
            .withStyle(ChatFormatting.YELLOW));
    }
}
