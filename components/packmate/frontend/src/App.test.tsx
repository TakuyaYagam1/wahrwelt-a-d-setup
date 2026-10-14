import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import type { Packet, Service, Stream } from "./types";

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    services: vi.fn(),
    createService: vi.fn(),
    updateService: vi.fn(),
    deleteService: vi.fn(),
    patterns: vi.fn(),
    createPattern: vi.fn(),
    updatePattern: vi.fn(),
    setPatternEnabled: vi.fn(),
    deletePattern: vi.fn(),
    lookBack: vi.fn(),
    streams: vi.fn(),
    packets: vi.fn(),
    setFavorite: vi.fn(),
    pcapStarted: vi.fn(),
    startPcap: vi.fn(),
  },
}));

vi.mock("./api", () => ({ api: apiMock }));
class MockWebSocket {
  static instances: MockWebSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  close = vi.fn();

  constructor(readonly url: string) {
    MockWebSocket.instances.push(this);
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

function service(port: number, name: string): Service {
  return {
    port,
    name,
    decryptTls: false,
    http: false,
    urldecodeHttpRequests: false,
    mergeAdjacentPackets: false,
    parseWebSockets: false,
  };
}

function stream(id: number, port = 80): Stream {
  return {
    id,
    service: port,
    protocol: "TCP",
    startTimestamp: 1_700_000_000_000,
    endTimestamp: 1_700_000_000_100,
    foundPatternsIds: [],
    favorite: false,
    ttl: 64,
    userAgentHash: null,
    sizeBytes: 10,
    packetsCount: 1,
  };
}

function packet(id: number, text: string): Packet {
  return {
    id,
    matches: [],
    timestamp: 1_700_000_000_000 + id,
    incoming: true,
    ungzipped: false,
    webSocketParsed: false,
    tlsDecrypted: false,
    hasHttpBody: false,
    content: btoa(String.fromCharCode(...new TextEncoder().encode(text))),
  };
}

function pageOfStreams(firstId = 10): Stream[] {
  return Array.from({ length: 10 }, (_, index) => stream(firstId - index));
}

function pageOfPackets(firstId = 100): Packet[] {
  return Array.from({ length: 10 }, (_, index) => packet(firstId - index, `пакет-${firstId - index}`));
}

beforeEach(() => {
  MockWebSocket.instances = [];
  vi.stubGlobal("WebSocket", MockWebSocket);
  window.history.replaceState({}, "", "/");
  localStorage.clear();
  localStorage.setItem("packmate.pageSize", "10");
  for (const method of Object.values(apiMock)) {
    method.mockReset();
  }
  apiMock.services.mockResolvedValue([service(80, "HTTP"), service(443, "HTTPS")]);
  apiMock.patterns.mockResolvedValue([]);
  apiMock.pcapStarted.mockResolvedValue(false);
});

it("получает события через WebSocket и сохраняет остановку короткого PCAP", async () => {
  const startRequest = deferred<void>();
  apiMock.streams.mockResolvedValue([]);
  apiMock.startPcap.mockReturnValue(startRequest.promise);
  const view = render(<App />);
  const startButton = await screen.findByTitle("Начать обработку PCAP-файла");
  const socket = MockWebSocket.instances.at(-1)!;
  expect(socket.url).toBe(`ws://${window.location.host}/api/ws`);

  fireEvent.click(startButton);
  await act(async () => {
    socket.onmessage?.(new MessageEvent("message", {
      data: JSON.stringify({ type: "PCAP_STARTED", value: null }),
    }));
    socket.onmessage?.(new MessageEvent("message", {
      data: JSON.stringify({ type: "PCAP_STOPPED", value: null }),
    }));
    startRequest.resolve();
  });

  expect(screen.getByTitle("Начать обработку PCAP-файла")).toBeInTheDocument();
  view.unmount();
  expect(socket.close).toHaveBeenCalledOnce();
});

describe("смена выборки во время загрузки", () => {
  it("не добавляет позднюю страницу потоков к выбранному другому сервису", async () => {
    const oldPage = deferred<Stream[]>();
    apiMock.streams.mockImplementation((port: number | undefined, query: { startingFrom: number | null }) => {
      if (port === 443) {
        return Promise.resolve([stream(900, 443)]);
      }
      return query.startingFrom === null ? Promise.resolve(pageOfStreams()) : oldPage.promise;
    });

    render(<App />);
    await screen.findByRole("button", { name: /#10 TCP/ });
    fireEvent.click(screen.getByRole("button", { name: "Загрузить еще потоки" }));
    await waitFor(() => expect(apiMock.streams).toHaveBeenCalledWith(
      undefined,
      expect.objectContaining({ startingFrom: 1 }),
      expect.any(AbortSignal),
    ));

    fireEvent.click(screen.getByRole("button", { name: /HTTPS #443/ }));
    await screen.findByRole("button", { name: /#900 TCP/ });

    await act(async () => oldPage.resolve([stream(99, 80)]));
    expect(screen.queryByRole("button", { name: /#99 TCP/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /#900 TCP/ })).toBeInTheDocument();
  });

  it("игнорирует поздние пакеты старого потока и не снимает индикатор нового запроса", async () => {
    const initialPage = deferred<Packet[]>();
    const currentPage = deferred<Packet[]>();
    apiMock.streams.mockResolvedValue(pageOfStreams());
    apiMock.packets.mockImplementation((streamId: number, query: { startingFrom: number | null }) => {
      if (streamId === 10 && query.startingFrom === null) {
        return initialPage.promise;
      }
      if (streamId === 9 && query.startingFrom === null) {
        return currentPage.promise;
      }
      return Promise.resolve([]);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /#10 TCP/ }));
    await waitFor(() => expect(apiMock.packets).toHaveBeenCalledWith(10, { startingFrom: null, pageSize: 10 }, expect.any(AbortSignal)));

    fireEvent.click(screen.getByRole("button", { name: /#9 TCP/ }));
    await waitFor(() => expect(apiMock.packets).toHaveBeenCalledWith(9, { startingFrom: null, pageSize: 10 }, expect.any(AbortSignal)));
    await act(async () => initialPage.resolve(pageOfPackets(80)));
    expect(screen.getByText("Загрузка пакетов...")).toBeInTheDocument();

    await act(async () => currentPage.resolve([packet(1, "актуальный пакет")]));
    expect(await screen.findByText("актуальный пакет")).toBeInTheDocument();
    expect(screen.queryByText(/пакет-80/)).not.toBeInTheDocument();
  });

  it("отменяет позднюю дополнительную страницу пакетов после выбора другого потока", async () => {
    const oldPage = deferred<Packet[]>();
    apiMock.streams.mockResolvedValue(pageOfStreams());
    apiMock.packets.mockImplementation((streamId: number, query: { startingFrom: number | null }) => {
      if (streamId === 10 && query.startingFrom === null) {
        return Promise.resolve(pageOfPackets());
      }
      if (streamId === 10) {
        return oldPage.promise;
      }
      return Promise.resolve([packet(2, "пакет выбранного потока")]);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: /#10 TCP/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Загрузить еще пакеты" }));
    await waitFor(() => expect(apiMock.packets).toHaveBeenCalledWith(
      10,
      { startingFrom: 91, pageSize: 10 },
      expect.any(AbortSignal),
    ));

    fireEvent.click(screen.getByRole("button", { name: /#9 TCP/ }));
    expect(await screen.findByText("пакет выбранного потока")).toBeInTheDocument();
    await act(async () => oldPage.resolve([packet(80, "пакет-старого потока")]));

    expect(screen.queryByText("пакет-старого потока")).not.toBeInTheDocument();
    expect(screen.getByText("пакет выбранного потока")).toBeInTheDocument();
  });
});
