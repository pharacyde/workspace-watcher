import { defineConfig, devices } from '@playwright/test';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

/**
 * The browser test that the unit tests cannot be.
 *
 * <p>Every bug this suite exists for was a browser bug: a virtualizer that scrolls its ancestor
 * unless told otherwise, a scroll handler that could not tell whose scroll it was, an editor
 * disposed in the wrong order, a <pre> that grows instead of scrolling. None of them are visible
 * to a unit test and all of them were found by hand.
 */

const port = Number(process.env.WW_E2E_PORT ?? 18099);

/**
 * A workspace of its own, and a Claude home of its own.
 *
 * <p>Nothing here may touch the developer's real ~/.claude — the Java tests hold the same line.
 * That takes four overrides, not three: the spool has its own default under ~/.claude and does not
 * follow claude-home, and the hook drain deletes what it finds there.
 */
/*
 * Decided once and passed down through the environment, not computed here.
 *
 * This file is evaluated in the runner *and* again in every worker process, so anything random in
 * it answers differently per process. Calling mkdtemp directly produced two directories: the runner
 * started the watcher on one and the test wrote its files into the other, and the feed then stayed
 * empty for a reason no assertion could describe. Workers inherit the runner's environment, so
 * writing the answer there is what makes the two agree.
 */
const root = process.env.WW_E2E_ROOT ?? mkdtempSync(join(tmpdir(), 'ww-e2e-'));
process.env.WW_E2E_ROOT = root;

const workspace = join(root, 'workspace');
const claudeHome = join(root, 'claude');
// A fixture for the usage header: a five-hour window that has rolled over and a live weekly one,
// which is the shape the real file has and the one the panel has to describe honestly.
if (!existsSync(join(root, 'claude.json'))) {
  writeFileSync(
    join(root, 'claude.json'),
    JSON.stringify({
      oauthAccount: { organizationRateLimitTier: 'e2e_fixture' },
      cachedUsageUtilization: {
        fetchedAtMs: Date.now() - 3_600_000,
        utilization: {
          limits: [
            {
              kind: 'session',
              group: 'session',
              percent: 7,
              severity: 'normal',
              resets_at: new Date(Date.now() - 3_600_000).toISOString(),
              is_active: false,
            },
            {
              kind: 'weekly_all',
              group: 'weekly',
              percent: 49,
              severity: 'normal',
              resets_at: new Date(Date.now() + 86_400_000).toISOString(),
              is_active: true,
            },
          ],
        },
      },
    }),
  );
}

for (const dir of [workspace, join(claudeHome, 'projects'), join(root, 'state')]) {
  mkdirSync(dir, { recursive: true });
}
process.env.WW_WORKSPACE = workspace;

// fileURLToPath rather than import.meta.dirname: the latter needs a recent node, and this config is
// the one file that has to load before anything can tell you why it did not.
const repo = resolve(fileURLToPath(new URL('.', import.meta.url)), '..');
/**
 * The packaged jar, found rather than named.
 *
 * <p>A hardcoded version stops matching the day someone edits pom.xml, and the only symptom is
 * "was not able to start" - the same unhelpful failure findJava below was written to avoid.
 */
function findJar(): string {
  const target = join(repo, 'target');
  const jars = existsSync(target)
    ? readdirSync(target).filter((name) => name.endsWith('.jar') && !name.endsWith('.original.jar'))
    : [];
  if (jars.length !== 1) {
    throw new Error(
      `Expected exactly one jar in ${target}, found ${jars.length ? jars.join(', ') : 'none'}. ` +
        'Run `mvn -DskipTests package` first: the test drives whatever bundle the jar contains.',
    );
  }
  return join(target, jars[0]);
}

const jar = findJar();
/** The lowest JDK the jar's class files can run on. */
const MINIMUM_JDK = 25;

/**
 * The major version a java reports, or 0 if it will not say.
 *
 * <p>Asked rather than assumed. JAVA_HOME on this very machine points at 21 while a 26 sits beside
 * it, so trusting the variable produces `UnsupportedClassVersionError: class file version 69.0`
 * from a process the test runner only describes as "was not able to start" - a confusing half hour
 * for a problem with a one-line answer.
 */
function majorVersion(java: string): number {
  try {
    // --version, not -version: the one with a single dash writes to stderr and has done for twenty
    // years, and execFileSync hands back stdout - so the older spelling reads as an empty string
    // and every JDK looks too old. The two-dash form has printed to stdout since JDK 9.
    const output = execFileSync(java, ['--version'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    });
    return Number(/(\d+)/.exec(output)?.[1] ?? 0);
  } catch {
    return 0;
  }
}

/** The first java on offer that is new enough, checked rather than taken on trust. */
function findJava(): string {
  const candidates = [];
  if (process.env.JAVA_HOME) {
    candidates.push(join(process.env.JAVA_HOME, 'bin', 'java'));
  }
  if (process.platform === 'darwin') {
    try {
      const home = execFileSync('/usr/libexec/java_home', ['-v', `${MINIMUM_JDK}+`], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
      }).trim();
      if (home) {
        candidates.push(join(home, 'bin', 'java'));
      }
    } catch {
      // No JDK that new is installed; the plain java below will say so.
    }
  }
  candidates.push('java');

  for (const candidate of candidates) {
    if (majorVersion(candidate) >= MINIMUM_JDK) {
      return candidate;
    }
  }
  throw new Error(
    `No JDK ${MINIMUM_JDK} or newer found. Tried: ${candidates.join(', ')}. ` +
      'Install one or point JAVA_HOME at it; the jar cannot run on anything older.',
  );
}

const java = findJava();

export default defineConfig({
  testDir: './tests',
  // Serially, on one worker: the tests share one running watcher and one workspace directory, and
  // a second worker writing files into it would show up in the first one's feed.
  workers: 1,
  fullyParallel: false,
  // The feed is driven by a filesystem scan that paces itself, so a first assertion can wait
  // seconds for something a unit test would see immediately.
  timeout: 90_000,
  expect: { timeout: 20_000 },
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['html', { open: 'never' }], ['list']] : [['list']],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'retain-on-failure',
    ...devices['Desktop Chrome'],
  },
  webServer: {
    // No keystore, so it serves plain http: a self-signed certificate would only add a trust
    // dialog to a test that has nothing to say about TLS.
    // Quoted: the command is run through a shell, and both the temporary directory and someone's
    // checkout are allowed to contain a space.
    command: [
      java,
      '-jar',
      jar,
      `--server.port=${port}`,
      `--watcher.workspace=${workspace}`,
      `--watcher.claude-home=${claudeHome}`,
      `--watcher.database=${join(root, 'state', 'events.db')}`,
      `--watcher.keystore=${join(root, 'absent.p12')}`,
      // The spool defaults to ~/.claude/workspace-watcher-spool and does not follow claude-home,
      // so without this the test watcher reads - and drains, deleting files - the real one. The
      // register is filled from the spool, not from claude-home, so this is also what actually
      // keeps a real project from being adopted mid-test.
      `--watcher.spool=${join(root, 'spool')}`,
      // Same reason, one file over: the billing mode and the cached limit usage are read from
      // ~/.claude.json, which is not under claude-home either. Pointed at a fixture so the suite
      // renders known numbers instead of the developer's actual subscription usage.
      `--watcher.claude-config=${join(root, 'claude.json')}`,
    ]
      .map((part) => `"${part}"`)
      .join(' '),
    url: `http://127.0.0.1:${port}/`,
    // Never reuse: a watcher left running from a manual session is pointed at a real project, and
    // the test would then assert against someone's actual work.
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
