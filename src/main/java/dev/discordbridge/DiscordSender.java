package dev.discordbridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Sends messages from Minecraft to Discord. All sends run on one background thread, in order. */
final class DiscordSender {
	private static final Logger LOG = LoggerFactory.getLogger("discordbridge/sender");
	private static final Gson GSON = new Gson();
	private static final String USER_AGENT = "DiscordBot (https://github.com/discordbridge, 1.0.0)";

	private final BridgeConfig config;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "DiscordBridge-Sender");
		t.setDaemon(true);
		return t;
	});

	DiscordSender(BridgeConfig config) {
		this.config = config;
	}

	void sendPlayerMessage(String playerName, String text) {
		enqueue(playerName, text, false);
	}

	void sendSystem(String text) {
		enqueue(config.serverName, text, true);
	}

	void shutdown() {
		executor.shutdown();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void enqueue(String name, String text, boolean system) {
		try {
			executor.execute(() -> deliver(name, text, system));
		} catch (RuntimeException e) {
			// executor already shut down
		}
	}

	private void deliver(String name, String text, boolean system) {
		if (config.hasWebhook()) {
			JsonObject body = base(text);
			body.addProperty("username", name.length() > 80 ? name.substring(0, 80) : name);
			int status = post(config.webhookUrl, body, false);
			// Discord rejects some webhook usernames (for example ones containing "discord"). Fall back to the bot.
			if (status != 400) return;
		}
		String content = system ? text : "**" + name + "**: " + text;
		post("https://discord.com/api/v10/channels/" + config.channelId + "/messages", base(content), true);
	}

	private static JsonObject base(String content) {
		JsonObject body = new JsonObject();
		body.addProperty("content", content.length() > 2000 ? content.substring(0, 2000) : content);
		// Stops @everyone, @here and role/user pings coming from Minecraft chat.
		JsonObject allowed = new JsonObject();
		allowed.add("parse", new JsonArray());
		body.add("allowed_mentions", allowed);
		return body;
	}

	private int post(String url, JsonObject body, boolean useBotAuth) {
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(15))
						.header("Content-Type", "application/json")
						.header("User-Agent", USER_AGENT)
						.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
				if (useBotAuth) request.header("Authorization", "Bot " + config.botToken);
				HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
				int status = response.statusCode();
				if (status == 429) {
					Thread.sleep((long) (retryAfterSeconds(response.body()) * 1000) + 100);
					continue;
				}
				if (status >= 300) {
					LOG.warn("Discord returned HTTP {}: {}", status, response.body());
				}
				return status;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return -1;
			} catch (Exception e) {
				LOG.warn("Failed to send message to Discord: {}", e.toString());
				return -1;
			}
		}
		return -1;
	}

	private static double retryAfterSeconds(String body) {
		try {
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			return o.has("retry_after") ? o.get("retry_after").getAsDouble() : 1.0;
		} catch (Exception e) {
			return 1.0;
		}
	}
}
