/**
 * Reset (or create) the Super Admin password from SUPER_ADMIN_* env vars.
 * Use when login fails after env credentials changed, or to align a local preview account.
 *
 *   node src/resetSuperAdminPassword.js
 */
import bcrypt from 'bcryptjs';
import dotenv from 'dotenv';
import { v4 as uuidv4 } from 'uuid';
import pool from './db.js';

dotenv.config();

async function reset() {
  const email = process.env.SUPER_ADMIN_EMAIL || 'superadmin@ekms.local';
  const password = process.env.SUPER_ADMIN_PASSWORD || 'ChangeMeNow!';
  const displayName = process.env.SUPER_ADMIN_DISPLAY_NAME || 'Super Admin';

  if (!password || password.length < 8) {
    console.error('SUPER_ADMIN_PASSWORD must be at least 8 characters.');
    process.exit(1);
  }

  const passwordHash = await bcrypt.hash(password, 12);
  const now = Date.now();

  const [existing] = await pool.execute(
    `SELECT id FROM users WHERE email = :email LIMIT 1`,
    { email },
  );

  if (existing[0]) {
    await pool.execute(
      `UPDATE users
       SET password_hash = :passwordHash,
           account_status = 'ACTIVE',
           lifecycle_state = 'ACTIVE',
           updated_at_epoch_ms = :now
       WHERE email = :email`,
      { passwordHash, now, email },
    );
    console.log(`Updated Super Admin password for ${email}`);
  } else {
    const id = uuidv4();
    await pool.execute(
      `INSERT INTO users
        (id, display_name, email, password_hash, role, account_status, revision,
         lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
       VALUES
        (:id, :displayName, :email, :passwordHash, 'SUPER_ADMIN', 'ACTIVE', 1,
         'ACTIVE', :now, :now)`,
      { id, displayName, email, passwordHash, now },
    );
    console.log(`Created Super Admin ${email}`);
  }

  process.exit(0);
}

reset().catch((err) => {
  console.error(err);
  process.exit(1);
});
