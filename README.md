# ContractCore

A configurable contract/quest plugin for Paper servers. Players browse a YAML-defined
selection of contracts, accept them within configurable limits, complete objectives
during normal gameplay, track progress, and claim rewards exactly once.

## Requirements

- Paper **1.21.x** (developed and tested against Paper 1.21.11, build 132)
- Java **21+**
- No other plugins required. Economy plugins are *not* used — rewards are experience levels only.

## Build

```
mvn package
```

The plugin jar is `target/ContractCore-1.0.0.jar` (SQLite JDBC driver is shaded inside).
Run the test suite with `mvn test` (47 tests: config validation, service state machine,
real SQLite persistence).

## Installation

1. Drop `ContractCore-1.0.0.jar` into the server's `plugins/` folder.
2. Start the server. `plugins/ContractCore/config.yml` is created from the bundled sample.
3. Edit the contracts to taste and run `/contractcore reload` (or restart).

Player data is stored in `plugins/ContractCore/data/contracts.db` (SQLite, WAL mode).
Deleting that file resets all player progress.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/contracts` | `contractcore.use` | Opens the contract GUI (browse / accept / track / claim). |
| `/contracts accept <id>` | `contractcore.use` | Accepts a contract. |
| `/contracts claim <id>` | `contractcore.use` | Claims the reward of a completed contract. |
| `/contracts abandon <id>` | `contractcore.use` | Abandons an active contract (frees a slot, no reward). |
| `/contracts progress` | `contractcore.use` | Prints active contracts in chat. |
| `/contractcore reload` | `contractcore.admin.reload` | Reloads and validates `config.yml`; the old config is kept on error. |
| `/contractcore assign <player> <id>` | `contractcore.admin.manage` | Force-accepts a contract for an online player (ignores enabled/limits). |
| `/contractcore complete <player> <id>` | `contractcore.admin.manage` | Marks a contract complete for testing. |
| `/contractcore reset <player> [id]` | `contractcore.admin.manage` | Removes one contract (or all, without id) for a player. |
| `/contractcore info <player>` | `contractcore.admin.manage` | Dumps the player's stored contract rows. |
| `/contractcore save` | `contractcore.admin.manage` | Flushes dirty player data to the database immediately. |

`contractcore.admin` is a parent of both admin nodes and defaults to `op`.

## Configuration

```yaml
settings:
  max-active-contracts: 3      # how many ACTIVE contracts a player may hold
  autosave-seconds: 20         # interval for writing dirty player data to SQLite

contracts:
  - id: zombie-hunter          # lowercase, a-z 0-9 _ -, unique
    name: "Zombie Hunter"      # shown in GUI and messages
    description: "Kill 20 zombies."
    objective:
      type: KILL_MOB           # BREAK_BLOCK | KILL_MOB | COLLECT_ITEM
      target: ZOMBIE           # material name for blocks/items, entity name for mobs
      amount: 20
    difficulty: MEDIUM         # EASY | MEDIUM | HARD (cosmetic, colors the GUI)
    enabled: true              # false hides the contract from players
    repeatable: true           # may be taken again after completion
    cooldown-seconds: 7200     # re-accept delay after claim/abandon (repeatable only)
    reward:
      exp: 400                 # experience levels granted on claim
```

Unknown keys, unknown materials/entities, duplicate ids, invalid amounts and
inconsistent cooldowns all reject the whole configuration with a message listing
every problem. Materials are resolved against the running server, so a config that
is valid on 1.21.11 may fail on another version — the error will say so.

## Objective semantics and anti-farm rules

- **BREAK_BLOCK** — counts block breaks in Survival/Adventure. Blocks the player
  placed themselves never count (blocks silk-touch re-place loops). The tracking
  set is per-player, capped at 2048 recent placements, and lives in memory only.
- **KILL_MOB** — kills are credited to the player who dealt the damage (the
  killer's UUID is tagged on the mob when hit, so kills by fall/environment after
  player damage still count). Spawn-egg and command-spawned mobs never count.
  Normal spawner farming is not blocked — that is vanilla gameplay.
- **COLLECT_ITEM** — counts when an item is picked up; one progress per pickup
  event regardless of stack size. Items the collector dropped themselves never
  count, and each item entity counts at most once, so a failed pickup (e.g. full
  inventory) cannot be replayed by picking the same entity up again. Trading or
  passing items between players counts for the recipient — this is deliberately
  not distinguishable from normal trading.

Creative/spectator blocks never count. All event handlers exit immediately when
the player has no matching active contract. Blocks placed before a contract was
accepted are not tracked (only relevant when a player was building while holding
a break contract).

## Rewards and failure semantics

Rewards are experience **levels** (the green bar number), not raw experience points —
`player.giveExpLevels`, so `exp: 30` gives 30 levels. The claim is a two-step operation:

1. An atomic database transition marks the contract `CLAIMED` (this is what
   makes duplicate claims impossible — a second transition matches zero rows).
2. On success the reward is applied to the online player on the main thread.

Known consequence of that order, in order of likelihood:

- **Crash after step 1, before step 2:** the XP is lost; the contract shows as
  claimed. This is logged on the next claim attempt as "already claimed". A
  server operator can compensate manually. (Reversing the order would make
  duplicate rewards possible instead, which is worse.)
- **Quit during the claim window (milliseconds):** same effect; the reward is
  not delivered and a warning is logged.
- **Hard crash (kill -9):** up to `autosave-seconds` of objective progress may
  be lost. Accepts, claims and admin operations are written through immediately.
  A clean `/stop` flushes everything.

## Threading

- After the one-time open/migration during startup, the SQLite database is owned
  by a single dedicated thread; the server thread never blocks on the database.
- Async continuations re-enter the main thread through Paper's
  `GlobalRegionScheduler` before touching players, inventories or messages.
- In-memory player state is a `ConcurrentHashMap` of immutable records; no
  Bukkit `Player` references are held anywhere (UUID keys only).

## Known limitations

- Paper 1.21.x only; the compile-time dependency is a snapshot
  (`1.21.11-R0.1-SNAPSHOT`). Pin a release build for production.
- No economy integration (by design). Reward handling is isolated behind
  `RewardHandler` so money rewards can be added without touching the claim flow.
- Admin commands only target **online** players.
- Messages are English-only and not configurable.
- `enabled: false` hides a contract from browsing; `/contractcore assign` can
  still force it.
- Contracts removed from the config leave orphan rows in the database that are
  simply ignored everywhere (`/contractcore reset` removes them).

## Development

```
mvn test                 # 47 unit/integration tests
mvn package              # shaded jar in target/
```

## Architecture

```
com.blockbuilder.contractcore
  ContractCorePlugin       lifecycle and wiring only
  commands                 /contracts, /contractcore
  listeners                objective tracking, GUI clicks, join/quit lifecycle
  managers                 ContractService (state machine), ContractRegistry,
                           PlayerDataStore (async SQLite), ConfigLoader,
                           ContractGui, reward delivery
  models                   contract/objective/reward records, player state,
                           and result enums
  utils                    messages, PDC keys, material/entity validators
```

New objective types are one enum value plus a listener handler and a validation
branch — the progress and claim pipeline is type-agnostic.
