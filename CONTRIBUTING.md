# Contributing to JDesk Framework

Thank you for your interest in contributing.

## How this repository is maintained

All development happens in this repository. Its history starts with a single import commit, so changes made before the framework was published are not visible here.

## Contributor License Agreement

Before your first pull request can be merged, you need to sign the [Contributor License Agreement](CLA.md). To sign, post this comment on your first pull request:

```text
I have read the CLA Document and I hereby sign the CLA
```

You only need to sign once. The maintainer checks for the comment before merging and keeps a record of it. The CLA lets the maintainer keep distributing the project under the Apache License 2.0 and, if needed, under other terms. You keep the copyright to your contributions.

## Development setup

1. Install JetBrains Runtime 17 with JCEF (a `jbrsdk_jcef` 17 build) and point `JAVA_HOME` to it.
2. Install Maven 3.9 or newer.
3. Build and run the tests:

   ```bash
   mvn -B verify
   ```

Windows x64 is the primary platform. If you work on another operating system, please say so in your pull request.

## Pull requests

- Keep each pull request focused on one change, and describe what it changes and how you verified it.
- Add or update tests when behavior changes.
- Follow the style of the surrounding code. Comments say what the code does and point out pitfalls; design rationale belongs in `docs/`.
- Write comments, log messages and commit messages in English. The design notes under `docs/` are currently in Chinese; either language is fine for changes to them.
- `mvn -B verify` must pass. CI runs it on Windows.
- Write commit messages as `type(scope): summary`, for example `fix(vfs): keep file identity across renames`.

## Bugs and feature requests

Please use GitHub Issues. For security problems, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.
