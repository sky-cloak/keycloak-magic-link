# Contributing to Keycloak Magic Link

Thanks for your interest in improving Keycloak Magic Link.

## Build

```bash
mvn package                              # build + unit tests
mvn -Dkeycloak.version=25.0.6 package    # build against a specific Keycloak version
```

JDK 21 is used to build; the jar targets bytecode 17 and runs on Keycloak 24, 25, and 26.

## Integration test

```bash
ci/integration-test.sh 26.0.7            # boots a real Keycloak with the jar and verifies behavior
```

Requires Docker. CI runs the build plus this integration test against Keycloak 24.0.5,
25.0.6, and 26.0.7 on every push.

## Pull requests

- Keep changes focused: one concern per PR.
- Add or update tests for any behavior change.
- Use conventional-commit messages (`feat`, `fix`, `docs`, `chore`, ...).
- Make sure `mvn package` and the integration test pass before opening the PR.

## Reporting bugs

Open an issue with your Keycloak version, the extension version, and steps to reproduce.
For security issues, see [SECURITY.md](SECURITY.md) instead.
