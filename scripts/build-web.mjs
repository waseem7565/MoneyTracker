// Builds the Capacitor web bundle (www/) from the single-file app.
// The standalone index.html loads Chart.js from a CDN; the app bundle ships it locally
// (so charts work offline) and adds Capacitor's core runtime for native plugins.
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'www');
const CDN_TAG = '<script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js"></script>';

let html = readFileSync(join(root, 'index.html'), 'utf8');
if (!html.includes(CDN_TAG)) throw new Error('Chart.js <script> tag not found in index.html; update scripts/build-web.mjs to match.');
html = html.replace(CDN_TAG, '<script src="capacitor.js"></script>\n<script src="chart.umd.js"></script>');

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
writeFileSync(join(out, 'index.html'), html);
copyFileSync(join(root, 'node_modules/chart.js/dist/chart.umd.js'), join(out, 'chart.umd.js'));
copyFileSync(join(root, 'node_modules/@capacitor/core/dist/capacitor.js'), join(out, 'capacitor.js'));
copyFileSync(join(root, 'monefy-import.js'), join(out, 'monefy-import.js'));
console.log('Built www/ (index.html, chart.umd.js, capacitor.js, monefy-import.js)');
