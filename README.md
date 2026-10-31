# Discord Bridge (Fabric 1.21.1, server side)

Two-way chat between a Minecraft server and one Discord channel.

Minecraft to Discord: player chat, joins, leaves, deaths, server start/stop.
Discord to Minecraft: messages from people in the channel, shown as "[Discord] name: message".

The mod runs inside the Minecraft server, so the bridge works whenever the server is running.
Requires Fabric API on the server.

## 1. Create the Discord bot

1. Go to https://discord.com/developers/applications and click New Application.
2. Open the Bot tab. Click Reset Token and copy the token. Treat it like a password.
3. On the same tab, under Privileged Gateway Intents, turn on MESSAGE CONTENT INTENT and save.
4. Open OAuth2 > URL Generator. Tick the scope "bot". Under Bot Permissions tick View Channels,
   Send Messages and Read Message History. Open the generated URL and add the bot to your server.
5. In Discord, turn on Developer Mode (User Settings > Advanced). Right-click the channel you want to
   bridge and choose Copy Channel ID.

Player names always appear on Minecraft messages. Without a webhook they show as a bold prefix
("**Steve**: hello") under the bot's own avatar. Optional webhook, so the player's name is the
sender header instead (no skin icons, the webhook's own avatar is used): Channel settings >
Integrations > Webhooks > New Webhook > Copy Webhook URL.

## 2. Build the jar (no Java needed on your PC)

1. Create a free GitHub account and a new empty repository.
2. Upload everything in this folder to it, including the hidden ".github" folder. If the web uploader
   skips ".github", create the file .github/workflows/build.yml in the repo by hand and paste in the
   contents from this project.
3. Open the Actions tab and wait for the "build" run to finish (a few minutes). Open the run and
   download the artifact "discordbridge-jar".
4. Unzip it. Use discordbridge-1.0.0.jar. Ignore the -sources jar.

If the build fails, open the failed run, copy the red error lines, and send them back.

Building locally instead: install JDK 21, run `./gradlew build` (or `gradlew.bat build` on Windows),
and take the jar from build/libs/.

## 3. Install

1. Upload discordbridge-1.0.0.jar to the server's mods folder. Fabric API must already be there.
2. Start the server once. It creates config/discordbridge.json and says it is not configured.
3. Open config/discordbridge.json in the file manager and fill in botToken and channelId
   (and webhookUrl if you made one). Keep the quotes.
4. Restart the server. The console should log "Connected to Discord as <bot name>".

## Config options (config/discordbridge.json)

- botToken, channelId: required.
- webhookUrl: optional, see above.
- serverName: sender name for join/leave/death/status messages when a webhook is used.
- relayJoinLeave, relayDeaths, relayServerStatus: true or false.

## Troubleshooting

- Nothing arrives in Minecraft, messages reach Discord fine: MESSAGE CONTENT INTENT is off, or the
  bot cannot see the channel.
- Nothing arrives in Discord: check the channel ID and that the bot can send messages in that channel.
- "Discord rejected the bot token": reset the token in the Developer Portal and paste the new one.
- If the token ever leaks, reset it immediately.
