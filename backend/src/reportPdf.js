import PDFDocument from 'pdfkit';

function formatWhen(epochMs) {
  if (epochMs == null || Number.isNaN(Number(epochMs))) return '—';
  return new Date(Number(epochMs)).toLocaleString('en-MY', {
    timeZone: 'Asia/Kuala_Lumpur',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

function usableWidth(doc) {
  return doc.page.width - doc.page.margins.left - doc.page.margins.right;
}

/**
 * Scale column widths so they fill the page content width exactly.
 * @param {{ label: string, weight: number }[]} specs
 */
function layoutColumns(doc, specs) {
  const totalWeight = specs.reduce((sum, col) => sum + col.weight, 0);
  const width = usableWidth(doc);
  return specs.map((col) => ({
    label: col.label,
    width: Math.floor((col.weight / totalWeight) * width),
  }));
}

function cellText(value) {
  const text = value == null || value === '' ? '—' : String(value);
  return text.replace(/\s+/g, ' ').trim();
}

/**
 * Draw a ruled table: header + rows. Text is clipped to each cell so columns never overlap.
 */
function drawDataTable(doc, { columns, rows, startY }) {
  const left = doc.page.margins.left;
  const right = doc.page.width - doc.page.margins.right;
  const bottom = doc.page.height - doc.page.margins.bottom;
  const padX = 4;
  const padY = 4;
  const headerFontSize = 9;
  const bodyFontSize = 8;
  const lineGap = 1;
  const minRowHeight = 18;
  const maxCellLines = 3;

  let y = startY;

  function drawHeader() {
    const headerHeight = headerFontSize + padY * 2 + 2;
    if (y + headerHeight > bottom) {
      doc.addPage();
      y = doc.page.margins.top;
    }

    doc.save();
    doc.rect(left, y, right - left, headerHeight).fill('#E8EEF5');
    doc.restore();

    doc.font('Helvetica-Bold').fontSize(headerFontSize).fillColor('#102A43');
    let x = left;
    for (const col of columns) {
      doc.text(col.label, x + padX, y + padY, {
        width: col.width - padX * 2,
        height: headerHeight - padY,
        lineBreak: false,
        ellipsis: true,
      });
      x += col.width;
    }

    doc
      .strokeColor('#9AA8B8')
      .lineWidth(0.8)
      .moveTo(left, y)
      .lineTo(right, y)
      .moveTo(left, y + headerHeight)
      .lineTo(right, y + headerHeight)
      .stroke();

    // Vertical rules for header
    x = left;
    for (let i = 0; i < columns.length; i += 1) {
      doc.moveTo(x, y).lineTo(x, y + headerHeight).stroke();
      x += columns[i].width;
    }
    doc.moveTo(right, y).lineTo(right, y + headerHeight).stroke();

    y += headerHeight;
    doc.fillColor('#000000');
  }

  function measureRowHeight(values) {
    doc.font('Helvetica').fontSize(bodyFontSize);
    let maxHeight = minRowHeight;
    values.forEach((value, idx) => {
      const h = doc.heightOfString(cellText(value), {
        width: columns[idx].width - padX * 2,
        lineGap,
      });
      const capped = Math.min(h, bodyFontSize * maxCellLines + lineGap * (maxCellLines - 1));
      maxHeight = Math.max(maxHeight, capped + padY * 2);
    });
    return maxHeight;
  }

  function drawRow(values, stripe) {
    const rowHeight = measureRowHeight(values);

    if (y + rowHeight > bottom) {
      doc.addPage();
      y = doc.page.margins.top;
      drawHeader();
    }

    if (stripe) {
      doc.save();
      doc.rect(left, y, right - left, rowHeight).fill('#F7F9FC');
      doc.restore();
    }

    doc.font('Helvetica').fontSize(bodyFontSize).fillColor('#1B1B1B');
    let x = left;
    values.forEach((value, idx) => {
      const col = columns[idx];
      doc.text(cellText(value), x + padX, y + padY, {
        width: col.width - padX * 2,
        height: rowHeight - padY,
        lineGap,
        ellipsis: true,
      });
      x += col.width;
    });

    doc.strokeColor('#D0D7DE').lineWidth(0.5);
    doc.moveTo(left, y + rowHeight).lineTo(right, y + rowHeight).stroke();
    x = left;
    for (let i = 0; i < columns.length; i += 1) {
      doc.moveTo(x, y).lineTo(x, y + rowHeight).stroke();
      x += columns[i].width;
    }
    doc.moveTo(right, y).lineTo(right, y + rowHeight).stroke();

    y += rowHeight;
    doc.fillColor('#000000');
  }

  drawHeader();
  rows.forEach((values, index) => {
    drawRow(values, index % 2 === 1);
  });

  doc.y = y + 8;
  return y;
}

function writeReportTitle(doc, title, metaLines) {
  doc.font('Helvetica-Bold').fontSize(16).fillColor('#102A43').text(title, { align: 'center' });
  doc.moveDown(0.35);
  doc.font('Helvetica').fontSize(9).fillColor('#445566');
  for (const line of metaLines) {
    if (line) doc.text(line, { align: 'center' });
  }
  doc.moveDown(0.7);
  doc.fillColor('#000000');
}

/**
 * Stream a key pickup/return PDF for a site (or narrower filter). Rows come from key_checkouts
 * joined to users, keys, terminals, and sites — audit detail is used only as a fallback note.
 */
export function streamKeyOperationsPdf(res, { rows, siteName, filter, generatedAtEpochMillis }) {
  const filename = `key-operations-${(siteName || 'report').replace(/[^\w.-]+/g, '-').slice(0, 48)}.pdf`;
  res.setHeader('Content-Type', 'application/pdf');
  res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);

  const doc = new PDFDocument({ margin: 36, size: 'A4', layout: 'landscape', autoFirstPage: true });
  doc.pipe(res);

  const meta = [];
  if (siteName) meta.push(`Location: ${siteName}`);
  meta.push(`Generated: ${formatWhen(generatedAtEpochMillis)}`);
  if (filter?.fromEpochMillis || filter?.untilEpochMillis) {
    const from = filter.fromEpochMillis ? formatWhen(filter.fromEpochMillis) : 'beginning';
    const until = filter.untilEpochMillis ? formatWhen(filter.untilEpochMillis) : 'now';
    meta.push(`Period: ${from} — ${until}`);
  }
  writeReportTitle(doc, 'Key Pickup & Return Report', meta);

  if (!rows.length) {
    doc.font('Helvetica').fontSize(11).text('No key checkout records match this filter.');
    doc.end();
    return;
  }

  const columns = layoutColumns(doc, [
    { label: 'Personnel', weight: 1.4 },
    { label: 'Key', weight: 1.3 },
    { label: 'Taken', weight: 1.1 },
    { label: 'Returned', weight: 1.1 },
    { label: 'Location', weight: 1.5 },
    { label: 'Cabinet', weight: 1.2 },
    { label: 'Status', weight: 0.9 },
  ]);

  const tableRows = rows.map((row) => [
    row.userName || row.userId,
    row.keyName || row.keyId,
    formatWhen(row.takenAtEpochMillis),
    row.returnedAtEpochMillis ? formatWhen(row.returnedAtEpochMillis) : '—',
    row.siteName || '—',
    row.terminalName || '—',
    row.isEmergency ? 'Emergency' : row.status === 'OPEN' ? 'Open' : 'Returned',
  ]);

  drawDataTable(doc, { columns, rows: tableRows, startY: doc.y });
  doc.font('Helvetica').fontSize(8).fillColor('#666666').text(`Total records: ${rows.length}`);
  doc.end();
}

const ACTIVITY_CATEGORY_LABELS = {
  KEY_TAKE: 'Key take',
  KEY_RETURN: 'Key return',
  CABINET_REGISTRATION: 'Cabinet registration',
  PERSONNEL_REGISTRATION: 'Personnel registration',
};

/**
 * Stream an Activity Report PDF from enriched audit_events rows
 * (category, site/terminal/actor display names already joined by the caller).
 */
export function streamActivityLogsPdf(res, { rows, siteName, terminalName, filter, generatedAtEpochMillis }) {
  const filename = `activity-logs-${(siteName || 'report').replace(/[^\w.-]+/g, '-').slice(0, 48)}.pdf`;
  res.setHeader('Content-Type', 'application/pdf');
  res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);

  const doc = new PDFDocument({ margin: 36, size: 'A4', layout: 'landscape', autoFirstPage: true });
  doc.pipe(res);

  const meta = [];
  if (siteName) meta.push(`Location: ${siteName}`);
  if (terminalName) meta.push(`Cabinet: ${terminalName}`);
  if (filter?.categories?.length) {
    const labels = filter.categories.map((c) => ACTIVITY_CATEGORY_LABELS[c] || c).join(', ');
    meta.push(`Categories: ${labels}`);
  }
  meta.push(`Generated: ${formatWhen(generatedAtEpochMillis)}`);
  if (filter?.fromEpochMillis || filter?.untilEpochMillis) {
    const from = filter.fromEpochMillis ? formatWhen(filter.fromEpochMillis) : 'beginning';
    const until = filter.untilEpochMillis ? formatWhen(filter.untilEpochMillis) : 'now';
    meta.push(`Period: ${from} — ${until}`);
  }
  writeReportTitle(doc, 'Activity Report', meta);

  if (!rows.length) {
    doc.font('Helvetica').fontSize(11).text('No activity records match this filter.');
    doc.end();
    return;
  }

  const columns = layoutColumns(doc, [
    { label: 'Date/Time', weight: 1.1 },
    { label: 'Location', weight: 1.1 },
    { label: 'Cabinet', weight: 1.1 },
    { label: 'Category', weight: 1.1 },
    { label: 'Event', weight: 1.3 },
    { label: 'User', weight: 1.1 },
    { label: 'Detail', weight: 1.8 },
  ]);

  const tableRows = rows.map((row) => [
    formatWhen(row.occurredAtEpochMillis),
    row.siteName || '—',
    row.terminalName || '—',
    ACTIVITY_CATEGORY_LABELS[row.category] || row.category || '—',
    row.eventType || '—',
    row.actorName || row.actorUserId || '—',
    (row.detail || '—').toString().slice(0, 160),
  ]);

  drawDataTable(doc, { columns, rows: tableRows, startY: doc.y });
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
      AND t.lifecycle_state = 'ACTIVE'
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
