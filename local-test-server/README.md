# Fallen Local Test Server

This directory is a disposable local Purpur 26.2 flat-world test server. The
server version matches the Paper 26.2 development bundle used by the plugin.

On macOS, double-click this file in the project root:

```text
start-local-test-server.command
```

It opens Terminal and performs the complete build, deploy, download-if-needed,
and server startup flow. To run the same flow from an existing terminal:

```bash
local-test-server/run.sh
```

The script builds `:survival:shadowJar`, copies the plugin to `plugins/QuantumPlugin.jar`, downloads `server.jar` if missing, and starts the server with `nogui`.

The server binds to `*:25565`, so other LAN clients can connect to this machine's LAN IP. The script sets `DISABLE_QO_API=true` and `FALLEN_LOCAL_TEST=true` by default, so QO API integrations are skipped and the production launch-date gate is bypassed. To test the default production behavior:

```bash
DISABLE_QO_API=false FALLEN_LOCAL_TEST=false local-test-server/run.sh
```

Useful reset flags:

```bash
RESET_WORLD=1 RESET_FALLEN=1 local-test-server/run.sh
```

Default test layout in world `world`:

- A old-city region: `x=-96..-17, z=-48..48`
- A fu-island region: `x=-96..-17, z=80..176`
- B region: `x=17..96, z=-48..48`
- C region: `x=-40..40, z=-176..-80`
- A stations: `-56,-60,0` and `-56,-60,128`
- Sample placed keys are seeded from `fallen-local-test.yml`.

Use `test-commands.txt` from the server console after joining.

## Finale preview

After joining, replace `<name>` and run these commands from the server console:

```text
op <name>
fallen team set <name> A
fallen finale test A
```

The preview does not change the event phase, scores, or winner records. It blinds
the selected winning team, freezes all online players, removes their client-side
chunks in rate-limited expanding rings, shows the simulated-environment shutdown
titles and progress broadcasts, and then kicks everyone. Duration varies with
the players' view distance. Players can reconnect immediately.

To abort before it finishes:

```text
fallen finale cancel
```

If chunks have already disappeared, affected players are disconnected once so
that reconnecting cleanly reloads the terrain.
