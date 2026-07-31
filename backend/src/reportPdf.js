import PDFDocument from 'pdfkit';

function formatWhen(epochMs) {
  if (epochMs == null || Number.isNaN(Number(epochMs))) return '—';
  return new Date(Number(epochMs)).toLocaleString('en-MY', { timeZone: 'Asia/Kuala_Lumpur' });
}

function drawTableHeader(doc, columns, y) {
  doc.font('Helvetica-Bold').fontSize(9);
  let x = doc.page.margins.left;
  for (const col of columns) {
    doc.text(col.label, x, y, { width: col.width, continued: false });
    x += col.width;
  }
  doc
    .moveTo(doc.page.margins.left, y + 14)
    .lineTo(doc.page.width - doc.page.margins.right, y + 14)
    .strokeColor('#cccccc')
    .stroke();
  return y + 18;
}

function ensureSpace(doc, needed = 40) {
  if (doc.y + needed <= doc.page.height - doc.page.margins.bottom) return;
  doc.addPage();
}

/**
 * Stream a key pickup/return PDF for a site (or narrower filter). Rows come from key_checkouts
 * joined to users, keys, terminals, and sites — audit detail is used only as a fallback note.
 */
export function streamKeyOperationsPdf(res, { rows, siteName, filter, generatedAtEpochMillis }) {
  const filename = `key-operations-${(siteName || 'report').replace(/[^\w.-]+/g, '-').slice(0, 48)}.pdf`;
  res.setHeader('Content-Type', 'application/pdf');
  res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);

  const doc = new PDFDocument({ margin: 48, size: 'A4', layout: 'landscape' });
  doc.pipe(res);

  doc.font('Helvetica-Bold').fontSize(16).text('Key Pickup & Return Report', { align: 'center' });
  doc.moveDown(0.4);
  doc.font('Helvetica').fontSize(10).fillColor('#444444');
  if (siteName) doc.text(`Location: ${siteName}`, { align: 'center' });
  doc.text(`Generated: ${formatWhen(generatedAtEpochMillis)}`, { align: 'center' });
  if (filter?.fromEpochMillis || filter?.untilEpochMillis) {
    const from = filter.fromEpochMillis ? formatWhen(filter.fromEpochMillis) : 'beginning';
    const until = filter.untilEpochMillis ? formatWhen(filter.untilEpochMillis) : 'now';
    doc.text(`Period: ${from} — ${until}`, { align: 'center' });
  }
  doc.moveDown(0.8);
  doc.fillColor('#000000');

  if (!rows.length) {
    doc.font('Helvetica').fontSize(11).text('No key checkout records match this filter.');
    doc.end();
    return;
  }

  const columns = [
    { label: 'Who', width: 110 },
    { label: 'What (key)', width: 110 },
    { label: 'Taken', width: 95 },
    { label: 'Returned', width: 95 },
    { label: 'Where', width: 120 },
    { label: 'Why', width: 80 },
    { label: 'How', width: 90 },
  ];

  let y = drawTableHeader(doc, columns, doc.y);

  doc.font('Helvetica').fontSize(8);
  for (const row of rows) {
    ensureSpace(doc, 22);
    if (doc.y > y + 200) y = drawTableHeader(doc, columns, doc.page.margins.top);

    const where = [row.siteName, row.terminalName].filter(Boolean).join(' · ');
    const why = row.isEmergency ? 'Emergency' : row.status === 'OPEN' ? 'Open checkout' : 'Standard';
    const how = row.terminalName || 'Terminal';

    const values = [
      row.userName || row.userId,
      row.keyName || row.keyId,
      formatWhen(row.takenAtEpochMillis),
      row.returnedAtEpochMillis ? formatWhen(row.returnedAtEpochMillis) : '—',
      where,
      why,
      how,
    ];

    let x = doc.page.margins.left;
    const rowY = doc.y;
    let maxHeight = 12;
    values.forEach((value, idx) => {
      const height = doc.heightOfString(String(value), { width: columns[idx].width });
      maxHeight = Math.max(maxHeight, height);
      doc.text(String(value), x, rowY, { width: columns[idx].width, continued: false });
      x += columns[idx].width;
    });
    doc.y = rowY + maxHeight + 4;
    y = doc.y;
  }

  doc.moveDown();
  doc.font('Helvetica').fontSize(8).fillColor('#666666').text(`Total records: ${rows.length}`);
  doc.end();
}

export async function queryKeyCheckoutReportRows(pool, filter = {}) {
  const { siteId, terminalId, fromEpochMillis, untilEpochMillis, limit = 500 } = filter;
  let sql = `
    SELECT kc.id, kc.key_id, kc.user_id, kc.terminal_id,
           kc.taken_at_epoch_ms, kc.returned_at_epoch_ms, kc.due_at_epoch_ms,
           kc.status, kc.is_emergency,
           mk.display_name AS key_name,
           u.display_name AS user_name,
           t.name AS terminal_name,
           s.name AS site_name, s.city, s.province
    FROM key_checkouts kc
    JOIN managed_keys mk ON mk.id = kc.key_id
    JOIN users u ON u.id = kc.user_id
    JOIN terminals t ON t.id = kc.terminal_id
    JOIN sites s ON s.id = t.site_id
    WHERE 1=1
  `;
  const params = {};
  if (siteId) {
    sql += ` AND t.site_id = :siteId`;
    params.siteId = siteId;
  }
  if (terminalId) {
    sql += ` AND kc.terminal_id = :terminalId`;
    params.terminalId = terminalId;
  }
  if (fromEpochMillis != null && !Number.isNaN(Number(fromEpochMillis))) {
    sql += ` AND kc.taken_at_epoch_ms >= :fromMs`;
    params.fromMs = Number(fromEpochMillis);
  }
  if (untilEpochMillis != null && !Number.isNaN(Number(untilEpochMillis))) {
    sql += ` AND kc.taken_at_epoch_ms <= :untilMs`;
    params.untilMs = Number(untilEpochMillis);
  }
  sql += ` ORDER BY kc.taken_at_epoch_ms DESC LIMIT ${Math.min(Number(limit) || 500, 1000)}`;

  const [rows] = await pool.execute(sql, params);
  return rows.map((row) => ({
    id: row.id,
    keyId: row.key_id,
    userId: row.user_id,
    terminalId: row.terminal_id,
    keyName: row.key_name,
    userName: row.user_name,
    terminalName: row.terminal_name,
    siteName: row.site_name,
    city: row.city,
    province: row.province,
    takenAtEpochMillis: Number(row.taken_at_epoch_ms),
    returnedAtEpochMillis: row.returned_at_epoch_ms == null ? null : Number(row.returned_at_epoch_ms),
    dueAtEpochMillis: Number(row.due_at_epoch_ms),
    status: row.status,
    isEmergency: Boolean(row.is_emergency),
  }));
}
