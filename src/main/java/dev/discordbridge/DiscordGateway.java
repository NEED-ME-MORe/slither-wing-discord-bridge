package dev.discordbridge;

import com.google.gson.Gson;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Minimal Discord gateway client (receive-only use). Connects, identifies, keeps the heartbeat
 * going and reconnects when the connection drops. Passes MESSAGE_CREATE payloads to a callback.
 */
final class DiscordGateway {
	private static final Logger LOG = LoggerFactory.getLogger("discordbridge/gateway");
	private static final Gson GSON = new Gson();
	private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
	// GUILD_MESSAGES (1 << 9) + MESSAGE_CONTENT (1 << 15)
	private static final int INTENTS = (1 << 9) | (1 << 15);

	private final String token;
	private final Consumer<JsonObject> onMessage;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "DiscordBridge-Gateway");
		t.setDaemon(true);
		return t;
	});
	private final AtomicInteger generation = new AtomicInteger();
	private volatile boolean stopped;
	private volatile Session session;
	private volatile int backoffSeconds = 2;

	DiscordGateway(String token, Consumer<JsonObject> onMessage) {
		this.token = token;
		this.onMessage = onMessage;
	}

	void start() {
		connect();
	}

	void stop() {
		stopped = true;
		Session s = session;
		if (s != null) s.close();
		scheduler.shutdownNow();
	}

	private void connect() {
		if (stopped) return;
		int gen = generation.get();
		Session s = new Session(gen);
		session = s;
		http.newWebSocketBuilder().buildAsync(URI.create(GATEWAY_URL), s).whenComplete((ws, err) -> {
			if (err != null) {
				LOG.warn("Could not connect to Discord gateway: {}", err.toString());
				scheduleReconnect(gen);
			}
		});
	}

	private void scheduleReconnect(int gen) {
		if (stopped || !generation.compareAndSet(gen, gen + 1)) return;
		Session old = session;
		if (old != null) old.close();
		int delay = backoffSeconds;
		backoffSeconds = Math.min(backoffSeconds * 2, 60);
		LOG.info("Reconnecting to Discord in {}s", delay);
		try {
			scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
		} catch (RejectedExecutionException ignored) {
			// shutting down
		}
	}

	private final class Session implements WebSocket.Listener {
		private final int gen;
		private final StringBuilder buffer = new StringBuilder();
		private volatile WebSocket ws;
		private volatile Integer seq;
		private volatile boolean ack = true;
		private volatile ScheduledFuture<?> heartbeat;

		Session(int gen) {
			this.gen = gen;
		}

		void close() {
			ScheduledFuture<?> h = heartbeat;
			if (h != null) h.cancel(false);
			WebSocket w = ws;
			if (w != null) w.abort();
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			this.ws = webSocket;
			webSocket.request(1);
		}

		@Override
		public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				String text = buffer.toString();
				buffer.setLength(0);
				if (gen == generation.get()) {
					try {
						handle(JsonParser.parseString(text).getAsJsonObject());
					} catch (Exception e) {
						LOG.warn("Error handling gateway payload: {}", e.toString());
					}
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
			if (gen != generation.get()) return null;
			LOG.warn("Discord gateway closed: {} {}", code, reason);
			if (code == 4004) {
				LOG.error("Discord rejected the bot token. Check botToken in config/discordbridge.json.");
				fatal();
			} else if (code == 4013 || code == 4014) {
				LOG.error("Discord refused the requested intents. Enable MESSAGE CONTENT INTENT for the bot in the Developer Portal.");
				fatal();
			} else {
				scheduleReconnect(gen);
			}
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			if (gen != generation.get()) return;
			LOG.warn("Discord gateway error: {}", error.toString());
			scheduleReconnect(gen);
		}

		private void fatal() {
			stopped = true;
			close();
		}

		private void handle(JsonObject payload) {
			if (payload.has("s") && !payload.get("s").isJsonNull()) {
				seq = payload.get("s").getAsInt();
			}
			int op = payload.get("op").getAsInt();
			switch (op) {
				case 10 -> {
					long interval = payload.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
					startHeartbeat(interval);
					identify();
				}
				case 11 -> ack = true;
				case 1 -> sendHeartbeat();
				case 7, 9 -> scheduleReconnect(gen);
				case 0 -> dispatch(payload);
				default -> { }
			}
		}

		private void dispatch(JsonObject payload) {
			String type = payload.get("t").getAsString();
			JsonObject d = payload.getAsJsonObject("d");
			if (type.equals("READY")) {
				backoffSeconds = 2;
				String name = d.getAsJsonObject("user").get("username").getAsString();
				LOG.info("Connected to Discord as {}", name);
			} else if (type.equals("MESSAGE_CREATE")) {
				onMessage.accept(d);
			}
		}

		private void startHeartbeat(long interval) {
			long first = (long) (interval * ThreadLocalRandom.current().nextDouble());
			heartbeat = scheduler.scheduleAtFixedRate(() -> {
				try {
					if (!ack) {
						LOG.warn("Discord did not acknowledge heartbeat, reconnecting");
						scheduleReconnect(gen);
						return;
					}
					ack = false;
					sendHeartbeat();
				} catch (Exception e) {
					LOG.warn("Heartbeat error: {}", e.toString());
				}
			}, first, interval, TimeUnit.MILLISECONDS);
		}

		private void sendHeartbeat() {
			JsonObject o = new JsonObject();
			o.addProperty("op", 1);
			Integer s = seq;
			o.add("d", s == null ? JsonNull.INSTANCE : new JsonPrimitive(s));
			sendJson(o);
		}

		private void identify() {
			JsonObject props = new JsonObject();
			props.addProperty("os", "linux");
			props.addProperty("browser", "discordbridge");
			props.addProperty("device", "discordbridge");
			JsonObject d = new JsonObject();
			d.addProperty("token", token);
			d.addProperty("intents", INTENTS);
			d.add("properties", props);
			JsonObject o = new JsonObject();
			o.addProperty("op", 2);
			o.add("d", d);
			sendJson(o);
		}

		private void sendJson(JsonObject o) {
			WebSocket w = ws;
			if (w == null) return;
			try {
				synchronized (this) {
					w.sendText(GSON.toJson(o), true).get(10, TimeUnit.SECONDS);
				}
			} catch (Exception e) {
				LOG.warn("Failed to send to Discord gateway: {}", e.toString());
				scheduleReconnect(gen);
			}
		}
	}
}
