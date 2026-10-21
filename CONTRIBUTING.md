# Contributing

Issues and pull requests are welcome at https://github.com/lxp-git/lumen.

## Build and test

JDK 17, Android SDK (API 36), and a device or emulator for anything user-visible.

```bash
./gradlew :lumen-runtime:testDebugUnitTest      # JVM unit tests
./gradlew :lumen-runtime:lintDebug              # includes NewApi checks at minSdk 21
./gradlew :lumen-sample:installDebug
adb shell am start -n dev.lumen.sample/.MainActivity --es scenario all
python3 plugins/lumen/skills/lumen/scripts/lumen.py -p dev.lumen.sample net
python3 -m unittest discover -s plugins/lumen/tests
```

To try a change in another app, `./scripts/publish-local.sh` and add `build/maven-repo` as
the first repository there (the script prints how). `examples/pokedex/setup.sh --local` does
this for Pokedex.

## Guidelines

- Write new code in Kotlin. Stetho-derived Java stays Java unless it is being rewritten.
- A debug agent must never crash or slow the host app: catch at entry points, keep disk and
  JSON work off the main thread and the OkHttp call path, and log only through
  `LogRedirector` (silent unless `debugLogs = true`).
- Chrome's frontend is the spec. When a change works around frontend behaviour, say which
  Chrome version and why in a comment next to it.
- Keep the release variant clean: nothing may make Lumen reach a non-debuggable build.
- Two-space indentation, 100 columns. One logical change per commit, with a message that says
  why.

## License

By contributing you agree that your contributions are licensed under the MIT license in
[LICENSE](LICENSE).
