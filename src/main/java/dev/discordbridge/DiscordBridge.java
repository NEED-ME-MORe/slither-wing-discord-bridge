package dev.discordbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class DiscordBridge implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("discordbridge");
	private static final int MAX_DISCORD_TO_MC_LENGTH = 400;

	private BridgeConfig config;
	private DiscordSender sender;
	private DiscordGateway gateway;
	private volatile MinecraftServer server;

	@Override
	public void onInitialize() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("discordbridge.json");
		config = BridgeConfig.load(path);
		if (!config.isConfigured()) {
			LOGGER.warn("Discord Bridge is not configured. Edit {} (botToken and channelId), then restart the server.", path);
			return;
		}
		sender = new DiscordSender(config);

		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			gateway = new DiscordGateway(config.botToken, this::onDiscordMessage);
			gateway.start();
			if (config.relayServerStatus) sender.sendSystem("\u2705 Server started");
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			if (config.relayServerStatus) sender.sendSystem("\uD83D\uDED1 Server stopping");
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			if (gateway != null) gateway.stop();
			sender.shutdown();
		});

		ServerMessageEvents.CHAT_MESSAGE.register((message, player, params) ->
				sender.sendPlayerMessage(player.getName().getString(), message.signedContent()));

		if (config.relayJoinLeave) {
			ServerPlayConnectionEvents.JOIN.register((handler, packetSender, s) ->
					sender.sendSystem("\u27A1\uFE0F " + handler.getPlayer().getName().getString() + " joined the game"));
			ServerPlayConnectionEvents.DISCONNECT.register((handler, s) ->
					sender.sendSystem("\u2B05\uFE0F " + handler.getPlayer().getName().getString() + " left the game"));
		}

		if (config.relayDeaths) {
			ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
				if (entity instanceof ServerPlayer player) {
					sender.sendSystem("\uD83D\uDC80 " + player.getCombatTracker().getDeathMessage().getString());
				}
			});
		}
	}

	private void onDiscordMessage(JsonObject d) {
		MinecraftServer s = server;
		if (s == null) return;
		if (!config.channelId.equals(str(d, "channel_id"))) return;
		if (d.has("webhook_id")) return;

		JsonObject author = d.getAsJsonObject("author");
		if (author == null) return;
		JsonElement bot = author.get("bot");
		if (bot != null && bot.isJsonPrimitive() && bot.getAsBoolean()) return;

		String content = cleanContent(d);
		if (content.isEmpty()) return;
		if (content.length() > MAX_DISCORD_TO_MC_LENGTH) {
			content = content.substring(0, MAX_DISCORD_TO_MC_LENGTH) + "...";
		}

		String name = null;
		JsonObject member = d.has("member") && d.get("member").isJsonObject() ? d.getAsJsonObject("member") : null;
		if (member != null) name = str(member, "nick");
		if (name == null) name = str(author, "global_name");
		if (name == null) name = str(author, "username");
		if (name == null) name = "Unknown";

		Component line = Component.literal("[Discord] ").withStyle(ChatFormatting.BLUE)
				.append(Component.literal(name).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(content).withStyle(ChatFormatting.WHITE));
		s.execute(() -> s.getPlayerList().broadcastSystemMessage(line, false));
	}

	/** Turns Discord markup (mentions, custom emoji, attachments) into readable plain text. */
	private static String cleanContent(JsonObject d) {
		String content = str(d, "content");
		if (content == null) content = "";

		JsonElement mentionsElement = d.get("mentions");
		if (mentionsElement != null && mentionsElement.isJsonArray()) {
			JsonArray mentions = mentionsElement.getAsJsonArray();
			for (JsonElement e : mentions) {
				if (!e.isJsonObject()) continue;
				JsonObject user = e.getAsJsonObject();
				String id = str(user, "id");
				String display = str(user, "global_name");
				if (display == null) display = str(user, "username");
				if (id == null || display == null) continue;
				content = content.replace("<@" + id + ">", "@" + display).replace("<@!" + id + ">", "@" + display);
			}
		}
		content = content.replaceAll("<a?:(\\w+):\\d+>", ":$1:")
				.replaceAll("<#\\d+>", "#channel")
				.replaceAll("<@&\\d+>", "@role")
				.replaceAll("<@!?\\d+>", "@user")
				.replaceAll("\\s*\\R\\s*", " ")
				.trim();

		JsonElement attachments = d.get("attachments");
		if (attachments != null && attachments.isJsonArray() && attachments.getAsJsonArray().size() > 0) {
			int n = attachments.getAsJsonArray().size();
			content = (content.isEmpty() ? "" : content + " ") + "[" + n + (n == 1 ? " attachment]" : " attachments]");
		}
		return content;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}
}
