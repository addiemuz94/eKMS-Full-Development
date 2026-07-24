import fs from 'fs';

const SESSION_ID = '5c6d1f';
const CANDIDATE_PATHS = [
  '/root/eKMS/.cursor/debug-5c6d1f.log',
  '/app/debug-5c6d1f.log',
  '/tmp/ekms-debug-5c6d1f.log',
];

/** Temporary NDJSON debug ingest for agent session 5c6d1f. Do not log secrets. */
export function agentDebugLog({ hypothesisId, location, message, data = {}, runId = 'pre-fix' }) {
  const line = JSON.stringify({
    sessionId: SESSION_ID,
    hypothesisId,
    location,
    message,
    data,
    runId,
    timestamp: Date.now(),
    id: `log_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
  });
  // Always mirror to container logs for docker log scrape.
  console.log(`[agent-debug] ${line}`);
  for (const path of CANDIDATE_PATHS) {
    try {
      fs.appendFileSync(path, `${line}\n`);
      return;
    } catch {
      // try next path
    }
  }
}
