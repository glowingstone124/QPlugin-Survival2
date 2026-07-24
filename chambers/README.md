# QuantumPlugin Chambers target

`./gradlew :chambers:shadowJar` builds the standalone chambers plugin.

## Chamber package format

Every chamber is a self-contained directory:

```text
plugins/QuantumPlugin-Chambers/
├── chambers.yml
└── chambers/
    └── laser-intro/
        ├── structure.nbt
        ├── goal.yml
        └── scripts/                 # optional
            ├── welcome.yml
            ├── hint-region.yml
            └── button-hint.yml
```

- `structure.nbt` is loaded through Bukkit's `StructureManager` when the plugin starts or reloads.
- `goal.yml` contains the title, objective, time limit, entity placement flag, spawn, and goal cuboid.
- Entering a chamber displays `title` as the title and `objective` as the subtitle.
- Coordinates in `goal.yml` are relative to the structure placement origin.
- Every YAML file in `scripts/` is a declarative server-side message trigger. It cannot execute
  console commands.
- Trigger types are `enter`, `region`, `interact-block`, `complete`, and `fail`.
- Message channels are `chat`, `action-bar`, and `title`, and each line can use `delay-ticks`.
- Delayed lines are discarded if the player leaves that chamber or the test run ends.
- Available text placeholders are `{player}`, `{chamber}`, `{completed}`, and `{total}`.

Copy `goal.example.yml` as the starting point for a new package. Add the directory name to the
global `pool` only after both required files are present.

## Runtime model

- A player must have a `PENDING` Minecraft test request in QAPI before joining.
- The chambers server atomically claims that request using its node token; otherwise the player is kicked.
- Every accepted player receives a separate void world named `qchamber_<player UUID>`.
- The run randomly selects `selection-count` unique package ids from `pool`.
- Selected NBT structures are placed along the X axis with the configured gap.
- Reaching a package's relative goal cuboid advances to the next selected chamber.
- Completion, timeout, cancellation, disconnect, and plugin shutdown unload and delete the instance.
- Exact instance-name validation protects the template and normal worlds from cleanup.

## Authoring

1. Run `/chambers build` as an operator to enter the persistent void authoring world.
2. Build a chamber in a stable initial state.
3. Export it to `<chamber id>/structure.nbt`.
4. Copy and edit `goal.example.yml`.
5. Add optional YAML message triggers from `script-examples/`.
6. Add the directory id to `pool`, then run `/chambers reload`.

`/chambers start` remains an operator-only practice command. Normal players can only start through
the claimed `MinecraftRegistrationTest` session created by the join gate.

The QAPI node entry used by this server must have role `SERVER` and a name included in
`CHAMBERS_NODE_NAMES` (default: `chambers`).
