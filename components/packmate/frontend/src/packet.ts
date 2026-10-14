import type { FoundPattern, Packet, Pattern, PatternSearchType } from "./types";

export interface TextSegment {
  text: string;
  pattern?: Pattern;
}

export function decodePacket(content: string): Uint8Array {
  const binary = atob(content);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export function packetText(bytes: Uint8Array): string {
  return new TextDecoder("utf-8", { fatal: false }).decode(bytes);
}

function rangeForMatch(
  bytes: Uint8Array,
  match: FoundPattern,
  searchType: PatternSearchType,
): [number, number] {
  if (searchType !== "SUBBYTES") {
    return [match.startPosition, match.endPosition + 1];
  }

  const start = Math.max(0, Math.min(bytes.length, match.startPosition));
  const end = Math.max(start, Math.min(bytes.length, match.endPosition + 1));
  return [packetText(bytes.subarray(0, start)).length, packetText(bytes.subarray(0, end)).length];
}

export function packetSegments(packet: Packet, patterns: Pattern[]): TextSegment[] {
  const bytes = decodePacket(packet.content);
  const text = packetText(bytes);
  const byId = new Map(patterns.map((pattern) => [pattern.id, pattern]));
  const ranges = packet.matches
    .flatMap((match) => {
      const pattern = byId.get(match.patternId);
      if (!pattern || pattern.deleted || pattern.actionType !== "FIND") {
        return [];
      }
      const [start, end] = rangeForMatch(bytes, match, pattern.searchType);
      const safeStart = Math.max(0, Math.min(text.length, start));
      const safeEnd = Math.max(safeStart, Math.min(text.length, end));
      return safeEnd > safeStart ? [{ start: safeStart, end: safeEnd, pattern }] : [];
    })
    .sort((left, right) => left.start - right.start || left.end - right.end);

  const segments: TextSegment[] = [];
  let cursor = 0;
  for (const range of ranges) {
    if (range.start < cursor) {
      continue;
    }
    if (range.start > cursor) {
      segments.push({ text: text.slice(cursor, range.start) });
    }
    segments.push({ text: text.slice(range.start, range.end), pattern: range.pattern });
    cursor = range.end;
  }
  if (cursor < text.length || segments.length === 0) {
    segments.push({ text: text.slice(cursor) });
  }
  return segments;
}

export function hexDump(bytes: Uint8Array, blockSize: number, lineNumberBase: number): string {
  const width = Number.isFinite(blockSize) ? Math.max(1, Math.min(128, Math.trunc(blockSize))) : 16;
  const base = lineNumberBase === 16 ? 16 : 10;
  const rows: string[] = [];

  for (let offset = 0; offset < bytes.length; offset += width) {
    const block = bytes.subarray(offset, Math.min(offset + width, bytes.length));
    const address = offset.toString(base).padStart(10, "0");
    const hex = Array.from(block, (byte) => byte.toString(16).padStart(2, "0").toUpperCase());
    const paddedHex = [...hex, ...Array.from({ length: width - block.length }, () => "  ")]
      .join(" ");
    const chars = Array.from(block, (byte) =>
      byte >= 0x20 && byte <= 0x7e ? String.fromCharCode(byte) : ".",
    ).join("");
    rows.push(`${address}: ${paddedHex} |${chars.padEnd(width, " ")}|`);
  }

  return rows.join("\n");
}

export function pythonBytes(bytes: Uint8Array): string {
  return `b'${Array.from(bytes, (byte) => `\\x${byte.toString(16).padStart(2, "0")}`).join("")}'`;
}

export function safePatternColor(color: string): string {
  if (/^#[\da-f]{3}(?:[\da-f]{3}|[\da-f]{5})?$/i.test(color)) {
    return color;
  }
  if (/^(?:red|green|blue|orange|purple|teal|yellow|white|black|gray|grey)$/i.test(color)) {
    return color;
  }
  return "#ff5959";
}
