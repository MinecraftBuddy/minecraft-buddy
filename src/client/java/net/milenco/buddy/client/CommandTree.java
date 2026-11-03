package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The commands the server sent, with everything under them: sub-commands, arguments and their kinds. The list of
 * names alone ({@code serverinfo}) does not say that {@code /order} takes {@code create <item> <aantal> <prijs>}.
 */
public final class CommandTree {
	private CommandTree() {}

	private static final Gson GSON = new Gson();
	/** Deeper than this is a loop or vanilla's {@code /execute}; a plugin command never gets there. */
	private static final int MAX_DEPTH = 12;

	/** {@code serverinfo} with {@code boom}: the whole tree to {@code commandtree.json}. Only reads what the client already holds. */
	static void dump(Minecraft mc) {
		var conn = mc.getConnection();
		if (conn == null) { Exchange.emit("error", "msg", "no connection"); return; }
		List<CommandNode<?>> roots = new ArrayList<>(conn.getCommands().getRoot().getChildren());
		roots.sort(Comparator.comparing(CommandNode::getName));
		int[] count = {0};
		JsonArray commands = new JsonArray();
		for (CommandNode<?> root : roots) commands.add(node(root, 0, count));
		JsonObject o = new JsonObject();
		o.addProperty("t", System.currentTimeMillis());
		o.addProperty("server", AfkHelperClient.serverIp());
		if (mc.player != null) o.addProperty("player", mc.player.getGameProfile().name());
		o.add("commands", commands);
		Path file = Path.of(AfkConfig.get().exchangeDir, "commandtree.json");
		try { Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8); }
		catch (IOException e) { Exchange.emit("error", "msg", "the server's command list not saved: " + e.getMessage()); return; }
		Exchange.emit("commandtree", "file", file.toString(), "commands", roots.size(), "nodes", count[0]);
	}

	private static JsonObject node(CommandNode<?> node, int depth, int[] count) {
		count[0]++;
		JsonObject o = new JsonObject();
		o.addProperty("name", node.getName());
		if (node instanceof ArgumentCommandNode<?, ?> arg) {
			o.addProperty("kind", "argument");
			o.addProperty("type", type(arg.getType()));
			// the server fills these in itself when you press tab: see the command "tab"
			if (arg.getCustomSuggestions() != null) o.addProperty("suggestions", true);
		} else o.addProperty("kind", "literal");
		if (node.getCommand() != null) o.addProperty("executable", true);
		if (node.getRedirect() != null) { o.addProperty("redirect", node.getRedirect().getName()); return o; }
		if (!node.getChildren().isEmpty() && depth < MAX_DEPTH) {
			List<CommandNode<?>> children = new ArrayList<>(node.getChildren());
			children.sort(Comparator.comparing(CommandNode::getName));
			JsonArray below = new JsonArray();
			for (CommandNode<?> child : children) below.add(node(child, depth + 1, count));
			o.add("children", below);
		}
		return o;
	}

	private static String type(ArgumentType<?> type) {
		String id;
		try { id = String.valueOf(BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(ArgumentTypeInfos.byClass(type))); }
		catch (RuntimeException e) { id = type.getClass().getSimpleName(); }
		if (type instanceof StringArgumentType s) return id + ":" + s.getType().name().toLowerCase(Locale.ROOT);
		// their toString carries the bounds: "integer(1, 64)"
		if (type instanceof IntegerArgumentType || type instanceof LongArgumentType || type instanceof FloatArgumentType || type instanceof DoubleArgumentType) return id + ":" + type;
		return id;
	}

	/**
	 * What tab would offer after this text, as an event {@code tab}. Where the server fills in the suggestions the
	 * client asks it, exactly as when you type the text in the chat box; nothing is ever sent as a command. The event
	 * carries {@code start}, where in the text the suggestions begin (what they replace runs from there to the end), and
	 * the panel's {@code id} for the question (the panel's chat box completes commands with it, as tab does in the
	 * game).
	 */
	static void complete(Minecraft mc, String text, String id) {
		var conn = mc.getConnection();
		if (conn == null) { Exchange.emit("error", "msg", "no connection"); return; }
		int cut = text.startsWith("/") ? 1 : 0;
		String input = text.substring(cut);
		var parse = conn.getCommands().parse(input, conn.getSuggestionsProvider());
		conn.getCommands().getCompletionSuggestions(parse).thenAccept(found -> {
			List<String> out = new ArrayList<>();
			for (var s : found.getList()) out.add(s.getText());
			// the server's answer comes on the network's thread: written from the game's own, as every other event
			mc.execute(() -> {
				if (id == null) Exchange.emit("tab", "text", text, "start", found.getRange().getStart() + cut, "suggestions", out);
				else Exchange.emit("tab", "id", id, "text", text, "start", found.getRange().getStart() + cut, "suggestions", out);
			});
		});
	}
}
