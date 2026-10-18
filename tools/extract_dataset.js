#!/usr/bin/env node
/*
 * 从 legacy/TKFMtool.js（原 AutoX.js 脚本快照）中精确抽取 `var dataset = [...]` 字面量，
 * 生成 app/src/main/assets/characters.tsv。
 *
 * 用 TSV 而不是 JSON 的原因：一行一个角色、字段制表符分隔，Kotlin 侧用 split 就能读，
 * 单元测试也不必依赖 Android 的 org.json 桩实现。
 *
 * 只做机械搬运，不做任何“修正”，保证与原版数据逐字一致。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '..');
const srcPath = path.join(repoRoot, 'legacy', 'TKFMtool.js');
const outDir = path.join(repoRoot, 'app', 'src', 'main', 'assets');
const outPath = path.join(outDir, 'characters.tsv');

if (!fs.existsSync(srcPath)) {
  console.error('找不到数据源：' + path.relative(repoRoot, srcPath));
  console.error('请把旧仓库 https://github.com/BGFFw/TKFMtool 的 TKFMtool.js 放到 legacy/ 目录下。');
  process.exit(1);
}

const src = fs.readFileSync(srcPath, 'utf8');

const declIdx = src.indexOf('var dataset');
if (declIdx < 0) throw new Error('dataset declaration not found');
const openIdx = src.indexOf('[', declIdx);
if (openIdx < 0) throw new Error('dataset array not found');

let depth = 0;
let closeIdx = -1;
for (let i = openIdx; i < src.length; i++) {
  const ch = src[i];
  if (ch === '[') depth++;
  else if (ch === ']') {
    depth--;
    if (depth === 0) { closeIdx = i; break; }
  }
}
if (closeIdx < 0) throw new Error('unbalanced dataset array');

const literal = src.slice(openIdx, closeIdx + 1);
const data = new Function('return ' + literal)();

if (!Array.isArray(data) || data.length === 0) throw new Error('dataset is empty');

const rows = data.map((row, i) => {
  if (!Array.isArray(row)) throw new Error('row ' + i + ' is not an array');
  return row.map((cell) => {
    const text = String(cell);
    if (text.includes('\t') || text.includes('\n')) {
      throw new Error('row ' + i + ' cell contains a tab or newline: ' + JSON.stringify(text));
    }
    return text;
  });
});

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(outPath, rows.map((r) => r.join('\t')).join('\n') + '\n', 'utf8');

// 统计信息：验证搬运完整性
const vocab = new Set();
const names = new Set();
const starBuckets = {};
for (const row of rows) {
  names.add(row[0]);
  const star = row[0][0];
  starBuckets[star] = (starBuckets[star] || 0) + 1;
  for (let i = 1; i < row.length; i++) vocab.add(row[i]);
}

console.log('rows          = ' + rows.length);
console.log('unique names  = ' + names.size);
console.log('star buckets  = ' + JSON.stringify(starBuckets));
console.log('vocab size    = ' + vocab.size);
console.log('min row len   = ' + Math.min.apply(null, rows.map((r) => r.length)));
console.log('max row len   = ' + Math.max.apply(null, rows.map((r) => r.length)));
console.log('written       = ' + path.relative(repoRoot, outPath));
