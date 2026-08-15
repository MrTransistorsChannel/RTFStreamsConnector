package rtfstreamsconnector.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import rtfstreamsconnector.rivers.ChunkRiverCache;
import rtfstreamsconnector.rivers.ChunkRiverCache.CacheEntry;
import rtfstreamsconnector.rivers.ChunkRiverCache.RiverCandidateInfo;
import rtfstreamsconnector.rivers.RTFFlowProvider;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarver;

// Temporary diagnostic command - safe to delete at any time.
// Usage:
//   /rtfconnector probe            - candidates of the player's chunk + flow at the player's block
//   /rtfconnector probe x z        - same for block (x, z)
public final class ProbeCommand {

    private ProbeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtfconnector").requires(source -> source.hasPermission(2))
            .then(Commands.literal("probe")
                .executes(ProbeCommand::executeProbeHere)
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(ProbeCommand::executeProbe)))));
    }

    private static int executeProbeHere(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int x = (int) source.getPosition().x();
        int z = (int) source.getPosition().z();
        source.sendSuccess(() -> probe(source.getLevel(), x, z), false);
        return 1;
    }

    private static int executeProbe(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        source.sendSuccess(() -> probe(source.getLevel(), x, z), false);
        return 1;
    }

    private static MutableComponent probe(ServerLevel level, int x, int z) {
        MutableComponent msg = Component.literal("---- Chunk/flow probe ----\n").withStyle(ChatFormatting.GREEN);

        int cx = x >> 4;
        int cz = z >> 4;
        int x0 = cx << 4;
        int z0 = cz << 4;
        msg.append(Component.literal("Chunk (" + cx + ", " + cz + ")  blocks " + x0 + ".." + (x0 + 15) + " x " + z0 + ".." + (z0 + 15) + "\n").withStyle(ChatFormatting.GRAY));

        // ---- Candidate rivers of this chunk ----
        CacheEntry entry = ChunkRiverCache.getCandidatesForChunk(level, cx, cz);
        if (entry == null || entry.candidates.isEmpty()) {
            msg.append(Component.literal("No candidate rivers for this chunk!\n").withStyle(ChatFormatting.RED));
        } else {
            msg.append(Component.literal("Candidates (" + entry.candidates.size() + "):\n").withStyle(ChatFormatting.AQUA));
            int i = 1;
            for (RiverCandidateInfo info : entry.candidates) {
                Network leaf = info.riverChain[info.riverChainLength - 1];
                RiverCarver carver = leaf.riverCarver();
                River river = carver.river;
                msg.append(Component.literal(String.format(
                    "  %d. (%6.1f, %6.1f) -> (%6.1f, %6.1f)  len=%6.1f  bw=%d  main=%s  chain=%d  hash=%08x\n",
                    i++, river.x1, river.z1, river.x2, river.z2, river.length,
                    carver.config.bankWidth, carver.main, info.riverChainLength, info.riverHash)).withStyle(ChatFormatting.YELLOW));
            }
        }

        // ---- Flow at the queried block ----
        double rad = RTFFlowProvider.flowRadAt(level, x, z);
        if (Double.isNaN(rad)) {
            msg.append(Component.literal("Flow at (" + x + ", " + z + "): NaN (no flow)\n").withStyle(ChatFormatting.RED));
        } else {
            double deg = Math.toDegrees(rad);
            msg.append(Component.literal(String.format("Flow at (%d, %d): %.1f deg  (%.3f rad)%n", x, z, deg, rad)).withStyle(ChatFormatting.AQUA));
        }

        return msg;
    }
}