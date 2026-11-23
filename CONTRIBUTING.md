# Contributing to Wearsic

Thanks for helping improve Wearsic! This project is a Wear OS app plus a
self-hosted server, and contributions of all kinds are welcome.

## Code of conduct

All participants must follow our [Code of Conduct](CODE_OF_CONDUCT.md).

## How contributions work

1. **Fork** the repository.
2. Create a branch with a descriptive name (`fix/…`, `feat/…`,
   `docs/…`).
3. Make your change with focused commits.
4. Run the tests (below).
5. Open a pull request against `main` describing what you changed and why.

## Development setup

- **App**: Android Studio with JDK 21+. Build with `./gradlew :app:assembleDebug`.
- **Server**: Kotlin/Ktor. Build with `./gradlew :wearsic-server:installDist`.

## Testing

- App unit tests: `./gradlew :app:testDebugUnitTest`
- Server tests: `./gradlew :wearsic-server:test`
- Full suite: `./gradlew test`

PRs must keep the test suite green.

## Areas you can help with

- **UI/UX**: Wear OS Compose screens, animations, accessibility.
- **Server reliability**: extraction performance, retries, caching.
- **Docs**: clarifying `README.md`, API docs, and guides.
- **Localization**: translations for strings.
- **Bug reports**: open an issue with steps to reproduce, the app version,
  and the server version.

## Style guide

- Kotlin: follow the official Kotlin style; keep changes focused.
- Commit messages: short imperative summary, optional detail below.
- Keep PRs small and reviewable.

## Questions

Open a discussion or issue on GitHub before starting large changes, so we
can agree on the direction first.
