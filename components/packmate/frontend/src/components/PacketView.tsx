import { useMemo } from "react";
import { decodePacket, hexDump, packetSegments, packetText, pythonBytes, safePatternColor } from "../packet";
import type { Packet, Pattern } from "../types";

interface PacketViewProps {
  packet: Packet;
  previous?: Packet;
  patterns: Pattern[];
  hexdumpMode: boolean;
  blockSize: number;
  lineNumberBase: number;
  notify: (message: string) => void;
}

function dateText(timestamp: number): string {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "short",
    timeStyle: "medium",
  }).format(new Date(timestamp));
}

async function copyText(value: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }
  const field = document.createElement("textarea");
  field.value = value;
  field.setAttribute("readonly", "");
  field.style.position = "fixed";
  field.style.opacity = "0";
  document.body.append(field);
  field.select();
  const copied = document.execCommand("copy");
  field.remove();
  if (!copied) {
    throw new Error("Буфер обмена недоступен");
  }
}

export function PacketView({
  packet,
  previous,
  patterns,
  hexdumpMode,
  blockSize,
  lineNumberBase,
  notify,
}: PacketViewProps) {
  const bytes = useMemo(() => decodePacket(packet.content), [packet.content]);
  const segments = useMemo(() => packetSegments(packet, patterns), [packet, patterns]);
  const body = useMemo(
    () => hexdumpMode ? hexDump(bytes, blockSize, lineNumberBase) : packetText(bytes),
    [bytes, blockSize, hexdumpMode, lineNumberBase],
  );
  const flags = [
    packet.ungzipped ? "GZIP" : "",
    packet.webSocketParsed ? "WS" : "",
    packet.tlsDecrypted ? "TLS" : "",
  ].filter(Boolean);

  const copy = async (value: string, label: string) => {
    try {
      await copyText(value);
      notify(`${label}: скопировано`);
    } catch {
      notify("Не удалось скопировать данные");
    }
  };

  return (
    <article className={`packet ${packet.incoming ? "packet-request" : "packet-response"}`}>
      <header className="packet-header">
        <div className="packet-meta">
          <strong>#{packet.id}</strong>
          <time dateTime={new Date(packet.timestamp).toISOString()}>{dateText(packet.timestamp)}</time>
          {previous && <span>+{packet.timestamp - previous.timestamp} мс</span>}
          <span className="packet-direction">{packet.incoming ? "Запрос" : "Ответ"}</span>
          {flags.map((flag) => <span className="packet-flag" key={flag}>{flag}</span>)}
        </div>
        <div className="packet-actions">
          <button type="button" className="text-button" onClick={() => void copy(bytesToHex(bytes), "HEX")}>
            Копировать HEX
          </button>
          <button type="button" className="text-button" onClick={() => void copy(packetText(bytes), "Текст")}>
            Копировать текст
          </button>
          <button type="button" className="text-button" onClick={() => void copy(pythonBytes(bytes), "Python bytes")}>
            Копировать как Python bytes
          </button>
        </div>
      </header>
      <pre className="packet-content">
        {hexdumpMode
          ? body
          : segments.map((segment, index) => segment.pattern
            ? <mark key={`${segment.pattern.id}-${index}`} style={{ backgroundColor: safePatternColor(segment.pattern.color) }}>{segment.text}</mark>
            : <span key={`text-${index}`}>{segment.text}</span>)}
      </pre>
    </article>
  );
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}
