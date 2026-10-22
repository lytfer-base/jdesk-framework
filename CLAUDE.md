# JDesk Framework — working notes for Claude Code

JDesk Framework is a Java 17 / Swing desktop application framework (Maven multi-module) with an embedded JCEF WebView. Modules: `jdesk-framework-core` (kernel; no Swing except `com.jdesk.concurrency`), `jdesk-jcef-api` (build-time JCEF classes only), `jdesk-framework-frame` (Swing layer, WebView, storage, VFS, history, ACP/MCP), `jdesk-framework-demo` (demo app and the `demo-greeter` plugin). Design notes live in `docs/` (Chinese).

## Build and verify

- `JAVA_HOME` must be a JetBrains Runtime 17 **with JCEF** (`jbrsdk_jcef`); `jdesk-jcef-api` reads `jmods/jcef.jmod` and fails otherwise.
- `mvn -B verify` builds and tests everything. Tests run with `-Djdesk.locale=zh-CN` (set in the root pom), so i18n assertions don't depend on the machine's language.
- Pick the verification scope from what actually changed; run the affected tests at least. Socket-based tests (MCP, loopback resource server) need a normal desktop session.

## Boundaries

- This is a general-purpose framework. No product concepts, product names or product UI text in framework code, tests or docs.
- Public APIs must not expose backend SDK types (`org.cef.*`). A JxBrowser backend may exist outside this repository; the framework only loads it by class name, so never reference its types.
- Reuse existing framework mechanisms before adding new ones; keep new public API small.

## Code comments

- Comments are written in **English** and say only two things: **what** the code is and does (class or file header 1–3 sentences; public method 1 sentence, plus return value, null, exceptions or threading when needed), and **pitfalls** (one imperative line: "Don't X", "Must Y", "If you change A, check B", optionally with one `{@link}` or test name).
- **Why** a design was chosen belongs in `docs/`, not in comments. Never write dates, decision history, who decided, caller lists, counts or "only/unique" claims, disproven notes or comments about other comments.
- Limits: class header ≤ 5 lines, method ≤ 3 lines, inline ≤ 1 line. Private methods and self-explanatory code get no comment.
- `HanLiteralGuardTest` fails on CJK text in any comment, in main and test sources alike.

## User-facing text and logs

- User-facing text goes through the i18n bundles (`jdesk/i18n/frame_*.properties`, `I18n`, `LocalizedException`); never hard-code it. `HanLiteralGuardTest` fails on new CJK text outside comments; deliberate exceptions are listed with a reason in `jdesk-framework-frame/src/test/resources/arch/han-literal-exceptions.txt`. `FrameCatalogConsistencyTest` keeps `frame_en`, `frame_zh_CN` and `frame_ja` in step: same keys, same placeholders, valid message syntax.
- Log messages are English and never contain user content (titles, bodies, search terms, prompts).

## Threading and lifecycle

- Update Swing only on the EDT through the existing dispatch utilities; keep slow work off the EDT.
- Resources with their own lifecycle or external registrations implement `Disposable`, are registered under an owner with `Disposer.register`, and are released through `Disposer.dispose`. Pair every listener registration with its removal; background tasks end with their owner; caches stay bounded; release WebViews explicitly. See `docs/framework/disposer.md` and `docs/framework/memory-management.md`.
- On cancellable threads, long reads, copies and walks use `Cancellation` / `InterruptibleFiles` (`com.jdesk.core.io`) instead of `Files.copy`, `readAllBytes` or `walkFileTree`; let `Cancellation.isCancellation` through "degrade on failure" catches; wrap a multi-step disk or database change in `Cancellation.uninterruptibly` from its first write. See `docs/framework/cancellation.md`.
- For persistence, async work and window closing, check the failure, re-entry and release-order paths.

## Tests

- Test behavior and contracts, not literal implementation details; a test earns its place if a legitimate change would not force it to change.
- When a test guards against a regression, check it with a mutation probe: break the code on purpose and make sure the test fails.

## Commits

- Commit messages follow `type(scope): summary` (for example `fix(vfs): keep file identity across renames`) and are written in English.

## Branches and releases

- Work and commit on `dev`, never directly on `main`. Check the current branch before you start.
- `main` moves only at release time, by fast-forwarding it to `dev`. CI runs on pushes to `main` and on pull requests, not on `dev`. A ruleset protects `main`: no force pushes, no deletion, and the `build` check must pass.
- Don't push; the maintainer does. Only `main` and tags are pushed, never `git push --all` or `--mirror`.
- To release `X.Y.Z`:
  1. On `dev`, set the version to `X.Y.Z` in the root pom, in every module's `<parent>` and in the demo's `META-INF/application-info.xml`, then commit.
  2. Run `git switch main`, `git merge --ff-only dev` and `git switch dev`. The maintainer then pushes `main`.
  3. Once CI passes on that commit, tag it with `git tag -a vX.Y.Z main -m "JDesk Framework X.Y.Z"`. The maintainer pushes the tag. No GitHub Release is published.
  4. On `dev`, set the next `-SNAPSHOT` version in the root pom and in every module's `<parent>`.
