import path from 'node:path';
import { buildIndex } from './indexer.js';

/**
 * `npm run build-index -- [output path]`
 *
 * What the weekly GitHub Actions job runs. Prints progress on one line so the Actions log stays
 * readable, and refuses to publish a partial index — a sync that quietly returned 1.6% of the
 * corpus and reported success is exactly the bug the Android app shipped once.
 */

const output = path.resolve(process.argv[2] ?? 'out/station-index.db');

let lastPrint = 0;
const result = await buildIndex(output, ({ phase, fetched }) => {
  const now = Date.now();
  if (now - lastPrint < 1000 && fetched !== 0) return;
  lastPrint = now;
  console.log(`  ${phase}${fetched ? `… ${fetched.toLocaleString()} stations` : '…'}`);
});

console.log('');
console.log(`Built ${result.file}`);
console.log(`  stations            ${result.total.toLocaleString()}`);
console.log(`  server reported     ${result.expected?.toLocaleString() ?? 'unknown'}`);
console.log(`  complete            ${result.complete ? 'yes' : 'NO — partial index'}`);
console.log(`  tags w/ neighbours  ${result.tagsWithNeighbours.toLocaleString()}`);
console.log(`  size                ${(result.sizeBytes / 1024 / 1024).toFixed(1)} MB`);
console.log(`  took                ${result.seconds}s`);

if (!result.complete) {
  console.error('\nRefusing to publish a partial index.');
  process.exit(1);
}
