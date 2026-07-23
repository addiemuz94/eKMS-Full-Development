import dotenv from 'dotenv';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import pool from './db.js';

dotenv.config();

const __dirname = path.dirname(fileURLToPath(import.meta.url));

async function applySqlFile(fileName) {
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
    await pool.query(statement);
  }
  console.log(`Applied ${fileName} (${statements.length} statements).`);
}

async function migrate() {
  await applySqlFile('002_phase2.sql');
  await applySqlFile('003_phase4.sql');
  process.exit(0);
}

migrate().catch((err) => {
  console.error(err);
  process.exit(1);
});
