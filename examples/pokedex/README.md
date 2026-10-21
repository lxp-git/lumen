# Lumen in a real app: skydoves/Pokedex

[Pokedex](https://github.com/skydoves/Pokedex) (8k+ stars) is a widely copied showcase of
current Android practice: multi-module, Hilt, Coroutines + Flow, Room, Retrofit on OkHttp 5,
Moshi, DataBinding views, AGP 9 with a version catalog. It is a good stand-in for "an app you
did not write": its `OkHttpClient` is built inside the `core-network` library module, it
logs through Timber, and it caches everything in Room.

Adding Lumen takes one plugin alias. Nothing in Pokedex's Kotlin code changes.

```diff
 # gradle/libs.versions.toml
 [versions]
+lumen = "0.3.0"
 [plugins]
+lumen = { id = "io.github.lxp-git.lumen", version.ref = "lumen" }

 // app/build.gradle.kts
 plugins {
   ...
   alias(libs.plugins.hilt.plugin)
+  alias(libs.plugins.lumen)
 }
```

That is [`lumen.patch`](lumen.patch). The two files in [`lumen-mocks/`](lumen-mocks) go to
`app/src/debug/assets/lumen-mocks/`, so they exist only in debug builds.

## Run it

```bash
./examples/pokedex/setup.sh             # pinned upstream commit + this checkout of Lumen
./examples/pokedex/setup.sh --released  # same, with the plugin from the Gradle Plugin Portal

cd examples/pokedex/build/Pokedex
./gradlew :app:installDebug          # JDK 17+, Android SDK with API 37
adb shell monkey -p com.skydoves.pokedex -c android.intent.category.LAUNCHER 1
```

The default publishes this checkout to `build/maven-repo` first
([`scripts/publish-local.sh`](../../scripts/publish-local.sh)). `--released` only works with a
Lumen release after 0.3.0: Pokedex has minSdk 23, and 0.3.0 required 24.

`setup.sh` also applies [`upstream-compile-sdk.patch`](upstream-compile-sdk.patch), which has
nothing to do with Lumen: Pokedex's `main` currently fails its own CI because Glide 5 and
OkHttp 5.5 require compileSdk 37.

## What to look at

Open `chrome://inspect` and inspect `lumen://com.skydoves.pokedex`, or use the
[skill's CLI](../../plugins/lumen/skills/lumen/scripts/lumen.py) from the repo root
(`L=python3 plugins/lumen/skills/lumen/scripts/lumen.py`):

| Feature | In Pokedex | Chrome | CLI |
|---|---|---|---|
| Network | The first page loads before you could open DevTools; it is still there. Open a Pokémon for its detail call. | Network | `$L -p com.skydoves.pokedex net`, `req <id>` |
| Wire headers | OkHttp 5's `Accept-Encoding: gzip`, `User-Agent: okhttp/5.5.0` are recorded as sent, not as Retrofit built them. | Network → Headers | `req <id>` |
| Mocks | Open **Pikachu**: 9.9 m, 99.9 kg, Electric / Dragon (`pikachu-legendary.json`). Scroll to the second page: a slow 503 (`page-two-unavailable.json`) and Pokedex's error toast. | Network (rows marked mocked) | `mock list` |
| Live overrides | Pick a Pokémon request → *Override content*, edit, reload. | Network → Override | `mock add --url-contains pokemon/eevee --body '…'` |
| Logs | Timber's `HttpRequestInterceptor` lines and everything else since process start. | Console | `logs --grep pokeapi` |
| Database | Room's `Pokedex.db`: `PokemonEntity`, `PokemonInfoEntity`. | — (Chrome removed Web SQL) | `db sql Pokedex.db "SELECT name, page FROM PokemonEntity"` |
| Views | DataBinding layouts, RecyclerView items, Material components. | Elements | `ui --grep TextView` |
| Export | — | — | `har -o pokedex.har` |

Room caches pages and details, so a mock only shows for data Pokedex has not cached yet.
Clear the app's storage (`adb shell pm clear com.skydoves.pokedex`) to start over.

Pokedex has no WebSocket, Socket.IO, SharedPreferences, DataStore or MMKV; the
[in-repo sample](../../lumen-sample) covers those.
