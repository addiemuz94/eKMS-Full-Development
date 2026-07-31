import { Router } from 'express';
import { z } from 'zod';
import { registerPushToken } from '../fcm.js';
import { badRequest } from '../util.js';

const router = Router();

const schema = z.object({
  fcmToken: z.string().trim().min(20).max(512),
  platform: z.enum(['ANDROID', 'IOS']).default('ANDROID'),
});

router.post('/', async (req, res) => {
  const parsed = schema.safeParse(req.body);
  if (!parsed.success) return badRequest(res, 'Invalid push token payload');
  if (!req.auth?.sub) {
    return res.status(401).json({ error: 'UNAUTHORIZED', message: 'Auth required' });
  }
  await registerPushToken(req.auth.sub, parsed.data.fcmToken, parsed.data.platform);
  return res.status(204).send();
});

export default router;
