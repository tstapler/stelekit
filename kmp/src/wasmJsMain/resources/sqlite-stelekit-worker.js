import sqlite3InitModule from './sqlite3-bundler-friendly.mjs';

// Debug: report status to main page via BroadcastChannel
const dbgChannel = new BroadcastChannel('stelekit-worker-debug');
dbgChannel.postMessage({ msg: 'worker-module-loaded', initModuleType: typeof sqlite3InitModule });

let db = null;

async function init(dbPath) {
  dbgChannel.postMessage({ msg: 'init-called', dbPath });
  let sqlite3;
  try {
    sqlite3 = await sqlite3InitModule({ print: console.log, printErr: console.error });
    dbgChannel.postMessage({ msg: 'sqlite3-init-ok' });
  } catch (initErr) {
    dbgChannel.postMessage({ msg: 'sqlite3-init-failed', error: String(initErr) });
    throw initErr;
  }
  try {
    const poolUtil = await sqlite3.installOpfsSAHPoolVfs({
      name: 'opfs-sahpool',
      directory: '/stelekit',
      initialCapacity: 6,
      clearOnInit: false,
    });
    db = new poolUtil.OpfsSAHPoolDb(dbPath);
    self.postMessage({ type: 'ready', backend: 'opfs-sahpool' });
  } catch (e) {
    console.warn('[SteleKit] OPFS unavailable, falling back to in-memory:', e.message);
    db = new sqlite3.oo1.DB(':memory:');
    self.postMessage({ type: 'ready', backend: 'memory', warning: e.message });
  }
}

self.onmessage = async (e) => {
  const { type, id, sql, bind, dbPath, successful } = e.data;
  try {
    if (type === 'init') {
      await init(dbPath);
      return;
    }
    if (type === 'exec' || type === 'query') {
      const rows = [];
      db.exec({ sql, bind: bind ?? [], rowMode: 'object', callback: r => rows.push({ ...r }) });
      self.postMessage({ type: 'result', id, rows });
    } else if (type === 'execute-long') {
      db.exec({ sql, bind: bind ?? [] });
      self.postMessage({ type: 'long-result', id, value: db.changes() });
    } else if (type === 'transaction-begin') {
      db.exec('BEGIN');
      self.postMessage({ type: 'result', id, rows: [] });
    } else if (type === 'transaction-end') {
      db.exec(successful ? 'COMMIT' : 'ROLLBACK');
      self.postMessage({ type: 'result', id, rows: [] });
    }
  } catch (err) {
    self.postMessage({ type: 'error', id, message: err.message });
  }
};
