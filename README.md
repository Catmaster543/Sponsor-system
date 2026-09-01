# Sponsorship

A server-side NeoForge mod for **Minecraft 1.21.1** that turns your whitelist into a graph of personal responsibility.

Nobody gets onto the server unless somebody backs them, and everybody who backs a player is equally answerable for what
that player does. The link back to whoever vouched for a griefer is visible, permanent, and enforceable. The mod does
not decide punishment; it gives you the tools to see and act on the whole structure.

Not being backed means not being whitelisted.

- **Server-side only.** A vanilla client can connect to a server running this mod. Nothing to install for players.
- **No mixins, no Access Transformers.** Public vanilla APIs and NeoForge events only.
- **Instant effect.** `/invite` whitelists a player the moment the command runs — no restart, no `/whitelist reload`,
  and it works for players who have never joined and are currently offline.

---

## Install

1. Build the jar with `./gradlew build` (output lands in `build/libs/`), or download a release.
2. Drop the jar into your server's `mods/` folder. NeoForge 1.21.1 (21.1.248 or newer) is required.
3. Start the server once. The mod creates `sponsors.json` next to `whitelist.json`, brings the whitelist into line
   with the graph, and logs anything that needs your attention. On a brand-new server it holds the whitelist **off**
   until someone claims the root — see [The founder](#the-founder-starting-a-brand-new-server).
4. Read the startup log. If you had a whitelist already, see [Migration](#migration-from-an-existing-whitelist).

Config lives at `<server>/config/sponsorsystem-server.toml`. It is a NeoForge *server* config, so a per-world copy in
`<world>/serverconfig/sponsorsystem-server.toml` overrides it if you want different rules per world.

---

## Who is responsible for whom

**Everyone who backs you is equally responsible for you.** There is no primary and secondary. Whoever invited you and
whoever sponsored you afterwards are on the hook to exactly the same degree. That is the point: backing someone is not
a courtesy, it is taking on liability, so be careful whose name you put next to your own.

Three words are used consistently throughout:

- **inviter** — the person who brought you onto the server. You have at most one.
- **sponsor** — anyone who added their support afterwards. You can have any number.
- **supporter** — either of the above, when the difference does not matter, which is nearly always.

The difference between an inviter and a sponsor is *bookkeeping only*: it records who got you in, and it gives
`/invitetree` a line to draw. It says nothing about who answers for you.

This makes the structure a graph rather than a tree. You may sponsor your own inviter, or anyone above you — a real
vote of confidence, and entirely legal.

---

## Commands

### For everyone (permission level 0)

| Command | What it does |
|---|---|
| `/invite <name>` | Brings someone new onto the server, whitelists them immediately, and records you as backing them. Only for people not already here. |
| `/sponsor <name>` | Adds your support to someone already here. Grants no new access — it makes you answerable for them. |
| `/uninvite <name>` | Withdraws your own support from someone. |
| `/unsponsor <name>` | The same command under a different name. Use whichever fits what you meant. |

`/uninvite` and `/unsponsor` are aliases of one operation: **remove your own backing of that player**, whatever kind it
was. Since every supporter carries the same weight, taking back an invite and taking back a sponsorship are the same
act.

If withdrawing would leave that player with nobody at all, you are warned first and given a `[confirm]` to click.

### For operators (permission level 3)

| Command | What it does |
|---|---|
| `/invitetree [player]` | Draws the graph: the invite skeleton, everyone's extra sponsors, and who has been abandoned. Names are clickable. |
| `/sponsorship reload` | Re-reads `sponsors.json` from disk. |
| `/sponsorship adopt <player> [supporter]` | Brings someone into the graph, as a root or backed by an existing player. The migration path. |
| `/sponsorship reassign <player> <newSupporter>` | Moves a player's invite edge to a different supporter. |
| `/sponsorship revoke <player>` | Removes a player outright: drops every edge into them, unwhitelists and kicks them. |
| `/sponsorship stats` | Totals, edge counts, abandoned count, orphans. |
| `/sponsorship debug` | Everything: state, whitelist sync, every config value in effect, and the whole graph. |

Operator commands are gated with Brigadier's `requires`, so a player without permission does not merely get refused —
the commands are absent from their tab-completion entirely.

---

## Support, and losing it

A player is **supported** while somebody is backing them. Losing all support sets their status to `ABANDONED`.

**As of this version, abandonment is recorded but not enforced.** An abandoned player keeps their whitelist entry and
carries on playing; they are told they have nobody backing them, and so is the log. The grace period and the eventual
removal are a later version.

Support is recomputed from scratch every time an edge is added or removed, and once at startup. Removing one edge near
a root can strand a whole branch at once — that is correct, and intended.

### `supportModel`

**`REACHABILITY`** (the default) — you are supported if you are a root, or if at least one of your supporters is
themselves supported. Support flows outwards from the roots.

**`DIRECT`** — you are supported if anyone at all backs you, whatever state they are in. This has a hole worth
understanding before you choose it: two players can sponsor each other and hold each other up permanently. Their
inviter withdraws, they prop each other up, and nobody upstream can do anything about it. Use it only if you want that.

Getting support back returns a player to normal immediately, and they are told who stepped in.

## Support tickets

Backing someone costs a **ticket**, and withdrawing gives it back. By default everyone has **10**, shared between
invites and sponsorships — that shared scarcity is what forces a real choice about who is worth backing.

| Key | Default | Purpose |
|---|---|---|
| `maxSupportTicketsPerPlayer` | `10` | The shared pool. `-1` is unlimited. |
| `separateInviteAndSponsorBudgets` | `false` | Split into two independent pools instead. |
| `maxInvitesPerPlayer` | `10` | Invite pool, used only when the budgets are split. |
| `maxSponsorshipsPerPlayer` | `10` | Sponsorship pool, used only when the budgets are split. |
| `unlimitedTicketsPermissionLevel` | `3` | Permission level at which tickets stop applying. The root is always exempt. |
| `sponsorshipMinDurationMinutes` | `10` | How long a sponsorship must be held before its author may withdraw it. Operators bypass. |
| `supportModel` | `REACHABILITY` | See above. |

A ticket comes back when you withdraw, and also when the player you were backing is removed from the server — you are
not left paying for a sponsorship that outlived its target.

`sponsorshipMinDurationMinutes` is an anti-flap measure. Restoring support is meant to be a commitment; without a
minimum, a player could sponsor and immediately withdraw over and over.

---

## Migration from an existing whitelist

Installing this mod on a server that already has a whitelist does **not** delete anything. Existing whitelist entries
have nobody backing them, so on startup they are reported as **orphans**:

```
[Sponsorship] 4 whitelisted player(s) are NOT part of the support graph. They stay whitelisted until an
operator runs /sponsorship adopt <player> [sponsor]: Fiskerz (uuid), Alice (uuid), ...
```

They keep working exactly as before. To bring them into the graph:

```
/sponsorship adopt Fiskerz              # becomes a root - backed by nobody, answerable to nobody
/sponsorship adopt Alice Fiskerz        # joins the graph, backed by Fiskerz
```

`/sponsorship stats` tells you how many orphans are left. You can leave them orphaned indefinitely if you prefer; they
stay whitelisted either way, so there is no rush and no way to lock yourself out by ignoring this.

## The founder: starting a brand-new server

A new server has a problem: the whitelist is on and empty, so nobody can join, so nobody can ever be invited. Vanilla
rejects unwhitelisted logins inside `PlayerList#canPlayerLogin`, which runs *before* any event a mod can hook, so no mod
can let a rejected player back in without a mixin. This one inverts the problem instead:

**The whitelist is not turned on until a root exists.**

On startup, if `sponsors.json` has no entries **and** `whitelist.json` has no entries, the server enters `BOOTSTRAP`:

- the whitelist is turned **off**, `forceWhitelistOn` is ignored for now, and a loud banner explains this in the log;
- the next eligible player to join becomes the root of the graph;
- at that moment their entry is written and saved, the whitelist is turned **on**, anyone who slipped in during the
  window and is not whitelisted is kicked, and the founder is told in chat.

`BOOTSTRAP` can never be re-entered. A tree whose every member has been revoked is not empty — revoked entries stay in
the file as an audit trail — so revoking everyone does **not** re-open the server. That is deliberate: a re-openable
bootstrap is a backdoor.

### Closing the open window

While waiting for a founder the server is genuinely open. On anything public, set one of these first:

```toml
[bootstrap]
    restrictToName = "YourName"      # only this account may claim the root
    restrictToLoopback = true        # only a connection from 127.0.0.1 / ::1 may claim it
```

With `restrictToName` set, anyone else who connects is disconnected with "waiting for its founder" and the server stays
in `BOOTSTRAP`. Name matching ignores case, so the casing in the config does not have to be exact.

| Key | Default | Purpose |
|---|---|---|
| `bootstrap.enabled` | `true` | Allow the founder claim at all. `false` makes an empty tree a hard lockout — see below. |
| `bootstrap.restrictToName` | `""` | Only this username may claim the root. Empty means anyone. |
| `bootstrap.restrictToLoopback` | `false` | Only local connections may claim the root. Single-player/LAN-host counts as local. |
| `bootstrap.opFounder` | `false` | Also `/op` the founder. Off by default: being the root is not the same as being an operator. |

### Installing on a server that already has a whitelist

Bootstrap requires the whitelist to be empty too, so this case is **not** treated as a new server. Your existing
whitelist stays on and everyone on it keeps their access; they are simply reported as orphans until you adopt them. If
bootstrap fired here it would turn your live server's whitelist off, which is why it does not.

If you genuinely want a fresh bootstrap on such a server, stop it and delete `whitelist.json` as well as
`sponsors.json`.

## Locked out?

Every route back in, in order of preference.

**"The graph is empty and `bootstrap.enabled` is false."** This is a deliberate hard lockout; nobody can join. From the
**server console** (not in-game — you cannot get in):

```
/op YourName
```

Operators bypass the whitelist in Java Edition, so you can now connect. Then, in-game or from the console:

```
/sponsorship adopt YourName
```

That makes you a root. If you do not want to stay an operator afterwards, `/deop YourName` from the console.

**"The graph has entries but they are all wrong / the root is gone."** Same route: `/op` yourself from the console, join,
then `/sponsorship adopt <name>` to create a new root, or `/sponsorship reassign` to re-point a branch. Bootstrap will
not re-trigger, because the graph is not empty.

**"I want to see what the mod actually thinks is going on."** `/sponsorship debug` prints the bootstrap state, whitelist
synchronisation, every config value in effect, and the graph itself. Run it from the console if you cannot get in.

**Worst case:** stop the server, delete `sponsors.json` *and* `whitelist.json`, start it again. That is a brand-new
server as far as the mod is concerned, and the next player to join claims the root.

## Running the dev server

Two things bite every developer once. Both look like mod bugs and are not.

**1. The dev server runs in online mode by default.** The `Dev` player cannot authenticate against Mojang, so logging in
fails. Edit `run/server.properties`:

```properties
online-mode=false
```

**2. Whitelist entries store the UUID that was current when they were added.** A name whitelisted while the server was
in *online* mode stores that account's **Mojang** UUID, which does not match the **offline** UUID the same name gets
once `online-mode=false`. The entry then looks correct in `whitelist.json` but does not match the player who joins.

Set `online-mode=false` **first**, then whitelist. If you already have a mismatched file, delete `whitelist.json` and
let the mod rebuild it from `sponsors.json` on the next start.

---

## How it works

- **`sponsors.json`** sits next to `whitelist.json` in the server root — server-scoped, so it survives a world reset,
  and pretty-printed so you can read and edit it by hand. It is written atomically (temp file, then move) on every
  change and again at shutdown, so a crash mid-write cannot corrupt it. A typo in one entry costs that entry, not the
  file: broken rows are skipped and reported.
- **Schema 2.** Players live in `entries` and relationships in a separate `edges` array, because a player can have any
  number of supporters. A **schema 1 file migrates automatically** on first load: each old `sponsor` field becomes one
  invite edge, and an entry that had no sponsor is marked as a root. Before the first schema 2 write, the original is
  copied to **`sponsors.json.v1.bak`**, so a migration you disagree with can be undone by hand.
- **UUIDs are the only identity key.** Names are refreshed on login and used for display only, so name changes and name
  reuse cannot confuse the graph. Every clickable name in `/invitetree` navigates by UUID for the same reason.
- **Every graph and whitelist change happens on the server thread.** The only asynchronous part is the Mojang profile
  lookup behind `/invite`, which runs on the background executor and hops back before touching anything.
- **Nothing half-commits.** Support is recomputed and the graph saved to disk first; the whitelist is only touched once
  that save succeeded. If the write fails, the graph is rolled back to what is on disk and the command says so.
- **Cycles are legal.** Sponsoring someone above you is allowed, so the edge set can contain loops. Every traversal
  carries a visited set.
- **Statuses.** `PENDING` (invited, whitelisted, never joined), `ACTIVE` (has joined and is supported), `ABANDONED`
  (nobody backs them — still whitelisted, not yet enforced), `REVOKED` (removed by an operator — kept as an audit
  trail, not whitelisted).
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
game instance — 116 tests covering the two support models, branch-wide abandonment, inviter promotion, ticket spend
and refund in both budget modes, the schema 1 migration, cycle handling in every traversal, and command permission gating.

JUnit is the one dependency added to the MDK, and it is test-only; the mod itself pulls in nothing beyond what the MDK
already ships.
