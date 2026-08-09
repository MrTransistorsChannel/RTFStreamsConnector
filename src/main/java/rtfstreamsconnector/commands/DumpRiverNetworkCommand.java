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
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarver;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverWarp;
import rtfstreamsconnector.rivers.RiverCarverReflection;
import rtfstreamsconnector.rivers.RiverWarpReflection;
import rtfstreamsconnector.rivers.RivermapReflection;
import rtfstreamsconnector.util.PrivateFieldReflector;
import rtfstreamsconnector.util.RTFHelpers;

public final class DumpRiverNetworkCommand {

    private DumpRiverNetworkCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtfconnector")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("dumpnetwork")
                .executes(DumpRiverNetworkCommand::executeDumpNetworkHere)
                .then(Commands.literal("here")
                    .executes(DumpRiverNetworkCommand::executeDumpNetworkHere)
                    .then(Commands.literal("recursive")
                        .executes(DumpRiverNetworkCommand::executeDumpNetworkHereRecursively)))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(DumpRiverNetworkCommand::executeDumpNetwork)
                        .then(Commands.literal("recursive")
                            .executes(DumpRiverNetworkCommand::executeDumpNetworkRecursively))))));
    }

    // Dumps the important information about the nearest river network
    private static MutableComponent dumpNetwork(ServerLevel level, int x, int z, boolean recursive) {
        // Get the chunkGen region containing this block
        GeneratorContext ctx = RTFHelpers.generatorContextOf(level);
        Heightmap heightmap = ctx.generator.getHeightmap();
        Cell cell = new Cell();
        heightmap.continent().apply(cell, x, z);
        Rivermap rivermap = heightmap.continent().getRivermap(cell);
        Network[] rootNetworks = RivermapReflection.networksOf(rivermap);

        if (rootNetworks == null)
            return Component.literal("River network fetching failed! Check server logs for more information.").withStyle(ChatFormatting.RED);
        if (rootNetworks.length == 0)
            return Component.literal("No river networks found in this region!").withStyle(ChatFormatting.RED);

        MutableComponent msg = Component.literal("Main rivers in this region:\n");

        for (int i = 0; i < rootNetworks.length; i++) {
            if (recursive)
                msg.append(dumpRiversRecursively(rootNetworks[i], i == rootNetworks.length - 1, ""));
            else msg.append(riverInfo(rootNetworks[i], i == rootNetworks.length - 1, ""));
        }

        return msg;
    }

    private static int executeDumpNetwork(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        source.sendSuccess(() -> dumpNetwork(source.getLevel(), x, z, false), false);
        return 1;
    }

    private static int executeDumpNetworkRecursively(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        source.sendSuccess(() -> dumpNetwork(source.getLevel(), x, z, true), false);
        return 1;
    }

    private static int executeDumpNetworkHere(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int x = (int) source.getPosition().x();
        int z = (int) source.getPosition().z();
        source.sendSuccess(() -> dumpNetwork(source.getLevel(), x, z, false), false);
        return 1;
    }

    private static int executeDumpNetworkHereRecursively(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int x = (int) source.getPosition().x();
        int z = (int) source.getPosition().z();
        source.sendSuccess(() -> dumpNetwork(source.getLevel(), x, z, true), false);
        return 1;
    }

    private static MutableComponent dumpRiversRecursively(Network network, boolean lastChild, String prefix) {
        MutableComponent msg = riverInfo(network, lastChild, prefix);
        for (int i = 0; i < network.children().length; i++) {
            msg.append(dumpRiversRecursively(network.children()[i], i == network.children().length - 1,
                prefix + (lastChild ? "  " : "│ ")));
        }
        return msg;
    }

    private static MutableComponent riverInfo(Network network, boolean lastItem, String prefix) {
        RiverCarver carver = network.riverCarver();
        River river = carver.river;
        RiverWarp riverWarp = carver.warp;
        MutableComponent msg = Component.empty();

        // ---- First line ----
        String connector = lastItem ? "└─" : "├─";
        msg.append(Component.literal(prefix + connector + "Start: (" + river.x1 + ", " + river.z1 + "),"));
        msg.append(Component.literal(" End: (" + river.x2 + ", " + river.z2 + "), "));
        msg.append(Component.literal(" Length: " + river.length + "\n"));

        // ---- Subsequent lines ----
        // For the current level we use either "  " (if last) or "│ " (if not)
        String continuation = lastItem ? "  " : "│ ";

        // Warp settings block
        msg.append(Component.literal(prefix + continuation + "\n"));
        msg.append(Component.literal(prefix + continuation + "Warp settings:\n"));
        msg.append(Component.literal(prefix + continuation + "    Seed: " + RiverWarpReflection.seedOf(riverWarp) +
            "\n"));
        msg.append(Component.literal(prefix + continuation + "    Scale: " + RiverWarpReflection.scaleOf(riverWarp) +
            "\n"));
        msg.append(Component.literal(prefix + continuation + "    Frequency: " + RiverWarpReflection.frequencyOf
            (riverWarp) + "\n"));
        msg.append(Component.literal(prefix + continuation + "    Lower alpha boundary: " + RiverWarpReflection
            .lowerOf(riverWarp) + "\n"));
        msg.append(Component.literal(prefix + continuation + "    Upper alpha boundary: " + RiverWarpReflection
            .upperOf(riverWarp) + "\n"));

        // Carver settings block
        msg.append(Component.literal(prefix + continuation + "\n"));
        msg.append(Component.literal(prefix + continuation + "Carver settings:\n"));
        msg.append(Component.literal(prefix + continuation + "    Fade: " + RiverCarverReflection.fadeOf(carver) +
            "\n"));
        msg.append(Component.literal(prefix + continuation + "    Bed Width: " + Math.sqrt(RiverCarverReflection
            .bedWidth(carver).min()) + " to " + Math.sqrt(RiverCarverReflection.bedWidth(carver).max()) + "\n"));
        msg.append(Component.literal(prefix + continuation + "    Banks Width: " + Math.sqrt(RiverCarverReflection
            .banksWidth(carver).min()) + " to " + Math.sqrt(RiverCarverReflection.banksWidth(carver).max()) + "\n"));
        msg.append(Component.literal(prefix + continuation + "    Valley Width: " + Math.sqrt(RiverCarverReflection
            .valleyWidth(carver).min()) + " to " + Math.sqrt(RiverCarverReflection.valleyWidth(carver).max()) + "\n"));
        msg.append(Component.literal(prefix + continuation + "    Valley S-Curve coeffs: L=" + PrivateFieldReflector
            .accessor(carver.valleyCurve, "lower") + ", U=" + PrivateFieldReflector.accessor(carver.valleyCurve,
            "upper")
            + "\n"));
        msg.append(Component.literal(prefix + continuation + "\n"));
        return msg;
    }

}
