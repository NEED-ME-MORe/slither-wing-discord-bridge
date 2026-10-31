package dev.discordbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BridgeConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Bot token from the Discord Developer Portal. Keep this private. */
	public String botToken = "PASTE_BOT_TOKEN_HERE";
	/** ID of the channel that is bridged. */
	public String channelId = "PASTE_CHANNEL_ID_HERE";
	/** Optional. If set, Minecraft messages show the player's name and skin as the sender. */
	public String webhookUrl = "";
	/** Sender name used for join/leave/death/status messages when a webhook is set. */
	public String serverName = "Server";
	public boolean relayJoinLeave = true;
	public boolean relayDeaths = true;
	public boolean relayServerStatus = true;

	public boolean isConfigured() {
		return botToken != null && !botToken.isBlank() && !botToken.startsWith("PASTE_")
				&& channelId != null && channelId.matches("\\d{5,25}");
	}

	public boolean hasWebhook() {
		return webhookUrl != null && webhookUrl.startsWith("https://");
	}

	private void normalize() {
		botToken = botToken == null ? "" : botToken.trim();
		channelId = channelId == null ? "" : channelId.trim();
		webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
		if (serverName == null || serverName.isBlank()) serverName = "Server";
	}

	public static BridgeConfig load(Path path) {
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				BridgeConfig config = GSON.fromJson(reader, BridgeConfig.class);
				if (config != null) {
					config.normalize();
					return config;
				}
			} catch (IOException | JsonParseException e) {
				DiscordBridge.LOGGER.error("Could not read {}: {}", path, e.getMessage());
			}
			// Do not overwrite a file the user may be mid-edit on.
			return new BridgeConfig();
		}
		BridgeConfig defaults = new BridgeConfig();
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(defaults, writer);
		} catch (IOException e) {
			DiscordBridge.LOGGER.error("Could not write default config to {}: {}", path, e.getMessage());
		}
		return defaults;
	}
}
