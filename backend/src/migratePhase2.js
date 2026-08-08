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
  1062, // ER_DUP_ENTRY (backfill INSERT)
  1826, // ER_FK_DUP_NAME
]);

/** Strip `--` comments before splitting on `;` so prose in comments cannot create fake statements. */
function stripSqlComments(sql) {
  return sql
    .split('\n')
    .map((line) => {
      const trimmed = line.trimStart();
      if (trimmed.startsWith('--')) return '';
      const idx = line.indexOf('--');
      return idx >= 0 ? line.slice(0, idx) : line;
    })
    .join('\n');
}

async function applySqlFile(fileName, { ignoreDuplicates = false } = {}) {
  const sqlPath = path.join(__dirname, '..', 'sql', fileName);
  const sql = fs.readFileSync(sqlPath, 'utf8');
  const statements = stripSqlComments(sql)
    .split(';')
    .map((chunk) => chunk.trim())
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
  await applySqlFile('007_terminal_cabinet_settings.sql', { ignoreDuplicates: true });
  await applySqlFile('008_regional_admin_checkouts_office_hours_vendor_passkey.sql', { ignoreDuplicates: true });
  await applySqlFile('009_regions_and_key_access_requests.sql', { ignoreDuplicates: true });
  await applySqlFile('010_key_access_exception_calendar.sql', { ignoreDuplicates: true });
  await applySqlFile('011_vendor_key_access_and_fcm.sql', { ignoreDuplicates: true });
  await applySqlFile('012_key_checkout_notifications.sql', { ignoreDuplicates: true });
  await applySqlFile('013_key_access_request_auto_extend.sql', { ignoreDuplicates: true });
  await applySqlFile('014_users_original_email.sql', { ignoreDuplicates: true });
  await applySqlFile('015_site_key_access_duration.sql', { ignoreDuplicates: true });
  await applySqlFile('016_role_capabilities.sql', { ignoreDuplicates: true });
  process.exit(0);
}

migrate().catch((err) => {
  console.error(err);
  process.exit(1);
});
