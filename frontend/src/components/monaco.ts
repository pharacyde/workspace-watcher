import type * as Monaco from 'monaco-editor/editor/editor.api.js';
import { chunkLoadedSuccessfully } from './reload';

export type DiffEditor = Monaco.editor.IStandaloneDiffEditor;

const EXTENSION_LANGUAGE: Record<string, string> = {
  java: 'java',
  ts: 'typescript',
  tsx: 'typescript',
  js: 'javascript',
  jsx: 'javascript',
  css: 'css',
  html: 'html',
  xml: 'xml',
  yml: 'yaml',
  yaml: 'yaml',
  md: 'markdown',
  sh: 'shell',
  py: 'python',
  sql: 'sql',
};

export function languageFor(path: string): string {
  return EXTENSION_LANGUAGE[path.split('.').pop() ?? ''] ?? 'plaintext';
}

let loading: Promise<typeof Monaco> | null = null;
let styles: Promise<CSSStyleSheet> | null = null;

/**
 * Monaco's stylesheet, packaged for adoption into a shadow root.
 *
 * <p>Vite bundles the CSS that Monaco's modules import into the document stylesheet. A shadow root
 * does not see document styles, so an editor mounted inside one renders unstyled: no layout, no
 * colours, and a panel that grows to its full content height and blows out the grid around it.
 *
 * <p>Rather than give up encapsulation for this one component, the document rules are copied into
 * a constructable stylesheet the component adopts. Same isolation as every other panel, and Monaco
 * gets the CSS it expects.
 */
export function monacoStyleSheet(): Promise<CSSStyleSheet> {
  styles ??= (async () => {
    const text = [...document.styleSheets]
      .flatMap((sheet) => {
        try {
          return [...sheet.cssRules].map((rule) => rule.cssText);
        } catch {
          // Cross-origin sheet; nothing of ours lives there.
          return [];
        }
      })
      .join('\n');
    const sheet = new CSSStyleSheet();
    await sheet.replace(text);
    return sheet;
  })();
  return styles;
}

/**
 * Loads Monaco on first use and keeps it.
 *
 * <p>Dynamic import so the bundler splits it into its own chunk. Monaco is a few megabytes; making
 * every page load pay for it, when the diff panel is only reached by clicking a file, is a poor
 * trade - measured, it is the difference between a 237 kB and a 2.9 MB entry bundle.
 *
 * <p>Languages are registered one by one rather than importing the monaco-editor root, which ships
 * every language it knows: 4.1 MB against 2.9 MB for this set.
 */
export async function loadMonaco(): Promise<typeof Monaco> {
  loading ??= (async () => {
    const [monaco, { default: EditorWorker }] = await Promise.all([
      import('monaco-editor/editor/editor.api.js'),
      import('monaco-editor/editor/editor.worker.js?worker'),
      import('monaco-editor/languages/definitions/css/register.js'),
      import('monaco-editor/languages/definitions/html/register.js'),
      import('monaco-editor/languages/definitions/java/register.js'),
      import('monaco-editor/languages/definitions/javascript/register.js'),
      import('monaco-editor/languages/definitions/markdown/register.js'),
      import('monaco-editor/languages/definitions/python/register.js'),
      import('monaco-editor/languages/definitions/shell/register.js'),
      import('monaco-editor/languages/definitions/sql/register.js'),
      import('monaco-editor/languages/definitions/typescript/register.js'),
      import('monaco-editor/languages/definitions/xml/register.js'),
      import('monaco-editor/languages/definitions/yaml/register.js'),
    ]);

    // Monaco needs its worker wired up explicitly under Vite. The package's exports map is
    // "./*": "./esm/vs/*.js", so the specifier is monaco-editor/editor/... rather than the
    // esm/vs path most examples still show.
    self.MonacoEnvironment = { getWorker: () => new EditorWorker() };

    monaco.editor.defineTheme('watcher', {
      base: 'vs-dark',
      inherit: true,
      colors: { 'editor.background': '#161a21' },
      rules: [],
    });

    // The chunk is here, so a reload did fix whatever was stale and the loop guard can be lifted.
    chunkLoadedSuccessfully();
    return monaco;
  })();
  return loading;
}
