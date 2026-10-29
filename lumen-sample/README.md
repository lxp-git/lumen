# Lumen sample

An ordinary app (Application-scoped graph, ViewModel + StateFlow, ViewBinding, Retrofit, Room)
whose only Lumen code is the plugin line in [`build.gradle`](build.gradle). Each card exercises
one Lumen feature:

| Scenario | What it does | Look in |
|---|---|---|
| `http` | PokeAPI via Retrofit, POST body, app-negotiated gzip, PNG, 404, DNS failure | Network · `lumen.py net`, `req <id>` |
| `mock` | Requests answered by [`src/debug/assets/lumen-mocks`](src/debug/assets/lumen-mocks) | Network (mocked) · `lumen.py mock list` |
| `websocket` | 30 streamed deltas + a binary frame to an echo server | Network → Messages |
| `socket_io` | Socket.IO over websocket and polling | Network → Messages |
| `logcat` | Every priority, a stack trace, a 200-line burst | Console · `lumen.py logs` |
| `storage` | SharedPreferences, Preferences DataStore, MMKV | Application · `lumen.py kv list` |
| `database` | Room `sample.db` | `lumen.py db sql sample.db "SELECT * FROM favorites"` |
| `all` | Everything above | |

Run from the UI, or without touching the screen:

```bash
./gradlew :lumen-sample:installDebug
adb shell am start -n dev.lumen.sample/.MainActivity --es scenario all
adb shell am start -n dev.lumen.sample/.MainActivity --es fetch https://pokeapi.co/api/v2/pokemon/ditto
```

`fetch` sends one GET through the app's client, handy for trying a rule added with
`lumen.py mock add`; add `--ei count 1500` to send that many, e.g. to fill the network history
past what Lumen keeps in memory. The public echo server used for Socket.IO is load balanced without
sticky sessions, so the polling session can be refused after its handshake; the websocket
session is the reliable one.

`./gradlew :lumen-sample:assembleRelease` builds a minified release with no Lumen classes or
providers in it.
