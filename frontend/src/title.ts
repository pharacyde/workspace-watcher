/**
 * The tab title, composed in one place.
 *
 * <p>Two things have something to say in it: which workspace this tab is watching, and how many
 * notable events arrived while it was hidden. Both used to assign `document.title` directly, which
 * only works while there is one of them - the second writer erases the first one's half. So the
 * two facts are set here and the string is built from whatever is currently true.
 */

/**
 * Short on purpose. A tab strip gives a title a dozen characters before it truncates, and spending
 * them on the name of the app - which is the same in every one of these tabs - leaves nothing for
 * the workspace, which is the part that tells them apart. No logo either: the tab already carries
 * the eye as its icon, right next to this, and drawn twice it reads as two things.
 */
const APP = 'WW';

let workspace = '';
let missed = 0;

function paint() {
  const badge = missed > 0 ? `(${missed}) ` : '';
  document.title = `${badge}${APP}${workspace ? ` ${workspace}` : ''}`;
}

/**
 * Names the workspace this tab is watching, by its last path segment.
 *
 * <p>The folder name rather than the whole path: with several watchers open on several projects the
 * leading directories are the part they have in common - exactly the part that cannot tell them
 * apart.
 */
export function titleWorkspace(path: string | null) {
  const name = path?.split('/').filter(Boolean).pop() ?? '';
  if (name === workspace) return;
  workspace = name;
  paint();
}

/** How many notable events arrived while the tab was hidden; 0 clears the badge. */
export function titleMissed(count: number) {
  if (count === missed) return;
  missed = count;
  paint();
}
