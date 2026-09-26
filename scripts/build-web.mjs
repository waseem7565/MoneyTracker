// Builds the Capacitor web bundle (www/) from the single-file app.
// The standalone index.html loads Chart.js and the Manrope font from CDNs; the app bundle ships them locally
// (so charts and the font work offline) and adds Capacitor's core runtime for native plugins.
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'www');
const CDN_TAG = '<script src="https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js"></script>';
const FONT_TAG = '<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@200..800&display=swap" rel="stylesheet">';
const FONT_DIR = 'node_modules/@fontsource-variable/manrope/files';
// Same faces and unicode ranges as @fontsource-variable/manrope's index.css (Latin and Latin Extended).
const LOCAL_FONT = `<style>
  @font-face { font-family: 'Manrope'; font-style: normal; font-display: swap; font-weight: 200 800; src: url(manrope-latin-ext.woff2) format('woff2'); unicode-range: U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF; }
  @font-face { font-family: 'Manrope'; font-style: normal; font-display: swap; font-weight: 200 800; src: url(manrope-latin.woff2) format('woff2'); unicode-range: U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD; }
</style>`;

let html = readFileSync(join(root, 'index.html'), 'utf8');
if (!html.includes(CDN_TAG)) throw new Error('Chart.js <script> tag not found in index.html; update scripts/build-web.mjs to match.');
if (!html.includes(FONT_TAG)) throw new Error('Manrope font <link> not found in index.html; update scripts/build-web.mjs to match.');
html = html.replace(CDN_TAG, '<script src="capacitor.js"></script>\n<script src="chart.umd.js"></script>');
html = html.replace(FONT_TAG, LOCAL_FONT);

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
writeFileSync(join(out, 'index.html'), html);
copyFileSync(join(root, 'node_modules/chart.js/dist/chart.umd.js'), join(out, 'chart.umd.js'));
copyFileSync(join(root, 'node_modules/@capacitor/core/dist/capacitor.js'), join(out, 'capacitor.js'));
copyFileSync(join(root, FONT_DIR, 'manrope-latin-wght-normal.woff2'), join(out, 'manrope-latin.woff2'));
copyFileSync(join(root, FONT_DIR, 'manrope-latin-ext-wght-normal.woff2'), join(out, 'manrope-latin-ext.woff2'));
copyFileSync(join(root, 'monefy-import.js'), join(out, 'monefy-import.js'));
console.log('Built www/ (index.html, chart.umd.js, capacitor.js, Manrope font, monefy-import.js)');
