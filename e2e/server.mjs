// Static file server for the WASM demo. Sets COOP/COEP headers directly so the
// service worker skip-and-redirect path in coi-serviceworker.min.js never fires.
import http from 'http';
import fs from 'fs';
import path from 'path';

const DIST_DIR = process.env.DEMO_DIST;
if (!DIST_DIR) {
  console.error('DEMO_DIST env var is required');
  process.exit(1);
}

const PORT = parseInt(process.env.PORT ?? '8787', 10);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js':   'application/javascript',
  '.wasm': 'application/wasm',
  '.json': 'application/json',
  '.css':  'text/css',
  '.svg':  'image/svg+xml',
  '.ico':  'image/x-icon',
};

http.createServer((req, res) => {
  const urlPath = req.url === '/' ? '/index.html' : (req.url ?? '/index.html');
  const filePath = path.join(DIST_DIR, urlPath.split('?')[0]);
  const ext = path.extname(filePath);

  // SharedArrayBuffer requires cross-origin isolation; set headers so the
  // service worker on the page sees crossOriginIsolated=true immediately.
  res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
  res.setHeader('Cross-Origin-Embedder-Policy', 'require-corp');
  res.setHeader('Cross-Origin-Resource-Policy', 'same-origin');

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end(`Not found: ${urlPath}`);
      return;
    }
    res.writeHead(200, { 'Content-Type': MIME[ext] ?? 'application/octet-stream' });
    res.end(data);
  });
}).listen(PORT, () => {
  console.log(`WASM demo server: http://localhost:${PORT}  (dist: ${DIST_DIR})`);
});
