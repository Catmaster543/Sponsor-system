# Sponsorship

A server-side NeoForge mod for **Minecraft 1.21.1** that turns your whitelist into a tree of personal responsibility.

Every player on the server was invited by exactly one other player, who becomes their permanent **sponsor**. Anyone can
invite anyone — but the link back to whoever vouched for a griefer is visible, permanent, and enforceable. The mod does
not decide punishment; it gives you the tools to act on a whole branch at once.

Nobody who was not invited by someone can join. Not being in the tree means not being whitelisted.

- **Server-side only.** A vanilla client can connect to a server running this mod. Nothing to install for players.
- **No mixins, no Access Transformers.** Public vanilla APIs and NeoForge events only.
- **Instant effect.** `/invite` whitelists a player the moment the command runs — no restart, no `/whitelist reload`,
  and it works for players who have never joined and are currently offline.

---

## Install

1. Build the jar with `./gradlew build` (output lands in `build/libs/`), or download a release.
2. Drop the jar into your server's `mods/` folder. NeoForge 1.21.1 (21.1.248 or newer) is required.
3. Start the server once. The mod creates `sponsors.json` next to `whitelist.json`, turns the whitelist on if it was
   off, and logs anything that needs your attention.
4. Read the startup log. If you had a whitelist already, see [Migration](#migration-from-an-existing-whitelist).

Config lives at `<server>/config/sponsorsystem-server.toml`. It is a NeoForge *server* config, so a per-world copy in
`<world>/serverconfig/sponsorsystem-server.toml` overrides it if you want different rules per world.

---

## Commands

### For everyone (permission level 0)

| Command | What it does |
|---|---|
| `/invite <name>` | Whitelists that player immediately and records you as their sponsor. Works for players who have never joined. |
| `/uninvite <name>` | Withdraws a sponsorship **you** issued. Removes their whole branch from the whitelist and kicks any of them who are online. |
| `/sponsor [name]` | Shows who sponsored a player, and the full chain of responsibility up to the root. |
| `/invites [name]` | Lists a player's direct invitees, their status, and how many invites they have left. |
| `/invitetree [name]` | Draws the player's branch as an indented tree. |

Leaving `[name]` off means "me". All five have tab completion, suggesting names from the sponsorship tree and from
`whitelist.json`.

`/sponsor` is always public — a visible chain of responsibility is the point of the system. `/invites` and
`/invitetree` only let you look at *other* players when `publicInviteTree` is on (it is, by default) or when you are an
operator.

### For operators (permission level 3)

| Command | What it does |
|---|---|
| `/sponsorship reload` | Re-reads `sponsors.json` from disk and reconciles it against the whitelist. |
| `/sponsorship adopt <player> [sponsor]` | Brings a player who is not in the tree into it, as a root or under a given sponsor. This is the migration path. |
| `/sponsorship reassign <player> <newSponsor>` | Moves a player and their whole branch under a new sponsor. Refuses anything that would create a loop. |
| `/sponsorship revoke <player>` | Force-revokes, regardless of who sponsored them. |
| `/sponsorship stats` | Totals, orphan count, deepest chain, and whether the whitelist is actually on. |

---

## Config

`config/sponsorsystem-server.toml`:

| Key | Default | Purpose |
|---|---|---|
| `forceWhitelistOn` | `true` | Turn the vanilla whitelist on at startup if it is off, and log loudly that it was. |
| `maxInvitesPerPlayer` | `-1` | How many live invites one player may hold. `-1` is unlimited. Operators are exempt. |
| `minPlaytimeMinutesToInvite` | `0` | Playtime needed before a player may invite anyone. `0` disables. Operators are exempt. |
| `inviteCooldownMinutes` | `0` | Minimum gap between one player's invites. `0` disables. Operators are exempt. |
| `pendingInviteExpiryHours` | `0` | Hours before an unused invite expires and is unwhitelisted. `0` means never. |
| `cascadeOnRevoke` | `true` | Revoking someone also revokes their whole branch. `false` re-parents their invitees onto their sponsor. |
| `announceInvites` | `true` | Broadcast invites and revocations server-wide. Social pressure is the whole point. |
| `allowOfflineModeUuids` | `false` | On an offline-mode server, derive UUIDs from names instead of asking Mojang. |
| `publicInviteTree` | `true` | Whether players may inspect other players' invites and subtrees. |

Revoked invitees do not count against `maxInvitesPerPlayer` — revoking frees the slot again.

---

## Migration from an existing whitelist

Installing this mod on a server that already has a whitelist does **not** delete anything. Existing whitelist entries
have no sponsor, so on startup they are reported as **orphans**:

```
[Sponsorship] 4 whitelisted player(s) have no sponsor and are NOT part of the tree. They stay whitelisted until an
operator runs /sponsorship adopt <player> [sponsor]: Fiskerz (uuid), Alice (uuid), ...
```

They keep working exactly as before. To bring them into the tree:

```
/sponsorship adopt Fiskerz              # becomes a root — no sponsor, answerable to nobody
/sponsorship adopt Alice Fiskerz        # joins the tree beneath Fiskerz
```

`/sponsorship stats` tells you how many orphans are left. You can leave them orphaned indefinitely if you prefer; they
stay whitelisted either way, so there is no rush and no way to lock yourself out by ignoring this.

### Bootstrapping an empty server

Operators bypass the whitelist entirely in Java Edition, which is what makes this recoverable: even with an empty tree
and the whitelist forced on, an operator can always get in. The first operator to run `/invite` is adopted as a root of
the tree automatically (logged when it happens), so the tree can grow its first branch from in-game without touching
any files.

---

## How it works

- **`sponsors.json`** sits next to `whitelist.json` in the server root — server-scoped, so it survives a world reset,
  and pretty-printed so you can read and edit it by hand. It is written atomically (temp file, then move) on every
  change and again at shutdown, so a crash mid-write cannot corrupt it. A typo in one entry costs that entry, not the
  file: broken entries are skipped and reported, dangling sponsors and hand-edited loops are repaired into roots.
- **UUIDs are the only identity key.** Names are refreshed on login and used for display only, so name changes and name
  reuse cannot confuse the tree.
- **Every graph and whitelist change happens on the server thread.** The only asynchronous part is the Mojang profile
  lookup behind `/invite`, which runs on the background executor and hops back before touching anything.
- **Nothing half-commits.** The tree is updated and saved to disk first, and the whitelist is only touched once that
  save succeeded. If the write fails, the tree is rolled back to what is on disk and the command reports the failure.
- **Statuses.** `PENDING` (invited, whitelisted, never joined), `ACTIVE` (has joined), `REVOKED` (withdrawn — kept as an
  audit trail, not whitelisted). A revoked player can be invited again; their row is reused rather than duplicated.

### A note on operators

Operators bypass the whitelist in vanilla Minecraft. Removing an operator from the whitelist therefore does not keep
them out, and revoking one who is online kicks them but does not stop them reconnecting. If you need an operator gone,
deop them first. The mod says as much where it matters rather than pretending otherwise.

---

## Building and testing

```
./gradlew build     # compiles, runs the tests, produces build/libs/sponsorsystem-1.0.0.jar
./gradlew test      # tests only
```

The sponsorship graph and its JSON store are plain Java with no Minecraft imports, so they are unit tested without a
game instance — 48 tests covering cascade revocation, cycle rejection, orphan re-parenting, chain-to-root, invite
limits, and the file-corruption paths.

JUnit is the one dependency added to the MDK, and it is test-only; the mod itself pulls in nothing beyond what the MDK
already ships.
