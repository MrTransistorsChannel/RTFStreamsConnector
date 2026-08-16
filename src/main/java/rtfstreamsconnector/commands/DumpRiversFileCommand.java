package rtfstreamsconnector.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Range;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.River;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarver;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverWarp;
import rtfstreamsconnector.rivers.RiverCarverReflection;
import rtfstreamsconnector.rivers.RiverWarpReflection;
import rtfstreamsconnector.rivers.RivermapReflection;
import rtfstreamsconnector.util.PrivateFieldReflector;
import rtfstreamsconnector.util.RTFHelpers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Usage:
//   /rtfconnector dumprivers <x> <z> [server|world|config]
// Dumps EVERY river network (roots + full subtrees) of the single region
// (continent-lattice cell) containing block (x, z) to a pretty-printed JSON
// file. Default save location: server (see dumpDirFor below). The file is
// written next to the run's other dumps; the MATLAB sim loader
// (tools/load_network_dump.m) picks up the newest *.json from a folder.
public final class DumpRiversFileCommand {

    private static final String DUMP_SUBDIR = "rtf-dumps";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final SuggestionProvider<CommandSourceStack> LOCATION_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(new String[] {"server", "world", "config"}, builder);

    private DumpRiversFileCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtfconnector").requires(source -> source.hasPermission(2))
            .then(Commands.literal("dumprivers")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(DumpRiversFileCommand::executeDumpRivers)
                        .then(Commands.argument("location", StringArgumentType.word())
                            .suggests(LOCATION_SUGGESTIONS)
                            .executes(DumpRiversFileCommand::executeDumpRiversWithLocation))))));
    }

    private static int executeDumpRivers(CommandContext<CommandSourceStack> ctx) {
        return dumpAndReport(ctx, "server");
    }

    private static int executeDumpRiversWithLocation(CommandContext<CommandSourceStack> ctx) {
        return dumpAndReport(ctx, StringArgumentType.getString(ctx, "location"));
    }

    private static int dumpAndReport(CommandContext<CommandSourceStack> ctx, String variant) {
        CommandSourceStack source = ctx.getSource();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        source.sendSuccess(() -> dumpRiversToFile(source.getLevel(), x, z, variant), false);
        return 1;
    }

    private static MutableComponent dumpRiversToFile(ServerLevel level, int x, int z, String variant) {
        // Get the chunkGen region containing this block (same pattern as DumpRiverNetworkCommand)
        GeneratorContext ctx = RTFHelpers.generatorContextOf(level);
        Heightmap heightmap = ctx.generator.getHeightmap();
        Cell cell = new Cell();
        heightmap.continent().apply(cell, x, z);
        Rivermap rivermap = heightmap.continent().getRivermap(cell);
        Network[] rootNetworks = RivermapReflection.networksOf(rivermap);

        if (rootNetworks == null)
            return Component.literal("River network fetching failed! Check server logs for more information.").withStyle(ChatFormatting.RED);
        if (rootNetworks.length == 0) return Component.literal("No river networks found in this region!").withStyle(ChatFormatting.RED);

        JsonObject root = new JsonObject();
        root.addProperty("regionX", cell.continentX);
        root.addProperty("regionZ", cell.continentZ);
        root.addProperty("probeX", x);
        root.addProperty("probeZ", z);
        JsonArray rivers = new JsonArray();
        int riverCount = 0;
        for (Network network : rootNetworks) {
            rivers.add(buildNetwork(network));
            riverCount += countRivers(network);
        }
        root.add("rivers", rivers);

        try {
            Path dumpDir = dumpDirFor(level, variant);
            Files.createDirectories(dumpDir);
            String fileName = "rivers_region_" + cell.continentX + "_" + cell.continentZ + "_x" + x + "_z" + z + ".json";
            Path out = dumpDir.resolve(fileName);
            Files.writeString(out, GSON.toJson(root));
            return Component.literal("Dumped " + riverCount + " river(s) to " + out.toAbsolutePath()).withStyle(ChatFormatting.GREEN);
        } catch (IOException | RuntimeException e) {
            return Component.literal("Failed to write river dump: " + e).withStyle(ChatFormatting.RED);
        }
    }

    private static JsonObject buildNetwork(Network network) {
        RiverCarver carver = network.riverCarver();
        River river = carver.river;
        RiverWarp warp = carver.warp;
        Range bed = RiverCarverReflection.bedWidth(carver);
        Range banks = RiverCarverReflection.banksWidth(carver);
        Range valley = RiverCarverReflection.valleyWidth(carver);

        JsonObject node = new JsonObject();
        node.addProperty("x1", (double) river.x1);
        node.addProperty("z1", (double) river.z1);
        node.addProperty("x2", (double) river.x2);
        node.addProperty("z2", (double) river.z2);
        node.addProperty("length", (double) river.length);
        node.addProperty("seed", RiverWarpReflection.seedOf(warp));
        node.addProperty("scale", (double) RiverWarpReflection.scaleOf(warp));
        node.addProperty("frequency", (double) RiverWarpReflection.frequencyOf(warp));
        node.addProperty("lower", (double) RiverWarpReflection.lowerOf(warp));
        node.addProperty("upper", (double) RiverWarpReflection.upperOf(warp));
        node.addProperty("fade", (double) RiverCarverReflection.fadeOf(carver));
        node.addProperty("bedMin", Math.sqrt(bed.min()));
        node.addProperty("bedMax", Math.sqrt(bed.max()));
        node.addProperty("banksMin", Math.sqrt(banks.min()));
        node.addProperty("banksMax", Math.sqrt(banks.max()));
        node.addProperty("valleyMin", Math.sqrt(valley.min()));
        node.addProperty("valleyMax", Math.sqrt(valley.max()));
        node.addProperty("curveL", parseDouble(PrivateFieldReflector.accessor(carver.valleyCurve, "lower")));
        node.addProperty("curveU", parseDouble(PrivateFieldReflector.accessor(carver.valleyCurve, "upper")));

        JsonArray children = new JsonArray();
        for (Network child : network.children()) children.add(buildNetwork(child));
        node.add("children", children);
        return node;
    }

    private static int countRivers(Network network) {
        int count = 1;
        for (Network child : network.children()) count += countRivers(child);
        return count;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static Path dumpDirFor(ServerLevel level, String variant) {
        switch (variant == null ? "server" : variant) {
            case "world":
                return level.getServer().getWorldPath(LevelResource.ROOT).resolve(DUMP_SUBDIR);
            case "config":
                return FMLPaths.CONFIGDIR.get().resolve(DUMP_SUBDIR);
            case "server":
            default:
                return level.getServer().getServerDirectory().resolve(DUMP_SUBDIR);
        }
    }

}