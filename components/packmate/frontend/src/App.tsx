import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { api, ApiError } from "./api";
import { PacketView } from "./components/PacketView";
import type {
  CountersUpdate,
  Pattern,
  PatternActionType,
  PatternDirectionType,
  PatternInput,
  PatternSearchType,
  RouteState,
  Service,
  Stream,
  SubscriptionMessage,
} from "./types";

type ServiceDialogState = { mode: "create" | "edit"; value: Service };
type PatternDialogState = { mode: "create" | "edit"; value: PatternInput };

const pageSizeStorageKey = "packmate.pageSize";
const hexWidthStorageKey = "packmate.hexWidth";
const lineBaseStorageKey = "packmate.lineBase";
const hexdumpStorageKey = "packmate.hexdump";
const themeStorageKey = "packmate.theme";
const maxStreamsInMemory = 2000;

function readNumber(key: string, fallback: number, minimum: number, maximum: number): number {
  try {
    const value = Number(localStorage.getItem(key));
    return Number.isInteger(value) && value >= minimum && value <= maximum ? value : fallback;
  } catch {
    return fallback;
  }
}

function readBoolean(key: string, fallback: boolean): boolean {
  try {
    const value = localStorage.getItem(key);
    return value === null ? fallback : value === "true";
  } catch {
    return fallback;
  }
}

function parseRoute(): RouteState {
  const pieces = window.location.pathname.split("/").filter(Boolean);
  const route: RouteState = {};
  if (pieces[0] && /^\d+$/.test(pieces[0])) {
    route.servicePort = Number(pieces[0]);
  }
  if (pieces[1] && /^\d+$/.test(pieces[1])) {
    route.streamId = Number(pieces[1]);
  }
  const pattern = new URLSearchParams(window.location.search).get("pattern");
  if (pattern && /^\d+$/.test(pattern)) {
    route.patternId = Number(pattern);
  }
  return route;
}

function makePath(route: RouteState): string {
  const path = route.servicePort === undefined
    ? "/"
    : route.streamId === undefined
      ? `/${route.servicePort}`
      : `/${route.servicePort}/${route.streamId}`;
  return route.patternId === undefined ? path : `${path}?pattern=${route.patternId}`;
}

function emptyService(): Service {
  return {
    port: 0,
    name: "",
    decryptTls: false,
    http: false,
    urldecodeHttpRequests: false,
    mergeAdjacentPackets: false,
    parseWebSockets: false,
  };
}

function emptyPattern(): PatternInput {
  return {
    name: "",
    value: "",
    color: "#ff5959",
    searchType: "SUBSTRING",
    directionType: "BOTH",
    actionType: "FIND",
    serviceId: null,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error && error.message === "Требуется войти в Packmate") {
    return error.message;
  }
  if (error instanceof Error && error.message.startsWith("Ошибка сервера:")) {
    return error.message;
  }
  return "Не удалось связаться с Packmate. Проверьте сервер и повторите попытку.";
}

function serviceName(services: Service[], port: number): string {
  return services.find((service) => service.port === port)?.name ?? "Удаленный сервис";
}

function patternSummary(pattern: Pattern, services: Service[]): string {
  const method = pattern.searchType === "REGEX"
    ? `/${pattern.value}/`
    : pattern.searchType === "SUBBYTES"
      ? `0x${pattern.value}`
    : `"${pattern.value}"`;
  const action = pattern.actionType === "FIND" ? "подсвечивать" : "игнорировать";
  const direction = pattern.directionType === "INPUT"
    ? "в запросе"
    : pattern.directionType === "OUTPUT"
      ? "в ответе"
      : "в запросе и ответе";
  const service = pattern.serviceId === null
    ? "для любого сервиса"
    : `для ${serviceName(services, pattern.serviceId)} #${pattern.serviceId}`;
  return `${method}; ${action} ${direction} ${service}`;
}

function streamMatches(stream: Stream, query: string, services: Service[]): boolean {
  if (!query.trim()) {
    return true;
  }
  const haystack = [
    String(stream.id),
    stream.protocol,
    String(stream.service),
    serviceName(services, stream.service),
    stream.userAgentHash ?? "",
  ].join(" ").toLocaleLowerCase("ru-RU");
  return haystack.includes(query.trim().toLocaleLowerCase("ru-RU"));
}

function App() {
  const [route, setRoute] = useState<RouteState>(parseRoute);
  const [services, setServices] = useState<Service[]>([]);
  const [patterns, setPatterns] = useState<Pattern[]>([]);
  const [streams, setStreams] = useState<Stream[]>([]);
  const [packets, setPackets] = useState<import("./types").Packet[]>([]);
  const [streamHasMore, setStreamHasMore] = useState(false);
  const [packetHasMore, setPacketHasMore] = useState(false);
  const [streamsLoading, setStreamsLoading] = useState(true);
  const [packetsLoading, setPacketsLoading] = useState(false);
  const [moreStreamsLoading, setMoreStreamsLoading] = useState(false);
  const [morePacketsLoading, setMorePacketsLoading] = useState(false);
  const [favoritesOnly, setFavoritesOnly] = useState(false);
  const [pauseNewStreams, setPauseNewStreams] = useState(false);
  const [hexdumpMode, setHexdumpMode] = useState(() => readBoolean(hexdumpStorageKey, false));
  const [hexWidth, setHexWidth] = useState(() => readNumber(hexWidthStorageKey, 16, 1, 128));
  const [lineBase, setLineBase] = useState(() => readNumber(lineBaseStorageKey, 10, 10, 16));
  const [pageSize, setPageSize] = useState(() => readNumber(pageSizeStorageKey, 100, 10, 500));
  const [theme, setTheme] = useState(() => {
    try {
      return localStorage.getItem(themeStorageKey) === "dark" ? "dark" : "light";
    } catch {
      return "light";
    }
  });
  const [searchText, setSearchText] = useState("");
  const [serviceDialog, setServiceDialog] = useState<ServiceDialogState | null>(null);
  const [patternDialog, setPatternDialog] = useState<PatternDialogState | null>(null);
  const [lookBackPattern, setLookBackPattern] = useState<number | null>(null);
  const [lookBackMinutes, setLookBackMinutes] = useState(5);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [pcapStarted, setPcapStarted] = useState(true);
  const [notice, setNotice] = useState("");
  const [browsingHistory, setBrowsingHistory] = useState(false);
  const [socketStatus, setSocketStatus] = useState("Подключение к событиям...");
  const [counters, setCounters] = useState<CountersUpdate>({
    totalPackets: 0,
    totalStreams: 0,
    servicesPackets: {},
    servicesStreams: {},
  });
  const [refreshKey, setRefreshKey] = useState(0);
  const streamPageLock = useRef<string | null>(null);
  const packetPageLock = useRef<string | null>(null);
  const streamPageController = useRef<AbortController | null>(null);
  const packetPageController = useRef<AbortController | null>(null);
  const historyWindowRef = useRef(false);
  const streamListRef = useRef<HTMLElement | null>(null);
  const streamsRef = useRef<Stream[]>([]);
  const contextRef = useRef({ route, favoritesOnly, pauseNewStreams });
  const streamQueryKey = JSON.stringify([
    route.servicePort ?? null,
    route.patternId ?? null,
    favoritesOnly,
    pageSize,
    refreshKey,
  ]);
  const packetQueryKey = JSON.stringify([route.streamId ?? null, pageSize]);
  const streamQueryKeyRef = useRef(streamQueryKey);
  const packetQueryKeyRef = useRef(packetQueryKey);

  contextRef.current = { route, favoritesOnly, pauseNewStreams };
  streamsRef.current = streams;
  streamQueryKeyRef.current = streamQueryKey;
  packetQueryKeyRef.current = packetQueryKey;

  const goTo = useCallback((next: RouteState) => {
    const path = makePath(next);
    if (path !== `${window.location.pathname}${window.location.search}`) {
      window.history.pushState({}, "", path);
    }
    setRoute(next);
  }, []);

  const refreshServices = useCallback(async () => {
    try {
      setServices(await api.services());
    } catch (error) {
      setNotice(errorText(error));
    }
  }, []);

  const refreshPatterns = useCallback(async () => {
    try {
      setPatterns(await api.patterns());
    } catch (error) {
      setNotice(errorText(error));
    }
  }, []);

  useEffect(() => {
    const onPopState = () => setRoute(parseRoute());
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem(themeStorageKey, theme);
    } catch {
      setNotice("Браузер не сохраняет локальные настройки.");
    }
  }, [theme]);

  useEffect(() => {
    for (const [key, value] of [
      [pageSizeStorageKey, pageSize],
      [hexWidthStorageKey, hexWidth],
      [lineBaseStorageKey, lineBase],
      [hexdumpStorageKey, hexdumpMode],
    ] as const) {
      try {
        localStorage.setItem(key, String(value));
      } catch {
        setNotice("Браузер не сохраняет локальные настройки.");
        break;
      }
    }
  }, [hexWidth, hexdumpMode, lineBase, pageSize]);

  useEffect(() => {
    void refreshServices();
    void refreshPatterns();
    void api.pcapStarted().then(setPcapStarted).catch((error: unknown) => setNotice(errorText(error)));
  }, [refreshPatterns, refreshServices]);

  useEffect(() => {
    streamPageController.current?.abort();
    streamPageController.current = null;
    streamPageLock.current = null;
    historyWindowRef.current = false;
    setBrowsingHistory(false);
    setMoreStreamsLoading(false);

    const controller = new AbortController();
    const queryKey = streamQueryKey;
    setStreams([]);
    setStreamHasMore(false);
    setStreamsLoading(true);
    void api.streams(route.servicePort, {
      startingFrom: null,
      pageSize,
      pattern: route.patternId === undefined ? null : { id: route.patternId },
      favorites: favoritesOnly,
    }, controller.signal)
      .then((page) => {
        if (controller.signal.aborted || streamQueryKeyRef.current !== queryKey) {
          return;
        }
        setStreams(page);
        setStreamHasMore(page.length === pageSize);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted && streamQueryKeyRef.current === queryKey
            && !(error instanceof DOMException && error.name === "AbortError")) {
          setNotice(errorText(error));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted && streamQueryKeyRef.current === queryKey) {
          setStreamsLoading(false);
        }
      });
    return () => {
      controller.abort();
      streamPageController.current?.abort();
    };
  }, [favoritesOnly, pageSize, refreshKey, route.patternId, route.servicePort, streamQueryKey]);

  useEffect(() => {
    packetPageController.current?.abort();
    packetPageController.current = null;
    packetPageLock.current = null;
    setMorePacketsLoading(false);

    if (route.streamId === undefined) {
      setPackets([]);
      setPacketHasMore(false);
      setPacketsLoading(false);
      return;
    }
    const controller = new AbortController();
    const queryKey = packetQueryKey;
    setPackets([]);
    setPacketsLoading(true);
    setPacketHasMore(false);
    void api.packets(route.streamId, { startingFrom: null, pageSize }, controller.signal)
      .then((page) => {
        if (controller.signal.aborted || packetQueryKeyRef.current !== queryKey) {
          return;
        }
        setPackets(page);
        setPacketHasMore(page.length === pageSize);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted && packetQueryKeyRef.current === queryKey
            && !(error instanceof DOMException && error.name === "AbortError")) {
          setNotice(errorText(error));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted && packetQueryKeyRef.current === queryKey) {
          setPacketsLoading(false);
        }
      });
    return () => {
      controller.abort();
      packetPageController.current?.abort();
    };
  }, [pageSize, packetQueryKey, route.streamId]);

  const loadMoreStreams = useCallback(async () => {
    const latest = streamsRef.current.at(-1);
    const queryKey = streamQueryKeyRef.current;
    const routeSnapshot = contextRef.current.route;
    const favoritesSnapshot = contextRef.current.favoritesOnly;
    if (!streamHasMore || streamPageLock.current === queryKey || !latest) {
      return;
    }
    streamPageLock.current = queryKey;
    const controller = new AbortController();
    streamPageController.current = controller;
    setMoreStreamsLoading(true);
    try {
      const page = await api.streams(routeSnapshot.servicePort, {
        startingFrom: latest.id,
        pageSize,
        pattern: routeSnapshot.patternId === undefined
          ? null
          : { id: routeSnapshot.patternId },
        favorites: favoritesSnapshot,
      }, controller.signal);
      if (controller.signal.aborted || streamQueryKeyRef.current !== queryKey) {
        return;
      }
      const currentIds = new Set(streamsRef.current.map((stream) => stream.id));
      const unique = page.filter((stream) => !currentIds.has(stream.id));
      if (streamsRef.current.length + unique.length > maxStreamsInMemory) {
        historyWindowRef.current = true;
        setBrowsingHistory(true);
      }
      setStreams((current) => {
        if (streamQueryKeyRef.current !== queryKey) {
          return current;
        }
        const combined = [...current, ...unique];
        if (combined.length > maxStreamsInMemory) {
          return combined.slice(-maxStreamsInMemory);
        }
        return combined;
      });
      setStreamHasMore(unique.length > 0 && page.length === pageSize);
    } catch (error) {
      if (!controller.signal.aborted && streamQueryKeyRef.current === queryKey) {
        setNotice(errorText(error));
      }
    } finally {
      if (streamPageController.current === controller) {
        streamPageController.current = null;
      }
      if (streamPageLock.current === queryKey) {
        streamPageLock.current = null;
        if (streamQueryKeyRef.current === queryKey) {
          setMoreStreamsLoading(false);
        }
      }
    }
  }, [pageSize, streamHasMore]);

  const loadMorePackets = useCallback(async () => {
    const latest = packets.at(-1);
    const queryKey = packetQueryKeyRef.current;
    if (route.streamId === undefined || !packetHasMore
        || packetPageLock.current === queryKey || !latest) {
      return;
    }
    packetPageLock.current = queryKey;
    const controller = new AbortController();
    packetPageController.current = controller;
    setMorePacketsLoading(true);
    try {
      const page = await api.packets(route.streamId, { startingFrom: latest.id, pageSize }, controller.signal);
      if (controller.signal.aborted || packetQueryKeyRef.current !== queryKey) {
        return;
      }
      const currentIds = new Set(packets.map((packet) => packet.id));
      const unique = page.filter((packet) => !currentIds.has(packet.id));
      setPackets((current) => packetQueryKeyRef.current === queryKey ? [...current, ...unique] : current);
      setPacketHasMore(unique.length > 0 && page.length === pageSize);
    } catch (error) {
      if (!controller.signal.aborted && packetQueryKeyRef.current === queryKey) {
        setNotice(errorText(error));
      }
    } finally {
      if (packetPageController.current === controller) {
        packetPageController.current = null;
      }
      if (packetPageLock.current === queryKey) {
        packetPageLock.current = null;
        if (packetQueryKeyRef.current === queryKey) {
          setMorePacketsLoading(false);
        }
      }
    }
  }, [packetHasMore, packets, pageSize, route.streamId]);

  const handleSocketMessage = useCallback((message: MessageEvent<string>) => {
    let event: SubscriptionMessage;
    try {
      event = JSON.parse(message.data) as SubscriptionMessage;
    } catch {
      setNotice("Получено некорректное сообщение от сервера.");
      return;
    }
    const context = contextRef.current;
    switch (event.type) {
      case "NEW_STREAM": {
        if (!isRecord(event.value) || typeof event.value.id !== "number") {
          return;
        }
        const stream = event.value as unknown as Stream;
        if (context.pauseNewStreams || (context.route.servicePort !== undefined && stream.service !== context.route.servicePort)) {
          return;
        }
        if (context.favoritesOnly && !stream.favorite) {
          return;
        }
        if (context.route.patternId !== undefined && !stream.foundPatternsIds.includes(context.route.patternId)) {
          return;
        }
        if (historyWindowRef.current) {
          setNotice((current) => current || "Вы просматриваете историю. Вернитесь к новым потокам, чтобы следить за текущими.");
          return;
        }
        setStreams((current) => [stream, ...current.filter((item) => item.id !== stream.id)]
          .slice(0, maxStreamsInMemory));
        break;
      }
      case "SAVE_SERVICE":
      case "DELETE_SERVICE":
        void refreshServices();
        break;
      case "SAVE_PATTERN":
        void refreshPatterns();
        break;
      case "ENABLE_PATTERN":
      case "DISABLE_PATTERN": {
        const id = typeof event.value === "number" ? event.value : Number(event.value);
        if (Number.isInteger(id)) {
          setPatterns((current) => current.map((pattern) =>
            pattern.id === id ? { ...pattern, enabled: event.type === "ENABLE_PATTERN" } : pattern,
          ));
        }
        break;
      }
      case "COUNTERS_UPDATE":
        if (isRecord(event.value)) {
          setCounters(event.value as unknown as CountersUpdate);
        }
        break;
      case "PCAP_STARTED":
        setPcapStarted(true);
        setNotice("Обработка PCAP-файла началась.");
        break;
      case "PCAP_STOPPED":
        setPcapStarted(false);
        setNotice("Обработка PCAP завершена.");
        break;
      case "FINISH_LOOKBACK":
        setNotice("Поиск в старых потоках завершен.");
        setRefreshKey((current) => current + 1);
        break;
      default:
        break;
    }
  }, [refreshPatterns, refreshServices]);

  const socketMessageRef = useRef(handleSocketMessage);
  socketMessageRef.current = handleSocketMessage;

  useEffect(() => {
    let disposed = false;
    let socket: WebSocket | undefined;
    let reconnectTimer: number | undefined;

    const connect = () => {
      if (disposed) {
        return;
      }
      setSocketStatus("Подключение к событиям...");
      const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
      socket = new WebSocket(`${protocol}//${window.location.host}/api/ws`);
      socket.onopen = () => setSocketStatus("События подключены");
      socket.onmessage = (event) => socketMessageRef.current(event);
      socket.onerror = () => setSocketStatus("Ожидание повторного подключения...");
      socket.onclose = () => {
        if (disposed) {
          return;
        }
        setSocketStatus("Связь с событиями прервана; повторное подключение...");
        reconnectTimer = window.setTimeout(connect, 3000);
      };
    };

    connect();
    return () => {
      disposed = true;
      if (reconnectTimer !== undefined) {
        window.clearTimeout(reconnectTimer);
      }
      socket?.close();
    };
  }, []);

  const visibleStreams = useMemo(
    () => streams.filter((stream) => streamMatches(stream, searchText, services)),
    [searchText, services, streams],
  );
  const selectedStream = streams.find((stream) => stream.id === route.streamId);
  const selectedPattern = patterns.find((pattern) => pattern.id === route.patternId);
  const activePatterns = patterns.filter((pattern) => !pattern.deleted);
  const serviceByPort = useMemo(() => new Map(services.map((service) => [service.port, service])), [services]);
  const streamsPerMinute = counters.totalStreams ?? 0;
  const packetsPerStream = streamsPerMinute === 0
    ? 0
    : Math.round((counters.totalPackets / streamsPerMinute + Number.EPSILON) * 10) / 10;

  const showServiceDialog = (service?: Service) => {
    setServiceDialog(service
      ? { mode: "edit", value: { ...service } }
      : { mode: "create", value: emptyService() });
  };

  const showPatternDialog = (pattern?: Pattern) => {
    setPatternDialog(pattern
      ? { mode: "edit", value: { ...pattern } }
      : { mode: "create", value: emptyPattern() });
  };

  const saveService = async (service: Service) => {
    try {
      if (serviceDialog?.mode === "edit") {
        await api.updateService(serviceDialog.value.port, service);
      } else {
        await api.createService(service);
      }
      setServiceDialog(null);
      await refreshServices();
      setNotice(serviceDialog?.mode === "edit" ? "Сервис обновлен." : "Сервис создан.");
    } catch (error) {
      setNotice(errorText(error));
    }
  };

  const deleteService = async (service: Service) => {
    if (!window.confirm(`Удалить сервис "${service.name}" и его фильтр?`)) {
      return;
    }
    try {
      await api.deleteService(service.port);
      setServiceDialog(null);
      await refreshServices();
      if (route.servicePort === service.port) {
        goTo({ patternId: route.patternId });
      }
      setNotice("Сервис удален.");
    } catch (error) {
      setNotice(errorText(error));
    }
  };

  const savePattern = async (pattern: PatternInput) => {
    try {
      if (patternDialog?.mode === "edit" && pattern.id !== undefined) {
        await api.updatePattern(pattern.id, { name: pattern.name, color: pattern.color });
      } else {
        await api.createPattern(pattern);
      }
      setPatternDialog(null);
      await refreshPatterns();
      setNotice(patternDialog?.mode === "edit" ? "Паттерн обновлен." : "Паттерн создан.");
    } catch (error) {
      setNotice(errorText(error));
    }
  };

  const togglePattern = async (pattern: Pattern) => {
    try {
      await api.setPatternEnabled(pattern.id, !pattern.enabled);
      setPatterns((current) => current.map((item) => item.id === pattern.id
        ? { ...item, enabled: !pattern.enabled }
        : item));
      setNotice(pattern.enabled ? "Паттерн приостановлен." : "Паттерн включен.");
    } catch (error) {
      setNotice(errorText(error));
    }
  };

  const deletePattern = async (pattern: Pattern) => {
    if (!window.confirm(`Удалить паттерн "${pattern.name}"?`)) {
      return;
    }
    try {
      await api.deletePattern(pattern.id);
      await refreshPatterns();
      if (route.patternId === pattern.id) {
        goTo({ servicePort: route.servicePort });
      }
      setNotice("Паттерн удален.");
    } catch (error) {
      setNotice(errorText(error));
    }
  };

  const openPattern = (pattern: Pattern) => {
    if (pattern.actionType === "FIND") {
      goTo({ servicePort: route.servicePort, patternId: pattern.id });
    }
  };

  const toggleFavorite = async (stream: Stream) => {
    try {
      await api.setFavorite(stream.id, !stream.favorite);
      setStreams((current) => current.map((item) => item.id === stream.id
        ? { ...item, favorite: !stream.favorite }
        : item));
      if (favoritesOnly && stream.favorite) {
        setRefreshKey((current) => current + 1);
      }
    } catch (error) {
      setNotice(errorText(error));
    }
  };

  const startPcap = async () => {
    setPcapStarted(true);
    try {
      await api.startPcap();
      setNotice("Команда запуска PCAP отправлена.");
    } catch (error) {
      setPcapStarted(false);
      setNotice(errorText(error));
    }
  };

  const runLookBack = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (lookBackPattern === null || lookBackMinutes < 1) {
      return;
    }
    try {
      await api.lookBack(lookBackPattern, lookBackMinutes);
      setLookBackPattern(null);
      setNotice("Поиск в старых потоках запущен.");
    } catch (error) {
      setNotice(errorText(error));
    }
  };

  const goToStream = (stream: Stream) => {
    goTo({ servicePort: stream.service, streamId: stream.id, patternId: route.patternId });
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!event.ctrlKey || ["INPUT", "TEXTAREA", "SELECT"].includes((event.target as HTMLElement | null)?.tagName ?? "")) {
        return;
      }
      const currentIndex = visibleStreams.findIndex((stream) => stream.id === route.streamId);
      let next: Stream | undefined;
      if (event.key === "ArrowUp") {
        event.preventDefault();
        next = visibleStreams[currentIndex - 1];
      } else if (event.key === "ArrowDown") {
        event.preventDefault();
        next = visibleStreams[currentIndex + 1];
      } else if (event.key === "Home") {
        next = visibleStreams[0];
      } else if (event.key === "End") {
        next = visibleStreams.at(-1);
      }
      if (next) {
        goToStream(next);
        window.setTimeout(() => document.getElementById(`stream-${next?.id}`)?.scrollIntoView({
          behavior: "smooth",
          block: "center",
        }), 0);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [goToStream, route.streamId, visibleStreams]);

  const favoriteCount = streams.filter((stream) => stream.favorite).length;
  const returnToLatest = () => {
    historyWindowRef.current = false;
    setBrowsingHistory(false);
    setRefreshKey((current) => current + 1);
  };

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><img src="/logo.jpg" alt="" />wahrwelt <small>/ Packmate</small></div>
        <div className="traffic-stats" aria-label="Скорость обработки">
          <span>{streamsPerMinute} <abbr title="Потоки в минуту">SPM</abbr></span>
          <span className="stats-separator">,</span>
          <span>{packetsPerStream} <abbr title="Пакеты на поток в среднем">PPS</abbr></span>
        </div>
        <details className="pattern-menu">
          <summary>Паттерны</summary>
          <div className="pattern-popover">
            <button type="button" className="pattern-add" onClick={() => showPatternDialog()}>
              + Создать паттерн
            </button>
            <button type="button" className="pattern-all" onClick={() => goTo({ servicePort: route.servicePort })}>
              Все потоки
            </button>
            {activePatterns.length === 0 && <p className="muted empty-patterns">Паттернов пока нет.</p>}
            {activePatterns.map((pattern) => (
              <div className={`pattern-row ${pattern.actionType === "IGNORE" ? "pattern-ignore" : ""}`} key={pattern.id}>
                <button
                  type="button"
                  className="pattern-select"
                  disabled={pattern.actionType === "IGNORE"}
                  onClick={() => openPattern(pattern)}
                  title={pattern.actionType === "IGNORE" ? "Паттерн исключает совпадающие потоки" : "Показать потоки с этим паттерном"}
                >
                  <strong className={pattern.enabled ? "" : "disabled-pattern"} style={{ color: pattern.actionType === "FIND" ? pattern.color : undefined }}>
                    {pattern.name}
                  </strong>
                  <span>: {patternSummary(pattern, services)}</span>
                </button>
                <div className="pattern-actions">
                  <button type="button" aria-label={`Изменить паттерн ${pattern.name}`} onClick={() => showPatternDialog(pattern)}>Изменить</button>
                  {pattern.actionType === "FIND" && (
                    <button type="button" aria-label={`Поиск в старых потоках для ${pattern.name}`} onClick={() => setLookBackPattern(pattern.id)}>Назад</button>
                  )}
                  <button type="button" aria-label={pattern.enabled ? `Приостановить ${pattern.name}` : `Включить ${pattern.name}`} onClick={() => void togglePattern(pattern)}>
                    {pattern.enabled ? "Пауза" : "Пуск"}
                  </button>
                  <button type="button" aria-label={`Удалить паттерн ${pattern.name}`} onClick={() => void deletePattern(pattern)}>Удалить</button>
                </div>
              </div>
            ))}
          </div>
        </details>
        <nav className="service-tabs" aria-label="Сервисы">
          <button
            type="button"
            className={route.servicePort === undefined ? "active" : ""}
            onClick={() => goTo({ patternId: route.patternId })}
          >
            Все
          </button>
          {services.map((service) => (
            <div className={`service-tab ${route.servicePort === service.port ? "active" : ""}`} key={service.port}>
              <button type="button" onClick={() => goTo({ servicePort: service.port, patternId: route.patternId })}>
                {service.name} #{service.port} <small>({counters.servicesStreams[String(service.port)] ?? 0} SPM)</small>
              </button>
              <button type="button" className="service-edit" aria-label={`Изменить сервис ${service.name}`} onClick={() => showServiceDialog(service)}>✎</button>
            </div>
          ))}
          <button type="button" className="add-service" aria-label="Добавить сервис" onClick={() => showServiceDialog()}>+</button>
        </nav>
        {selectedPattern && <span className="selected-pattern">Фильтр: {selectedPattern.name}</span>}
        <button type="button" className="settings-button" aria-label="Настройки" onClick={() => setSettingsOpen(true)}>⚙</button>
      </header>

      <main className="workspace">
        <aside className="sidebar" aria-label="Список потоков">
          <div className="sidebar-toolbar">
            <button
              type="button"
              className={`tool-button pause-button ${pauseNewStreams ? "selected" : ""}`}
              title={pauseNewStreams ? "Продолжить показ новых потоков" : "Приостановить список"}
              aria-pressed={pauseNewStreams}
              onClick={() => setPauseNewStreams((paused) => !paused)}
            >
              {pauseNewStreams ? "▶" : "Ⅱ"}
            </button>
            <button
              type="button"
              className={`tool-button favorite-button ${favoritesOnly ? "selected" : ""}`}
              title={favoritesOnly ? "Показать все потоки" : "Показывать только избранные потоки"}
              aria-pressed={favoritesOnly}
              onClick={() => setFavoritesOnly((value) => !value)}
            >
              {favoritesOnly ? "★" : "☆"}
              <span className="sr-only">Избранные: {favoriteCount}</span>
            </button>
            <button
              type="button"
              className={`tool-button hex-button ${hexdumpMode ? "selected" : ""}`}
              title={hexdumpMode ? "Показать текст" : "Показать HEX-дамп"}
              aria-pressed={hexdumpMode}
              onClick={() => setHexdumpMode((value) => !value)}
            >
              {hexdumpMode ? "ТЕКСТ" : "HEX"}
            </button>
            {!pcapStarted && (
              <button type="button" className="tool-button file-button" title="Начать обработку PCAP-файла" onClick={() => void startPcap()}>
                Старт FILE
              </button>
            )}
            <button type="button" className="tool-button to-top-button" title="Прокрутить список вверх" onClick={() => streamListRef.current?.scrollTo({ top: 0, behavior: "smooth" })}>⌃</button>
          </div>
          <label className="stream-search">
            <span className="sr-only">Найти в загруженных потоках</span>
            <input type="search" value={searchText} onChange={(event) => setSearchText(event.target.value)} placeholder="Найти поток..." />
            {searchText && <button type="button" aria-label="Очистить поиск" onClick={() => setSearchText("")}>×</button>}
          </label>
          <section className="stream-list" ref={streamListRef}>
            {streamsLoading && <p className="muted list-message">Загрузка потоков...</p>}
            {!streamsLoading && visibleStreams.length === 0 && (
              <p className="muted list-message">{searchText ? "Совпадений нет." : "Потоков пока нет."}</p>
            )}
            <ul>
              {visibleStreams.map((stream) => {
                const matchedPatterns = stream.foundPatternsIds
                  .map((id) => patterns.find((pattern) => pattern.id === id))
                  .filter((pattern): pattern is Pattern => Boolean(pattern && !pattern.deleted));
                const selected = route.streamId === stream.id;
                return (
                  <li id={`stream-${stream.id}`} className={`stream-item ${selected ? "stream-selected" : ""} ${stream.favorite ? "stream-favorite" : ""}`} key={stream.id}>
                    <button type="button" className="stream-select" onClick={() => goToStream(stream)}>
                      <strong>#{stream.id} {stream.protocol}</strong>
                      {stream.ttl > 0 && <span className="stream-ttl">TTL {stream.ttl}</span>}
                      {route.servicePort === undefined && <span>{serviceName(services, stream.service)} #{stream.service}</span>}
                      <time>{new Intl.DateTimeFormat("ru-RU", { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(stream.startTimestamp))}</time>
                      {stream.userAgentHash && <span>UA: {stream.userAgentHash}</span>}
                      <span>{stream.sizeBytes} байт в {stream.packetsCount} пакетах</span>
                      {matchedPatterns.length > 0 && (
                        <span className="stream-patterns">
                          {matchedPatterns.map((pattern) => (
                            <span key={pattern.id} style={{ color: pattern.actionType === "FIND" ? pattern.color : undefined }}>{pattern.name} </span>
                          ))}
                        </span>
                      )}
                    </button>
                    <button
                      type="button"
                      className={`stream-star ${stream.favorite ? "is-favorite" : ""}`}
                      aria-label={stream.favorite ? `Убрать поток ${stream.id} из избранного` : `Добавить поток ${stream.id} в избранное`}
                      aria-pressed={stream.favorite}
                      onClick={() => void toggleFavorite(stream)}
                    >
                      {stream.favorite ? "★" : "☆"}
                    </button>
                  </li>
                );
              })}
            </ul>
            {browsingHistory && (
              <button type="button" className="load-more" onClick={returnToLatest}>
                Вернуться к новым потокам
              </button>
            )}
            {streamHasMore && (
              <button type="button" className="load-more" onClick={() => void loadMoreStreams()} disabled={moreStreamsLoading}>
                {moreStreamsLoading ? "Загрузка..." : "Загрузить еще потоки"}
              </button>
            )}
            {!streamHasMore && !streamsLoading && streams.length > 0 && <p className="muted list-message">Больше данных нет</p>}
          </section>
        </aside>

        <section className="packet-pane" aria-label="Пакеты выбранного потока">
          {notice && (
            <div className="notice" role="status">
              <span>{notice}</span>
              <button type="button" aria-label="Скрыть сообщение" onClick={() => setNotice("")}>×</button>
            </div>
          )}
          <div className="packet-legend">
            <span className="legend-request">Запрос</span>
            <span>, </span>
            <span className="legend-response">ответ</span>
            <span className="socket-state">{socketStatus}</span>
            <button type="button" className="theme-toggle" onClick={() => setTheme((current) => current === "dark" ? "light" : "dark")}>
              {theme === "dark" ? "Светлая тема" : "Темная тема"}
            </button>
          </div>
          {selectedStream && (
            <div className="stream-heading">
              <strong>Поток #{selectedStream.id}</strong>
              <span>{selectedStream.protocol} / {serviceByPort.get(selectedStream.service)?.name ?? "Удаленный сервис"} #{selectedStream.service}</span>
              <span>{selectedStream.sizeBytes} байт, {selectedStream.packetsCount} пакетов</span>
            </div>
          )}
          {route.streamId === undefined && <p className="empty-detail">Выберите поток слева, чтобы посмотреть пакеты.</p>}
          {route.streamId !== undefined && packetsLoading && <p className="empty-detail">Загрузка пакетов...</p>}
          {route.streamId !== undefined && !packetsLoading && packets.length === 0 && <p className="empty-detail">В этом потоке пока нет пакетов.</p>}
          {packets.map((packet, index) => (
            <PacketView
              key={packet.id}
              packet={packet}
              previous={index === 0 ? undefined : packets[index - 1]}
              patterns={patterns}
              hexdumpMode={hexdumpMode}
              blockSize={hexWidth}
              lineNumberBase={lineBase}
              notify={setNotice}
            />
          ))}
          {packetHasMore && (
            <button type="button" className="load-more packet-more" onClick={() => void loadMorePackets()} disabled={morePacketsLoading}>
              {morePacketsLoading ? "Загрузка..." : "Загрузить еще пакеты"}
            </button>
          )}
          {route.streamId !== undefined && !packetHasMore && packets.length > 0 && <p className="muted packet-end">Больше пакетов нет</p>}
        </section>
      </main>

      {serviceDialog && (
        <ServiceDialog
          state={serviceDialog}
          onClose={() => setServiceDialog(null)}
          onSave={(service) => void saveService(service)}
          onDelete={(service) => void deleteService(service)}
        />
      )}
      {patternDialog && (
        <PatternDialog
          state={patternDialog}
          services={services}
          onClose={() => setPatternDialog(null)}
          onSave={(pattern) => void savePattern(pattern)}
        />
      )}
      {lookBackPattern !== null && (
        <Overlay title="Поиск в старых потоках" onClose={() => setLookBackPattern(null)}>
          <form onSubmit={(event) => void runLookBack(event)}>
            <label className="form-field">
              <span>Сколько минут назад искать</span>
              <input type="number" min="1" max="10080" required value={lookBackMinutes} onChange={(event) => setLookBackMinutes(Number(event.target.value))} />
              <small>Совпадения появятся в списке потоков.</small>
            </label>
            <div className="dialog-actions">
              <button type="button" className="button-secondary" onClick={() => setLookBackPattern(null)}>Отмена</button>
              <button type="submit" className="button-primary">Запустить поиск</button>
            </div>
          </form>
        </Overlay>
      )}
      {settingsOpen && (
        <Overlay title="Настройки" onClose={() => setSettingsOpen(false)}>
          <div className="settings-fields">
            <label className="form-field">
              <span>Ширина строки HEX-дампа</span>
              <input type="number" min="1" max="128" value={hexWidth} onChange={(event) => setHexWidth(readInputNumber(event.target.value, 16, 1, 128))} />
              <small>Количество байтов в одной строке.</small>
            </label>
            <label className="form-field">
              <span>Система нумерации</span>
              <select value={lineBase} onChange={(event) => setLineBase(Number(event.target.value))}>
                <option value={10}>Десятичная</option>
                <option value={16}>Шестнадцатеричная</option>
              </select>
              <small>Формат адресов строк в HEX-дампе.</small>
            </label>
            <label className="form-field">
              <span>Размер страницы</span>
              <input type="number" min="10" max="500" value={pageSize} onChange={(event) => setPageSize(readInputNumber(event.target.value, 100, 10, 500))} />
              <small>Число потоков и пакетов, запрашиваемых за один раз.</small>
            </label>
          </div>
          <div className="dialog-actions">
            <button type="button" className="button-primary" onClick={() => setSettingsOpen(false)}>Готово</button>
          </div>
        </Overlay>
      )}
      <footer className="statusbar" aria-live="polite">
        {socketStatus} | {counters.totalPackets} пакетов за прошлую минуту
      </footer>
    </div>
  );
}

function readInputNumber(value: string, fallback: number, minimum: number, maximum: number): number {
  if (value === "") {
    return fallback;
  }
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= minimum && parsed <= maximum ? parsed : fallback;
}

interface OverlayProps {
  title: string;
  children: ReactNode;
  onClose: () => void;
}

function Overlay({ title, children, onClose }: OverlayProps) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) {
        onClose();
      }
    }}>
      <section className="dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title">
        <header className="dialog-header">
          <h2 id="dialog-title">{title}</h2>
          <button type="button" className="dialog-close" aria-label="Закрыть" onClick={onClose}>×</button>
        </header>
        <div className="dialog-body">{children}</div>
      </section>
    </div>
  );
}

interface ServiceDialogProps {
  state: ServiceDialogState;
  onClose: () => void;
  onSave: (service: Service) => void;
  onDelete: (service: Service) => void;
}

function ServiceDialog({ state, onClose, onSave, onDelete }: ServiceDialogProps) {
  const [service, setService] = useState(state.value);
  useEffect(() => setService(state.value), [state.value]);
  const updateBoolean = (field: keyof Service, checked: boolean) => {
    setService((current) => ({ ...current, [field]: checked }));
  };

  return (
    <Overlay title={state.mode === "create" ? "Создание сервиса" : "Изменение сервиса"} onClose={onClose}>
      <form onSubmit={(event) => { event.preventDefault(); onSave(service); }}>
        <label className="form-field">
          <span>Название</span>
          <input autoFocus required maxLength={100} value={service.name} onChange={(event) => setService({ ...service, name: event.target.value })} />
        </label>
        {state.mode === "create" ? (
          <label className="form-field">
            <span>Порт приложения</span>
            <input type="number" required min="1" max="65535" value={service.port || ""} onChange={(event) => setService({ ...service, port: Number(event.target.value) })} />
          </label>
        ) : (
          <div className="form-field"><span>Порт</span><strong>{service.port}</strong></div>
        )}
        <fieldset className="service-options">
          <legend>Обработка трафика</legend>
          <label><input type="checkbox" checked={service.http} onChange={(event) => updateBoolean("http", event.target.checked)} /> Обрабатывать HTTP</label>
          <label><input type="checkbox" checked={service.urldecodeHttpRequests} onChange={(event) => updateBoolean("urldecodeHttpRequests", event.target.checked)} /> Декодировать URL в запросах</label>
          <label><input type="checkbox" checked={service.mergeAdjacentPackets} onChange={(event) => updateBoolean("mergeAdjacentPackets", event.target.checked)} /> Объединять соседние пакеты</label>
          <label><input type="checkbox" checked={service.parseWebSockets} onChange={(event) => updateBoolean("parseWebSockets", event.target.checked)} /> Разбирать WebSocket</label>
          <label><input type="checkbox" checked={service.decryptTls} onChange={(event) => updateBoolean("decryptTls", event.target.checked)} /> Расшифровывать TLS_RSA_WITH_AES</label>
        </fieldset>
        <div className="dialog-actions">
          {state.mode === "edit" && <button type="button" className="button-danger dialog-delete" onClick={() => onDelete(service)}>Удалить сервис</button>}
          <button type="button" className="button-secondary" onClick={onClose}>Отмена</button>
          <button type="submit" className="button-primary">Сохранить</button>
        </div>
      </form>
    </Overlay>
  );
}

interface PatternDialogProps {
  state: PatternDialogState;
  services: Service[];
  onClose: () => void;
  onSave: (pattern: PatternInput) => void;
}

function PatternDialog({ state, services, onClose, onSave }: PatternDialogProps) {
  const [pattern, setPattern] = useState(state.value);
  const [validationMessage, setValidationMessage] = useState("");
  useEffect(() => {
    setPattern(state.value);
    setValidationMessage("");
  }, [state.value]);

  const setField = <K extends keyof PatternInput>(field: K, value: PatternInput[K]) => {
    setPattern((current) => ({ ...current, [field]: value }));
  };

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (state.mode === "create" && pattern.searchType === "SUBBYTES") {
      const normalized = pattern.value.replace(/\s+/g, "");
      if (!/^(?:[\da-f]{2})+$/i.test(normalized)) {
        setValidationMessage("Введите непустую последовательность байтов: пары шестнадцатеричных цифр.");
        return;
      }
      onSave({ ...pattern, value: normalized.toLowerCase() });
      return;
    }
    onSave(pattern);
  };

  return (
    <Overlay title={state.mode === "create" ? "Создание паттерна" : "Изменение паттерна"} onClose={onClose}>
      <form onSubmit={submit}>
        <label className="form-field">
          <span>Название</span>
          <input autoFocus required maxLength={100} value={pattern.name} onChange={(event) => setField("name", event.target.value)} />
          <small>Отображается в списке и в подсветке пакетов.</small>
        </label>
        {state.mode === "create" && (
          <>
            <label className="form-field">
              <span>Искомое значение</span>
              <input
                required
                value={pattern.value}
                placeholder={pattern.searchType === "REGEX" ? "[A-Z0-9]{31}=" : pattern.searchType === "SUBBYTES" ? "DE AD BE EF" : "HTTP/2"}
                onChange={(event) => { setField("value", event.target.value); setValidationMessage(""); }}
              />
              <small>
                {pattern.searchType === "REGEX"
                  ? "RE2/J не поддерживает lookaround и обратные ссылки."
                  : "Подстрока, регулярное выражение или байты."}
              </small>
            </label>
            <label className="form-field">
              <span>Действие</span>
              <select value={pattern.actionType} onChange={(event) => setField("actionType", event.target.value as PatternActionType)}>
                <option value="FIND">Подсвечивать совпадения</option>
                <option value="IGNORE">Игнорировать совпадающие потоки</option>
              </select>
              <small>Определяет действие Packmate при совпадении.</small>
            </label>
            <label className="form-field">
              <span>Способ поиска</span>
              <select value={pattern.searchType} onChange={(event) => setField("searchType", event.target.value as PatternSearchType)}>
                <option value="REGEX">Регулярное выражение</option>
                <option value="SUBSTRING">Подстрока</option>
                <option value="SUBBYTES">Байты</option>
              </select>
            </label>
            <label className="form-field">
              <span>Где искать</span>
              <select value={pattern.directionType} onChange={(event) => setField("directionType", event.target.value as PatternDirectionType)}>
                <option value="BOTH">В запросе и ответе</option>
                <option value="INPUT">Только в запросе</option>
                <option value="OUTPUT">Только в ответе</option>
              </select>
            </label>
            <label className="form-field">
              <span>Сервис</span>
              <select value={pattern.serviceId ?? ""} onChange={(event) => setField("serviceId", event.target.value === "" ? null : Number(event.target.value))}>
                <option value="">Любой сервис</option>
                {services.map((service) => <option value={service.port} key={service.port}>{service.name} #{service.port}</option>)}
              </select>
              <small>Оставьте пустым, чтобы искать во всех сервисах.</small>
            </label>
          </>
        )}
        {pattern.actionType === "FIND" && (
          <label className="form-field color-field">
            <span>Цвет подсветки</span>
            <input type="color" required value={pattern.color} onChange={(event) => setField("color", event.target.value)} />
          </label>
        )}
        {validationMessage && <p className="validation-message" role="alert">{validationMessage}</p>}
        <div className="dialog-actions">
          <button type="button" className="button-secondary" onClick={onClose}>Отмена</button>
          <button type="submit" className="button-primary">Сохранить</button>
        </div>
      </form>
    </Overlay>
  );
}

export default App;
