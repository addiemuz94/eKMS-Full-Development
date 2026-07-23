import bcrypt from 'bcryptjs';
import dotenv from 'dotenv';
import { v4 as uuidv4 } from 'uuid';
import pool from './db.js';

dotenv.config();

async function seed() {
  const email = process.env.SUPER_ADMIN_EMAIL || 'superadmin@ekms.local';
  const password = process.env.SUPER_ADMIN_PASSWORD || 'ChangeMeNow!';
  const displayName = process.env.SUPER_ADMIN_DISPLAY_NAME || 'Super Admin';

  const [existing] = await pool.execute(
    `SELECT id FROM users WHERE email = :email LIMIT 1`,
    { email },
  );
  if (existing[0]) {
    console.log(`Super Admin already exists: ${email}`);
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
      (:id, :displayName, :email, :passwordHash, 'SUPER_ADMIN', 'ACTIVE', 1,
       'ACTIVE', :now, :now)`,
    { id, displayName, email, passwordHash, now },
  );

  console.log('Seeded Super Admin');
  console.log(`  email: ${email}`);
  console.log('  password: (from SUPER_ADMIN_PASSWORD in .env)');
  process.exit(0);
}

seed().catch((err) => {
  console.error(err);
  process.exit(1);
});
