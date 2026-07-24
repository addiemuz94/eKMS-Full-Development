import dotenv from 'dotenv';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import pool from './db.js';

dotenv.config();

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** MySQL errors that mean "already applied" for additive ALTER migrations. */
const IGNORE_ERRNOS = new Set([
  1060, // ER_DUP_FIELDNAME
  1061, // ER_DUP_KEYNAME
  1826, // ER_FK_DUP_NAME
]);

async function applySqlFile(fileName, { ignoreDuplicates = false } = {}) {
  const sqlPath = path.join(__dirname, '..', 'sql', fileName);
  const sql = fs.readFileSync(sqlPath, 'utf8');
  const statements = sql
    .split(';')
    .map((chunk) =>
      chunk
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.length > 0 && !line.startsWith('--'))
        .join('\n')
        .trim(),
    )
    .filter((statement) => statement.length > 0);

  for (const statement of statements) {
    try {
      await pool.query(statement);
    } catch (err) {
      if (ignoreDuplicates && IGNORE_ERRNOS.has(Number(err.errno))) {
        console.log(`  skip (already applied): ${statement.slice(0, 60)}…`);
        continue;
      }
      throw err;
    }
  }
  console.log(`Applied ${fileName} (${statements.length} statements).`);
}

async function migrate() {
  await applySqlFile('002_phase2.sql');
  await applySqlFile('003_phase4.sql');
  await applySqlFile('004_unit_hierarchy.sql', { ignoreDuplicates: true });
  await applySqlFile('005_credential_enrollment_reference.sql', { ignoreDuplicates: true });
  await applySqlFile('006_registration_and_pairing.sql', { ignoreDuplicates: true });
  process.exit(0);
}

migrate().catch((err) => {
  console.error(err);
  process.exit(1);
});
