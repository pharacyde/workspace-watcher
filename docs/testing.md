# Tests and CI

What CI runs, and the rules that decide whether a run means anything. The last two are lessons:
they are why a green run once measured the wrong bundle.

`mvn verify` runs what CI runs: Spotless, the tests, the frontend build, and the jar. CI
(`.github/workflows/ci.yml`) builds on JDK 25 and 27 — 27 is what development happens on, 25 is the
LTS the build targets and the minimum the README promises — and runs the tests on macOS as well,
because that is the platform the process layer is written against and a green Linux build says
less here than it usually would. Standard runners are free on public repositories.

`mvn test`. 194 tests, deliberately aimed at the parsers and at the failure modes this project has
actually hit rather than at a coverage number. `GitServiceTest.resolvesVersionsFromASubdirectoryWorkspace`
is the regression test for the worst bug so far and should not be deleted.

Fixtures are built in `@TempDir`, including real git repositories. Nothing touches the developer's
own `~/.claude`.

**A fixture command that fails must throw, not return.** `Shell.run` reports a failed command as a
result rather than an exception, so `GitServiceTest.run` checks the exit code and throws with the
command and its output; before that, a failed fixture surfaced three lines later as an assertion
that said nothing about why. Measured on the Linux CI runner: a commit inside the clone that
`git submodule add` makes fails there, because the runner has no global git identity and the one
set on the source repository stayed in that repository's config - so
`GitServiceTest.addSubmodule` sets an identity on the clone as well, and the test that commits
inside the submodule had been measuring a commit that never happened.

One Playwright spec sits beside those, not among them - twelve tests in
`frontend/tests/smoke.spec.ts`, run with `cd frontend && npm run test:e2e`. It starts the app from `target/workspace-watcher-0.1.0-SNAPSHOT.jar` over plain HTTP and drives
Chromium against it, which is the only check that the bundle Vite produced actually boots in a
browser. Everything above it passes on a bundle that throws on load — a bad module specifier, a
stylesheet that never reaches the shadow root, a chunk that fails its dynamic import — because none
of the Java tests ever execute the JavaScript.

**Build the frontend before running it.** The jar has to be the current one: asset names carry a
content hash and `emptyOutDir` removes the old ones, so a jar built before your change serves a
different bundle than the one you are testing, and the test measures the previous version while
looking green. `mvn -DskipTests package` first, or the run means nothing. CI takes the jar the
JDK 27 build uploaded for exactly that reason, and runs the browser test once on one runner rather
than in every matrix combination — the bundle does not vary by JDK, and the browser download does
not need paying for twice.

**Token-shaped fixtures are assembled, not written out.** GitHub's push protection reads a test file
the way `SensitiveContentScanner` reads a payload: `"AKIA" + "…"` and `"xoxb-" + "…"` in
`SensitiveContentScannerTest` exist because the literal forms had the push to `main` refused.
