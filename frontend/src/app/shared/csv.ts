export type CsvCell = string | number | null | undefined;

/**
 * Semicolon-separated CSV, the default list separator of French Excel.
 * Cells containing the separator, quotes or line breaks are quoted.
 */
export function toCsv(rows: CsvCell[][]): string {
  return rows.map((row) => row.map(escapeCell).join(';')).join('\r\n');
}

/** Saves `content` as a file. The BOM makes Excel read the file as UTF-8 (accents). */
export function downloadCsv(filename: string, content: string): void {
  const blob = new Blob(['﻿', content], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

function escapeCell(cell: CsvCell): string {
  const text = cell === null || cell === undefined ? '' : String(cell);
  return /[;"\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}
