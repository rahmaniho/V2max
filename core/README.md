# V2max core

The platform-independent heart of the app, rewritten from scratch.

Everything here is **pure JVM Kotlin** — no Android imports, no third-party
dependencies. That is a deliberate constraint: it means this code compiles and
runs under a plain JDK, so the highest-risk logic in a VPN client (link parsing,
Xray config generation, server ranking) is covered by tests that actually
execute, instead of being verified only on a device.

```
core/
  model/     ir.v2max.core.model      domain types, settings, measurements
  protocol/  ir.v2max.core.protocol   share-link parsing, JSON reader
  config/    ir.v2max.core.config     Xray config generation, JSON writer
  domain/    ir.v2max.core.domain     server selection and failover heuristics
  format/    ir.v2max.core.format     Persian digits, sizes, durations, Jalali
  geo/       ir.v2max.core.geo        country detection from a server name
  testsuite/ ir.v2max.core.test       test suite and runner
```

## Running the tests

The suite has two entry points that exercise the *same* checks via
`runAllSuites()`, so there is no second copy of the assertions to drift:

```bash
tools/jvm.sh              # compile core + run the suite (no build system)
tools/jvm.sh --compile    # compile only
gradle :core:testsuite:test   # the same suite, under Gradle
```

`tools/jvm.sh` needs a JDK 17+ and the Kotlin compiler, and looks for them under
`$TOOLCHAIN_DIR` (default `~/.toolchain`):

| Path | Used for |
| --- | --- |
| `jdkw/jdk4py/java-runtime/bin/java` | running `kotlinc` and the suite |
| `kc/package/bin/kotlinc` | compiling |
| `kc/package/lib/kotlin-stdlib.jar` | runtime classpath |

If those are missing, `tools/setup-toolchain.sh` fetches them (a Temurin 17 from
the `jdk4py` PyPI wheel, and JetBrains' own `kotlin-compiler` package from npm).
Override with `JAVA_HOME` / `TOOLCHAIN_DIR` as needed. No network access is
required at test time.

## What changed from the previous implementation

**Immutable domain types.** The old `ServerConfig` exposed ~40 mutable `var`
fields, and `AppSettings` was the same. Any layer could mutate a server while a
test batch was scoring it, or flip a setting mid-connection, so the config could
be built from a half-updated snapshot. State now changes through `copy()`.

**Volatile state separated from identity.** Latency, speed, success/failure
counts and "what did the last successful test look like" moved into
`Measurement`. Re-measuring a server can therefore never change its
`dedupeKey()`, which previously conflated identity with health.

**Sealed types replace stringly-typed fields.** `network`, `security` and
`headerType` were free-form strings, so `network = "grcp"` or
`security = "reality"` with no public key compiled fine and failed at runtime
inside the core. `Transport` and `Security` are sealed hierarchies now, so those
states are unrepresentable. Likewise `Credential` makes it impossible for a
Trojan server to carry a `uuid` and no password.

**Failures carry a reason.** `LinkParser` returned `null` for every failure, so a
subscription where 5 of 50 links were unusable told the user nothing. It now
returns `ParseResult.Ok` or `ParseResult.Skipped(reason)` with reasons
`COMMENT`, `UNKNOWN_SCHEME`, `MALFORMED`, `INCOMPLETE`, `UNSUPPORTED`, `ERROR`.

**Unsupported input is reported, not coerced.** Two cases that previously
produced a server that *looks* fine and never connects are now surfaced:

- a Shadowsocks plugin other than simple-obfs HTTP;
- the legacy `h2` / `http` / `quic` transports, which the current Xray core has
  removed. Coercing these onto TCP would have been a silent downgrade.

**No hidden I/O in pure logic.** Config generation used to ask a
filesystem-backed `GeoAssets` singleton which `.dat` files existed. The platform
layer now resolves availability and injects `GeoAssets(geositeTag, geoipTag)`;
a null tag means "emit the built-in fallback rules only".

**Zero third-party dependencies.** Gson was replaced by a small JSON reader
(`ir.v2max.core.protocol.Json`) covering the subset the protocols need, and the
config side uses an ordered writer (`JObj` / `JArr`). Order is preserved so a
generated config stays diffable when debugging a connection.

**Selection heuristics are pure.** The rules deciding *which* server to use were
buried inside a `suspend` function that also owned a mutex, published UI state
and started the tunnel, so they could not be tested at all. `ServerSelector`
extracts them as plain functions — `prioritize`, `resolve`, `decide` — and names
the two tuned constants that were previously inline magic numbers:
`REMEMBERED_TOLERANCE = 60` (score points) and `KEEP_CURRENT_FACTOR = 1.35f`.
Separating "which server" from "what to do about it" matters because they have
different failure modes: picking a mediocre server is a performance problem,
while switching away from a working tunnel is a visible outage.

## Behaviour deliberately preserved

These are byte-for-byte compatible with the previous implementation, because
changing them would silently reorder servers or break existing configs for users:

- `score()`, `stability()`, `bestPing()`, `reachCount()` — identical arithmetic,
  including the `if/else` that makes the no-speed penalty and the speed bonus
  mutually exclusive, the 14-step cap on the speed bonus, and the
  "no data = 70" stability default.
- `Endpoint.usable` — same loopback/unspecified/port-range exclusions.
- The `FragmentProfile` values, and the rule that REALITY uses `packets = "1-3"`
  while plain TLS uses the profile value.
- An explicit `finalmask` from a share link suppresses generated fragmentation.
- A certificate pin overrides `allowInsecure`, since it is the stricter option.
- Tag names, private CIDR list, DNS hosts, and routing rule order.

## Not covered here

`core` has no Android code. The following still live only in `app/` and are the
next stage of the rewrite: the `VpnService` and TUN lifecycle, Compose UI,
persistence, subscription fetching, the update checker, WorkManager scheduling,
notifications and the widget. Those layers depend on platform APIs that cannot
be compiled or run in a headless environment.

The `core` modules are plain JVM modules in the Gradle build, and `:app` depends
on four of them — `:core:model`, `:core:format`, `:core:domain` and
`:core:geo`. Where core and the app had duplicate logic, the app's copy was
deleted rather than left to drift: `Format`/`Jalali`, `CountryFlags`, the
built-in server cap (`Repo.capped`) and the subscription merge
(`Repo.replaceSubscriptionServers`) all now run core code.

Two of those call into core through accessor lambdas, because the app still
holds its own mutable `ServerConfig` and has not adopted core's immutable
`Server`. `ServerListPolicy.capBuiltIn` and `SubscriptionMerger.merge` are
generic over the element type for that reason, and their `Server`-typed
overloads delegate to the same implementation, so both paths stay covered.

`:core:protocol` and `:core:config` are built and tested but not yet consumed
by `:app`; wiring them in means adopting core's `Server` type in the app, which
is the next stage of the rewrite.
