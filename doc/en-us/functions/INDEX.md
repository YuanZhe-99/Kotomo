# Function index

One row per module and tool: its role and the declarations to read first. Declarations carry the
Function Explanation Layer in their doc comments (first sentence: purpose; then inputs, returns,
side effects and notes where non-trivial).

| Module / file | Role | Start with |
|---|---|---|
| `tool/CheckDocs.java` | Documentation mirror and link check | `main`, `read` |
| `app/build.gradle.kts` | Android app build: SDK levels, version, R8, release signing from `key.properties` | `signingConfigs`, `buildTypes` |
| `build-lock.json` | Toolchain, upstream and data-source versions with download hashes | `toolchain` |
| `.github/workflows/build.yml` | CI: documentation check, Gradle check and build, signed release APK; GitHub Release on `v*` tags | `android`, `release` |
