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
    feedEl.querySelectorAll('.row').forEach(row => {
      row.hidden = !enabled.has(row.dataset.source);
    });
  });
});

fetch('/api/status').then(r => r.json()).then(status => {
  document.getElementById('workspace').textContent = status.workspace;
  if (!status.transcriptDirs.length) {
    addRow({
      seq: 0, ts: new Date().toISOString(), source: 'SYSTEM', type: 'WARN',
      summary: 'no Claude Code transcripts found for this workspace — file events still work, ' +
               'but nothing will be attributed to an agent'
    });
  }
});

const stream = new EventSource('/api/events');
stream.addEventListener('open', () => { connEl.textContent = 'live'; connEl.className = 'pill live'; });
stream.addEventListener('error', () => { connEl.textContent = 'reconnecting'; connEl.className = 'pill dim'; });
stream.addEventListener('watch', e => handle(JSON.parse(e.data)));

function handle(event) {
  if (event.source === 'GIT' && event.type === 'STATUS') renderGit(event.detail);
  if (event.source === 'PROCESS' && event.type === 'SNAPSHOT') renderProcesses(event.detail);
  addRow(event);
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
    gitEl.innerHTML = '<p class="empty">not a git repository</p>';
    return;
  }
  branchEl.textContent = snapshot.branch + (snapshot.head ? ' @ ' + snapshot.head : '');
  gitSummaryEl.textContent = snapshot.headSubject || '';
  gitEl.replaceChildren();
  if (!snapshot.files.length) {
    gitEl.innerHTML = '<p class="empty">clean</p>';
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
  fetch('/api/diff?path=' + encodeURIComponent(path))
    .then(r => r.json())
    .then(result => {
      const text = [result.staged, result.unstaged].filter(Boolean).join('\n');
      diffEl.replaceChildren();
      if (!text.trim()) {
        diffEl.innerHTML = '<p class="empty">no diff (file may be unchanged or ignored by git)</p>';
        return;
      }
      const pre = document.createElement('pre');
      pre.className = 'diff';
      for (const line of text.split('\n')) {
        pre.append(span(diffClass(line), line + '\n'));
      }
      diffEl.append(pre);
    });
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
  procEl.replaceChildren();
  if (!tree || !tree.length) {
    procEl.innerHTML = '<p class="empty">no processes with a working directory inside this workspace</p>';
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
  procEl.append(container);
}
