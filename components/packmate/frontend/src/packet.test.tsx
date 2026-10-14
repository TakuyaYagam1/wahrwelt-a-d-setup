import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { PacketView } from "./components/PacketView";
import { decodePacket, hexDump, packetSegments } from "./packet";
import type { Packet, Pattern } from "./types";

const pattern: Pattern = {
  id: 3,
  enabled: true,
  deleted: false,
  name: "Маркер",
  value: "secret",
  color: "#ff5959",
  searchType: "SUBSTRING",
  directionType: "BOTH",
  actionType: "FIND",
  serviceId: null,
};

function packet(text: string): Packet {
  return {
    id: 8,
    matches: [],
    timestamp: 1_700_000_000_000,
    incoming: true,
    ungzipped: false,
    webSocketParsed: false,
    tlsDecrypted: false,
    hasHttpBody: false,
    content: btoa(String.fromCharCode(...new TextEncoder().encode(text))),
  };
}

describe("отображение содержимого пакета", () => {
  it("подсвечивает диапазон совпадения без преобразования байтового содержимого в HTML", () => {
    const value = packet("до secret после <script>alert(1)</script>");
    value.matches = [{ patternId: pattern.id, startPosition: 3, endPosition: 8 }];

    const segments = packetSegments(value, [pattern]);
    expect(segments).toEqual([
      { text: "до " },
      { text: "secret", pattern },
      { text: " после <script>alert(1)</script>" },
    ]);

    const view = render(<PacketView
      packet={value}
      patterns={[pattern]}
      hexdumpMode={false}
      blockSize={16}
      lineNumberBase={10}
      notify={() => undefined}
    />);
    expect(screen.getByText("secret").tagName).toBe("MARK");
    expect(view.container.querySelector("script")).toBeNull();
    expect(view.container.textContent).toContain("<script>alert(1)</script>");
  });

  it("считает диапазон SUBBYTES в байтах, а не UTF-16 позициях", () => {
    const bytes = new TextEncoder().encode("Жx");
    const value: Packet = {
      ...packet(""),
      content: btoa(String.fromCharCode(...bytes)),
      matches: [{ patternId: 3, startPosition: 2, endPosition: 2 }],
    };
    const bytePattern = { ...pattern, searchType: "SUBBYTES" as const, value: "78" };

    expect(packetSegments(value, [bytePattern])).toEqual([
      { text: "Ж" },
      { text: "x", pattern: bytePattern },
    ]);
  });

  it("строит читаемые строки HEX-дампа с выбранной шириной", () => {
    expect(hexDump(decodePacket("AQIDBA=="), 2, 16)).toBe(
      "0000000000: 01 02 |..|\n0000000002: 03 04 |..|",
    );
  });
});
