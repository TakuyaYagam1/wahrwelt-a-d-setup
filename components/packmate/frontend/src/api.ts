import type {
  Packet,
  Pattern,
  PatternInput,
  Service,
  Stream,
} from "./types";

const apiRoot = "/api";

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, serverMessage?: string) {
    super(status === 401 ? "Требуется войти в Packmate" : serverMessage ?? `Ошибка сервера: ${status}`);
    this.name = "ApiError";
    this.status = status;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${apiRoot}/${path.replace(/^\/+/, "")}`, {
    ...init,
    headers,
    credentials: "same-origin",
  });

  if (!response.ok) {
    let serverMessage: string | undefined;
    if (response.status >= 400 && response.status < 500
        && response.headers.get("Content-Type")?.includes("application/json")) {
      try {
        const body: unknown = await response.json();
        if (typeof body === "object" && body !== null && "message" in body
            && typeof body.message === "string") {
          serverMessage = body.message.slice(0, 240);
        }
      } catch {
        serverMessage = undefined;
      }
    }
    throw new ApiError(response.status, serverMessage);
  }
  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  if (text.length === 0) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

function post<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  return request(path, {
    method: "POST",
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
}

export const api = {
  services: () => request<Service[]>("service/"),
  createService: (service: Service) => post<Service>("service/", service),
  updateService: (port: number, service: Service) => post<Service>(`service/${port}`, service),
  deleteService: (port: number) => request<void>(`service/${port}`, { method: "DELETE" }),
  patterns: () => request<Pattern[]>("pattern/"),
  createPattern: (pattern: PatternInput) => post<Pattern>("pattern/", pattern),
  updatePattern: (id: number, pattern: Pick<Pattern, "name" | "color">) =>
    post<Pattern>(`pattern/${id}`, pattern),
  setPatternEnabled: (id: number, enabled: boolean) =>
    post<void>(`pattern/${id}/enable?enabled=${String(enabled)}`),
  deletePattern: (id: number) => request<void>(`pattern/${id}`, { method: "DELETE" }),
  lookBack: (id: number, minutes: number) => post<void>(`pattern/${id}/lookback`, minutes),
  streams: (
    servicePort: number | undefined,
    payload: { startingFrom: number | null; pageSize: number; pattern: { id: number } | null; favorites: boolean },
    signal?: AbortSignal,
  ) => post<Stream[]>(`stream/${servicePort ?? "all"}`, payload, signal),
  packets: (
    streamId: number,
    payload: { startingFrom: number | null; pageSize: number },
    signal?: AbortSignal,
  ) => post<Packet[]>(`packet/${streamId}`, payload, signal),
  setFavorite: (streamId: number, favorite: boolean) =>
    post<void>(`stream/${streamId}/${favorite ? "favorite" : "unfavorite"}`),
  pcapStarted: () => request<boolean>("pcap/started"),
  startPcap: () => post<void>("pcap/start"),
};
