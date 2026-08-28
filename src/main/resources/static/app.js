// GraphQL client, hand-rolled. Queries go over HTTP POST, the live feed over a graphql-ws
// subscription. Roughly sixty lines, which is why there is no build step and no CDN here.

const HTTP_ENDPOINT = '/graphql';
const WS_ENDPOINT = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/graphql';

async function query(document, variables = {}) {
  const response = await fetch(HTTP_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: document, variables })
  });
  const result = await response.json();
  if (result.errors) throw new Error(result.errors.map(e => e.message).join('; '));
  return result.data;
}

/** Minimal graphql-transport-ws client with reconnect. */
function subscribe(document, onNext, onStateChange) {
  let socket, retry = 0, closed = false;

  const connect = () => {
    socket = new WebSocket(WS_ENDPOINT, 'graphql-transport-ws');

    socket.addEventListener('open', () => socket.send(JSON.stringify({ type: 'connection_init' })));

    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      switch (message.type) {
        case 'connection_ack':
          retry = 0;
          onStateChange('live');
          socket.send(JSON.stringify({ id: '1', type: 'subscribe', payload: { query: document } }));
          break;
        case 'ping':
          socket.send(JSON.stringify({ type: 'pong' }));
          break;
        case 'next':
          onNext(message.payload.data);
          break;
        case 'error':
          console.error('subscription error', message.payload);
          break;
      }
    });

    socket.addEventListener('close', () => {
      if (closed) return;
      onStateChange('reconnecting');
      // Back off, but stay responsive: a dashboard nobody is watching should not hammer the server.
      retry = Math.min(retry + 1, 6);
      setTimeout(connect, 250 * 2 ** retry);
    });
  };

  connect();
  return () => { closed = true; socket && socket.close(); };
}

// ---------------------------------------------------------------------------------------------

const feedEl = document.getElementById('feed');
const gitEl = document.getElementById('git');
const diffEl = document.getElementById('diff');
const diffPathEl = document.getElementById('diff-path');
const procEl = document.getElementById('processes');
const connEl = document.getElementById('conn');
const branchEl = document.getElementById('branch');
const gitSummaryEl = document.getElementById('git-summary');

const MAX_ROWS = 500;
const enabled = new Set(['TRANSCRIPT', 'HOOK', 'FS']);
let selectedPath = null;

document.querySelectorAll('.filters input').forEach(box => {
  box.addEventListener('change', () => {
    box.checked ? enabled.add(box.dataset.source) : enabled.delete(box.dataset.source);
    feedEl.querySelectorAll('.row').forEach(row => { row.hidden = !enabled.has(row.dataset.source); });
  });
});

const EVENT_FIELDS = 'seq ts source type summary path agent sessionId detail';

query(`{ status { workspace workspaceExists os transcriptDirs
          git { repo branch head headSubject files { path status staged } }
          processes { pid command cwd children { pid command cwd children { pid command cwd children { pid command cwd } } } } } }`)
  .then(({ status }) => {
    document.getElementById('workspace').textContent = status.workspace;
    renderGit(status.git);
    renderProcesses(status.processes);
    if (!status.transcriptDirs.length) {
      addRow({
        seq: '0', ts: new Date().toISOString(), source: 'SYSTEM', type: 'WARN',
        summary: 'no Claude Code transcripts found for this workspace — file events still work, ' +
                 'but nothing will be attributed to an agent'
      });
    }
  })
  .catch(error => console.error(error));

subscribe(
  `subscription { events { ${EVENT_FIELDS} } }`,
  data => handle(data.events),
  state => { connEl.textContent = state; connEl.className = 'pill ' + (state === 'live' ? 'live' : 'dim'); }
);

function handle(event) {
  const detail = event.detail ? safeParse(event.detail) : null;
  if (event.source === 'GIT' && event.type === 'STATUS' && detail) renderGit(detail);
  if (event.source === 'PROCESS' && event.type === 'SNAPSHOT' && detail) renderProcesses(detail);
  addRow(event);
}

function safeParse(text) {
  try { return JSON.parse(text); } catch { return null; }
}

function addRow(event) {
  const row = document.createElement('div');
  row.className = 'row' + (event.type === 'TOOL_ERROR' ? ' error' : '');
  row.dataset.source = event.source;
  row.hidden = !enabled.has(event.source);

  const time = new Date(event.ts).toLocaleTimeString('en-GB', { hour12: false });
  row.append(span('ts', time), span('tag ' + event.source, label(event)));

  const msg = span('msg', event.summary || '');
  if (event.agent) msg.prepend(span('agent', event.agent + ' '));
  row.append(msg);

  if (event.path) {
    row.style.cursor = 'pointer';
    row.addEventListener('click', () => showDiff(event.path));
  }

  feedEl.append(row);
  while (feedEl.childElementCount > MAX_ROWS) feedEl.firstElementChild.remove();
  // Only follow the tail when the user is already at the bottom.
  if (feedEl.scrollHeight - feedEl.scrollTop - feedEl.clientHeight < 80) {
    feedEl.scrollTop = feedEl.scrollHeight;
  }
}

function label(event) {
  if (event.source === 'FS') return event.type.toLowerCase();
  if (event.source === 'TRANSCRIPT') return event.type === 'TOOL_USE' ? 'agent →' : 'agent ←';
  if (event.source === 'HOOK') return 'hook';
  return event.source.toLowerCase();
}

function span(cls, text) {
  const el = document.createElement('span');
  el.className = cls;
  el.textContent = text;
  return el;
}

function renderGit(snapshot) {
  if (!snapshot || !snapshot.repo) {
    branchEl.textContent = '';
    gitSummaryEl.textContent = 'not a git repository';
    gitEl.replaceChildren(empty('not a git repository'));
    return;
  }
  branchEl.textContent = snapshot.branch + (snapshot.head ? ' @ ' + snapshot.head : '');
  gitSummaryEl.textContent = snapshot.headSubject || '';
  gitEl.replaceChildren();
  if (!snapshot.files.length) {
    gitEl.replaceChildren(empty('clean'));
    return;
  }
  for (const file of snapshot.files) {
    const row = document.createElement('div');
    row.className = 'file ' + file.status + (file.path === selectedPath ? ' selected' : '');
    row.append(span('st', file.status), span('p', file.path));
    row.addEventListener('click', () => showDiff(file.path));
    gitEl.append(row);
  }
}

function showDiff(path) {
  selectedPath = path;
  diffPathEl.textContent = path;
  gitEl.querySelectorAll('.file').forEach(f => f.classList.toggle('selected', f.textContent.endsWith(path)));

  query('query($path: String!) { diff(path: $path) { staged unstaged } }', { path })
    .then(({ diff }) => {
      const text = [diff.staged, diff.unstaged].filter(Boolean).join('\n');
      if (!text.trim()) {
        diffEl.replaceChildren(empty('no diff (file may be unchanged or ignored by git)'));
        return;
      }
      const pre = document.createElement('pre');
      pre.className = 'diff';
      for (const line of text.split('\n')) pre.append(span(diffClass(line), line + '\n'));
      diffEl.replaceChildren(pre);
    })
    .catch(error => diffEl.replaceChildren(empty(error.message)));
}

function diffClass(line) {
  if (line.startsWith('+++') || line.startsWith('---')) return 'm';
  if (line.startsWith('@@')) return 'h';
  if (line.startsWith('+')) return 'a';
  if (line.startsWith('-')) return 'd';
  if (line.startsWith('diff ') || line.startsWith('index ')) return 'm';
  return '';
}

function renderProcesses(tree) {
  if (!tree || !tree.length) {
    procEl.replaceChildren(empty('no processes with a working directory inside this workspace'));
    return;
  }
  const container = document.createElement('div');
  container.className = 'tree';
  const walk = (nodes, depth) => {
    for (const node of nodes) {
      const row = document.createElement('div');
      row.append(span('p', '  '.repeat(depth) + (depth ? '└─ ' : '') + node.pid + '  '));
      row.append(span('cmd', node.command));
      container.append(row);
      walk(node.children || [], depth + 1);
    }
  };
  walk(tree, 0);
  procEl.replaceChildren(container);
}

function empty(text) {
  const p = document.createElement('p');
  p.className = 'empty';
  p.textContent = text;
  return p;
}
