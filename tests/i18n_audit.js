#!/usr/bin/env node
/* YT Music Remote — i18n denetimi
   Kullanim: node tests/i18n_audit.js [i18n.js yolu] */
const fs = require('fs'), vm = require('vm');
const path = process.argv[2] || 'i18n.js';
const src = fs.readFileSync(path, 'utf8');
const ctx = { window: {} };
vm.createContext(ctx);
vm.runInContext(src, ctx);
const I = ctx.window.I18N, N = ctx.window.I18N_NAMES, O = ctx.window.I18N_ORDER, F = ctx.window.I18N_FLAGS;
const base = Object.keys(I.en);
const langs = Object.keys(I);
let problems = 0;
console.log(`dil: ${langs.length} | anahtar (en): ${base.length}`);

for (const l of langs) {
  const keys = Object.keys(I[l]);
  const missing = base.filter(k => !(k in I[l]));
  const extra = keys.filter(k => !base.includes(k));
  const empty = keys.filter(k => !String(I[l][k]).trim());
  if (missing.length) { console.log(`EKSIK     ${l}: ${missing.join(', ')}`); problems += missing.length; }
  if (extra.length) { console.log(`FAZLA     ${l}: ${extra.join(', ')}`); problems += extra.length; }
  if (empty.length) { console.log(`BOS       ${l}: ${empty.join(', ')}`); problems += empty.length; }
  const same = base.filter(k => I[l][k] === I.en[k]);
  if (l !== 'en' && l !== 'tr' && same.length)
    console.log(`CEVRILMEMIS ${l}: ${same.length} anahtar ingilizceyle ayni (${same.slice(0, 8).join(', ')})`);
}
for (const l of langs) if (!N[l]) { console.log('ISIM YOK  ', l); problems++; }
for (const l of O) if (!I[l]) { console.log('SIRA: tanimsiz', l); problems++; }
for (const l of langs) if (!O.includes(l)) { console.log('SIRA: eksik  ', l); problems++; }
for (const l of langs) if (!F[l]) { console.log('BAYRAK YOK', l); problems++; }
for (const l of langs) for (const k of base) {
  const a = (I.en[k].match(/\{\w+\}/g) || []).sort().join(',');
  const b = ((I[l][k] || '').match(/\{\w+\}/g) || []).sort().join(',');
  if (a !== b) { console.log(`YERTUTUCU ${l}.${k}: en=[${a}] ${l}=[${b}]`); problems++; }
}
// arayuzde kullanilan anahtarlar gercekten var mi?
try {
  const html = fs.readFileSync(path.replace(/i18n\.js$/, 'ui.html'), 'utf8');
  const used = new Set();
  for (const m of html.matchAll(/\bt\(\s*['"]([\w_]+)['"]/g)) used.add(m[1]);
  for (const m of html.matchAll(/data-i18n(?:-ph)?="([\w_]+)"/g)) used.add(m[1]);
  const unknown = [...used].filter(k => !(k in I.en));
  if (unknown.length) { console.log('TANIMSIZ ANAHTAR (ui.html): ' + unknown.join(', ')); problems += unknown.length; }
  const unused = base.filter(k => !used.has(k));
  if (unused.length) console.log('kullanilmayan anahtar: ' + unused.join(', '));
  console.log(`ui.html'de kullanilan anahtar: ${used.size}`);
} catch (e) { console.log('ui.html okunamadi: ' + e.message); }

console.log(problems ? `\n${problems} SORUN` : '\ntum diller tam, eksik yok');
process.exit(problems ? 1 : 0);
