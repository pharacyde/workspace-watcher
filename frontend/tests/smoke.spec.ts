import { expect, test, type Page } from '@playwright/test';
import { execFile } from 'node:child_process';
import { appendFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { promisify } from 'node:util';

const run = promisify(execFile);

/**
 * The temporary directory the watcher under test is observing. Writing into it is the only way to
 * produce events from the outside: this suite drives the UI, never the backend.
 */
const WORKSPACE = process.env.WW_WORKSPACE ?? '';

/**
 * How long to allow for something the file layer has to notice first.
 *
 * <p>The scanner derives its own interval from how long the walk took, at a tenth duty cycle, so
 * there is no interval to read and wait for exactly - only an upper bound that is generous enough
 * to survive a loaded CI machine. Every wait below is a poll against a condition, never a sleep;
 * this is only the point at which the suite gives up.
 */
const SCAN_TIMEOUT = 30_000;

/** Rows written per batch. Well under maxFileEventsPerScan (200), above which the scanner
 * collapses everything into one BULK row and the feed would gain a single line instead of many. */
const BATCH = 40;

/**
 * Console output that is noise rather than a defect.
 *
 * <p>graphql-ws connects lazily and retries forever, so the first attempt can land before the
 * endpoint is answering and Chromium logs the failed socket as a console error; the client
 * reconnects on its own and the header pill goes back to "live". Nothing else is filtered - a
 * silenced console is the reason bugs like these were only ever found by hand.
 *
 * <p>The favicon is not a resource the app ships; Chromium requests it unprompted and logs the 404.
 */
const IGNORED_CONSOLE = [
  /websocket/i,
  /subscription failed/i,
  /favicon\.ico/i,
];

/**
 * Serial, and one page for the whole file.
 *
 * <p>Two reasons, both about what is being tested. The console assertion at the end has to cover
 * everything the earlier tests did, which means they must share a page. And the feed only becomes
 * scrollable once enough events have accumulated, which takes several scan intervals - paying that
 * once and building on it is the difference between a suite that runs in half a minute and one that
 * runs in several.
 */
test.describe.configure({ mode: 'serial' });

test.describe('workspace-watcher dashboard', () => {
  let page: Page;
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];

  /** Reads the feed's own event counter, which counts every event and not just the rows the
   * virtualizer has currently laid out. */
  async function feedCount(): Promise<number> {
    const text = (await page.locator('ww-feed h2 .count').textContent()) ?? '0';
    return Number(text.replace(/\D/g, ''));
  }

  /** scrollHeight, scrollTop and clientHeight of the feed's scroll container in one read, so the
   * three cannot be measured across different frames. */
  async function feedMetrics() {
    return page.locator('ww-feed lit-virtualizer').evaluate((element) => ({
      scrollHeight: element.scrollHeight,
      scrollTop: element.scrollTop,
      clientHeight: element.clientHeight,
    }));
  }

  /** How far the feed is from the bottom. This is the number the follow bug moved: an earlier
   * version left it at the full height of everything that had arrived since. */
  async function tailGap(): Promise<number> {
    const { scrollHeight, scrollTop, clientHeight } = await feedMetrics();
    return scrollHeight - scrollTop - clientHeight;
  }

  /** The three buttons in the feed header, in render order. Their labels change with their state,
   * so they cannot be located by text; the labels are asserted instead, which fails loudly if the
   * order ever changes. */
  function feedButton(which: 'follow' | 'wrap' | 'pause') {
    return page.locator('ww-feed h2 button').nth({ follow: 0, wrap: 1, pause: 2 }[which]);
  }

  async function buttonLabel(which: 'follow' | 'wrap' | 'pause'): Promise<string> {
    return ((await feedButton(which).textContent()) ?? '').trim();
  }

  async function setFollow(on: boolean) {
    const label = await buttonLabel('follow');
    expect(label).toMatch(/⤓ follow/);
    if ((label === '⤓ follow') !== on) {
      await feedButton('follow').click();
    }
    expect(await buttonLabel('follow')).toBe(on ? '⤓ follow' : '⤓ follow off');
  }

  async function setWrap(on: boolean) {
    const label = await buttonLabel('wrap');
    expect(label).toMatch(/⏎ wrap/);
    if ((label === '⏎ wrap on') !== on) {
      await feedButton('wrap').click();
    }
    expect(await buttonLabel('wrap')).toBe(on ? '⏎ wrap on' : '⏎ wrap off');
  }

  /** Types into the header search box, which filters every panel at once. Used to make one
   * specific row reachable: the feed is virtualised, so a row that is scrolled away is not in the
   * DOM at all and cannot be clicked. */
  async function search(term: string) {
    const box = page.locator('ww-app input[type="search"]');
    await box.fill(term);
  }

  /** Writes a batch of distinct files, so the feed gains one row per file rather than one
   * collapsed row: consecutive identical events are folded together by design. */
  async function writeBatch(prefix: string, count = BATCH) {
    for (let index = 0; index < count; index++) {
      await writeFile(join(WORKSPACE, `${prefix}-${index}.txt`), `${prefix} ${index}\n`);
    }
  }

  /** Waits for the feed to have gained events, then for it to settle. Every assertion here has to
   * tolerate the UI batching a burst of events onto a single animation frame. */
  async function waitForNewEvents(before: number) {
    await expect
      .poll(feedCount, { timeout: SCAN_TIMEOUT, message: 'the feed never saw the new files' })
      .toBeGreaterThan(before);
  }

  test.beforeAll(async ({ browser }) => {
    expect(WORKSPACE, 'WW_WORKSPACE must name the directory the watcher is observing').not.toBe('');

    // A git repository, so the working tree panel has something to list and the diff panel has two
    // sides to show. The scanner ignores .git, and GitService re-reads the repository on any scan
    // that found a change, so creating it after the watcher started is fine.
    await run('git', ['init', '-q'], { cwd: WORKSPACE });
    await writeFile(join(WORKSPACE, 'tracked.txt'), 'committed line\n');
    await run('git', ['add', 'tracked.txt'], { cwd: WORKSPACE });
    await run(
      'git',
      // Configured on the command line rather than in the repository: the machine running this may
      // have no global identity, and a failing commit would look like a UI bug.
      [
        '-c',
        'user.name=smoke',
        '-c',
        'user.email=smoke@example.invalid',
        'commit',
        '-q',
        '-m',
        'initial',
      ],
      { cwd: WORKSPACE },
    );

    page = await browser.newPage();
    page.on('console', (message) => {
      if (message.type() !== 'error') return;
      const text = message.text();
      if (IGNORED_CONSOLE.some((pattern) => pattern.test(text))) return;
      consoleErrors.push(text);
    });
    page.on('pageerror', (error) => pageErrors.push(`${error.name}: ${error.message}`));

    await page.goto('/');
  });

  test.afterAll(async () => {
    await page?.close();
  });

  test('every panel is on the page', async () => {
    for (const panel of [
      'ww-timeline',
      'ww-process-panel',
      'ww-feed',
      'ww-git-panel',
      'ww-diff-panel',
    ]) {
      await expect(page.locator(panel), `${panel} should be rendered`).toBeVisible();
    }
    // The header pill is the app's own statement that the socket is up. Anything else here means
    // the panels are decoration over a dead connection.
    await expect(page.locator('ww-app .pill.live')).toHaveText('live', { timeout: SCAN_TIMEOUT });
  });

  test('writing files fills the activity feed', async () => {
    const before = await feedCount();
    await writeBatch('alpha');
    await waitForNewEvents(before);
    await expect(page.locator('ww-feed .rowline').first()).toBeVisible();
  });

  test('following keeps the feed pinned to the newest row', async () => {
    await setFollow(true);

    // Without this the whole test passes vacuously: a list shorter than its viewport has a gap of
    // zero whether following works or not. Enough rows have to arrive for it to actually scroll.
    await expect
      .poll(
        async () => {
          const { scrollHeight, clientHeight } = await feedMetrics();
          return scrollHeight - clientHeight;
        },
        { timeout: SCAN_TIMEOUT, message: 'the feed never grew past its viewport' },
      )
      .toBeGreaterThan(200);

    // The regression: an earlier version turned following off the moment a single row arrived,
    // because it measured a layout the virtualizer was still growing.
    for (const prefix of ['beta', 'gamma']) {
      const before = await feedCount();
      await writeBatch(prefix);
      await waitForNewEvents(before);
      await expect
        .poll(tailGap, { timeout: SCAN_TIMEOUT, message: `the feed stopped following after ${prefix}` })
        .toBeLessThan(8);
      expect(await buttonLabel('follow')).toBe('⤓ follow');
    }
  });

  test('toggling wrap does not break following', async () => {
    await setFollow(true);

    // Wrap changes the height of every row at once, which is what caught the follow logic out
    // before: it scrolled to a height that was stale by the time the rows had been re-measured,
    // and the feed sat 940 px short of the end.
    for (const wrapped of [true, false]) {
      await setWrap(wrapped);
      const before = await feedCount();
      await writeBatch(wrapped ? 'delta' : 'epsilon');
      await waitForNewEvents(before);
      await expect
        .poll(tailGap, {
          timeout: SCAN_TIMEOUT,
          message: `the feed stopped following with wrap ${wrapped ? 'on' : 'off'}`,
        })
        .toBeLessThan(8);
    }
  });

  test('switching between a feed row and a working-tree file repeatedly', async () => {
    // Following would keep moving rows out from under the click, and the row wanted here is not
    // the newest one anyway.
    await setFollow(false);
    await appendFile(join(WORKSPACE, 'tracked.txt'), 'changed by the smoke test\n');

    // The feed is virtualised: a row that is not on screen is not in the DOM. Filtering to the one
    // file makes both its feed row and its working-tree row the only candidates.
    await search('tracked.txt');

    const feedRow = page.locator('ww-feed .rowline').first();
    const treeRow = page.locator('ww-git-panel .rowline').filter({ hasText: 'tracked.txt' });
    await expect(feedRow).toBeVisible({ timeout: SCAN_TIMEOUT });
    await expect(treeRow).toBeVisible({ timeout: SCAN_TIMEOUT });

    const panel = page.locator('ww-diff-panel');

    // Four round trips. Monaco's "TextModel got disposed before DiffEditorWidget model got reset"
    // did not throw on the first switch, and the empty-panel variant needed the container to have
    // been replaced first - both only appeared after going back and forth a few times.
    for (let round = 0; round < 4; round++) {
      await treeRow.click();
      await expect(panel.locator('h2')).toContainText('Diff');
      // Monaco is fetched on the first click of a file, so the first round pays for the chunk.
      await expect(panel.locator('.monaco-diff-editor')).toBeVisible({ timeout: SCAN_TIMEOUT });
      // The editor being present is not enough: the bug this covers left a mounted editor attached
      // to a detached container, which renders no lines at all.
      await expect(panel.locator('.view-lines').first()).toBeVisible();

      await feedRow.click();
      await expect(panel.locator('h2')).toContainText('File');
      await expect(panel.locator('pre.content')).toBeVisible();
      await expect(panel.locator('.monaco-diff-editor')).toHaveCount(0);
    }

    await search('');
  });

  test('a row with a path shows the file, and the file keeps growing', async () => {
    await setFollow(false);
    const before = await feedCount();
    await writeFile(join(WORKSPACE, 'growing.log'), 'line 0\n');
    await waitForNewEvents(before);

    await search('growing.log');
    const row = page.locator('ww-feed .rowline').first();
    await expect(row).toBeVisible({ timeout: SCAN_TIMEOUT });
    await row.click();

    const content = page.locator('ww-diff-panel pre.content');
    await expect(content).toContainText('line 0');

    // The panel follows the file over its own subscription rather than over the feed, so this is a
    // second path to the same file and worth its own assertion: appended lines have to appear
    // without another click.
    for (let line = 1; line <= 20; line++) {
      await appendFile(join(WORKSPACE, 'growing.log'), `line ${line}\n`);
    }
    await expect(content).toContainText('line 20', { timeout: SCAN_TIMEOUT });

    await search('');
  });

  test('nothing was logged to the console and nothing threw', async () => {
    expect(pageErrors, 'unhandled page errors').toEqual([]);
    expect(consoleErrors, 'console errors').toEqual([]);
  });
});
