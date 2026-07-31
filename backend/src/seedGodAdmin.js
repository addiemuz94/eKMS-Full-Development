#!/usr/bin/env node
/**
 * Seed the one-time GOD_ADMIN bootstrap account (developer mode).
 * Does not create Super Admin — God Admin registers the first SA via the portal.
 */
import bcrypt from 'bcryptjs';
import dotenv from 'dotenv';
import { v4 as uuidv4 } from 'uuid';
import pool from './db.js';

dotenv.config();

async function seed() {
  const email = process.env.GOD_ADMIN_EMAIL || 'godadmin@ekms.local';
  const password = process.env.GOD_ADMIN_PASSWORD || 'ChangeMeNow!';
  const displayName = process.env.GOD_ADMIN_DISPLAY_NAME || 'God Admin';

  const [existing] = await pool.execute(
    `SELECT id FROM users WHERE email = :email OR role = 'GOD_ADMIN' LIMIT 1`,
    { email },
  );
  if (existing[0]) {
    console.log(`God Admin already exists (${existing[0].id})`);
    process.exit(0);
  }

  const now = Date.now();
  const id = uuidv4();
  const passwordHash = await bcrypt.hash(password, 12);
  await pool.execute(
    `INSERT INTO users
      (id, display_name, email, password_hash, role, account_status, revision,
       lifecycle_state, created_at_epoch_ms, updated_at_epoch_ms)
     VALUES
      (:id, :displayName, :email, :passwordHash, 'GOD_ADMIN', 'ACTIVE', 1,
       'ACTIVE', :now, :now)`,
    { id, displayName, email, passwordHash, now },
  );

  console.log('Seeded God Admin (bootstrap only)');
  console.log(`  email: ${email}`);
  console.log('  password: (from GOD_ADMIN_PASSWORD in .env)');
  process.exit(0);
}

seed().catch((err) => {
  console.error(err);
  process.exit(1);
});
