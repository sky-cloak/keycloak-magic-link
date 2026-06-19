# Versioning

This extension follows [Semantic Versioning](https://semver.org) with one local convention for the
pre-1.0 phase.

## Current phase: pre-1.0 (MVP and iteration)

While the major version is `0`, we are explicitly in MVP and iteration territory.

- `0.MINOR.PATCH`
- **MINOR** bumps land new features or behavior changes (including occasional breaking changes).
- **PATCH** bumps are bugfixes and documentation updates.
- Breaking changes are allowed without a major bump until `1.0.0` ships. Any breaking change is
  called out at the top of the release notes.

We will cut `1.0.0` when the extension is feature-stable for our roadmap, has been certified against
the target integration (e.g. Okta/Entra for SCIM), and has been running unsupervised in production
for at least one full minor cycle.

## Post-1.0: full Semantic Versioning

Once `1.0.0` ships:

- **MAJOR** for backwards-incompatible API/config/wire changes.
- **MINOR** for new, backwards-compatible features.
- **PATCH** for backwards-compatible bugfixes.
- Deprecations are marked in release notes and stay in place for at least one additional MINOR
  release before removal.

## Pre-releases

Release candidates and previews follow the pattern `v0.2.0-rc.1`, `v0.2.0-beta.1`, `v0.2.0-alpha.1`.
The release workflow detects the dash and marks the GitHub release as a *Prerelease* so it does not
show up as the "latest" version.

## Keycloak compatibility

Every release supports **Keycloak 25 and 26**. The CI matrix builds and integration-tests against
25.0.6, 26.0.7, and the current 26.6.x. Keycloak 24 was dropped at v0.3.0 (it is past upstream
maintenance, and the action-token rebuild is cleaner without straddling it). Keycloak 27 will be
added to the matrix when it ships; we keep at least the two most recent supported majors.

The release artifact is built against Keycloak 26.0.7, whose SPI surface is compatible with the 25
and 26.6 targets for everything this extension uses.

## Release process

1. Bump the version in `pom.xml` (`<version>X.Y.Z</version>`).
2. Update `CHANGELOG.md` (when present) with the new version's notes.
3. Commit on `main` with a conventional message: `chore(release): vX.Y.Z`.
4. Tag the release: `git tag vX.Y.Z && git push origin vX.Y.Z`.
5. The `Release` workflow validates that the tag matches `pom.xml`, builds the JAR, attaches it to
   a GitHub release, and writes release notes from the commit log since the previous tag.

Tags that do not match the pattern `v*.*.*` are ignored. A tag whose version does not match
`pom.xml` will fail the workflow before publishing - no half-baked releases.
