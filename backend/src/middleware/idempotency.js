import pool from '../db.js';

const TTL_MS = 24 * 60 * 60 * 1000;

export async function idempotency(req, res, next) {
  if (!['POST', 'PATCH', 'DELETE'].includes(req.method)) {
    return next();
  }

  const key = req.headers['idempotency-key'];
  if (!key) {
    return res.status(400).json({
      error: 'IDEMPOTENCY_KEY_REQUIRED',
      message: 'Mutating requests require Idempotency-Key header',
    });
  }

  const userId = req.auth?.sub || 'anonymous';
  const [rows] = await pool.execute(
    `SELECT status_code, response_body FROM idempotency_keys
     WHERE idempotency_key = :key AND user_id = :userId
       AND created_at_epoch_ms > :minCreated`,
    { key, userId, minCreated: Date.now() - TTL_MS },
  );

  if (rows.length > 0) {
    return res.status(rows[0].status_code).json(
      typeof rows[0].response_body === 'string'
        ? JSON.parse(rows[0].response_body)
        : rows[0].response_body,
    );
  }

  const originalJson = res.json.bind(res);
  res.json = (body) => {
    const statusCode = res.statusCode || 200;
    pool
      .execute(
        `INSERT INTO idempotency_keys (idempotency_key, user_id, status_code, response_body, created_at_epoch_ms)
         VALUES (:key, :userId, :statusCode, CAST(:body AS JSON), :now)
         ON DUPLICATE KEY UPDATE status_code = VALUES(status_code), response_body = VALUES(response_body)`,
        {
          key,
          userId,
          statusCode,
          body: JSON.stringify(body),
          now: Date.now(),
        },
      )
      .catch((err) => console.error('idempotency store failed', err.message));
    return originalJson(body);
  };

  return next();
}
