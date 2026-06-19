# Contributing to Keycloak Magic Link

Thanks for your interest in improving Keycloak Magic Link.

## Build

```bash
mvn package                              # build + unit tests
mvn -Dkeycloak.version=25.0.6 package    # build against a specific Keycloak version
```

JDK 21 is used to build; the jar targets bytecode 17 and runs on Keycloak 25 and 26.

## Integration tests

```bash
ci/magic-link-authenticator-test.sh 26.6.3   # Mode A login-flow end-to-end (real Keycloak + MailHog)
ci/magic-link-admin-test.sh 26.6.3           # Mode B admin API (issue / revoke / send / two-step consume)
ci/magic-link-crosspath-test.sh 26.6.3       # same-device bypass regression
```

Requires Docker. CI runs the build plus the full test suite against Keycloak 25.0.6,
26.0.7, and 26.6.3 on every push.

## Pull requests

- Keep changes focused: one concern per PR.
- Add or update tests for any behavior change.
- Use conventional-commit messages (`feat`, `fix`, `docs`, `chore`, ...).
- Make sure `mvn package` and the integration test pass before opening the PR.

## Reporting bugs

Open an issue with your Keycloak version, the extension version, and steps to reproduce.
For security issues, see [SECURITY.md](SECURITY.md) instead.
