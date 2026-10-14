export type PatternSearchType = "REGEX" | "SUBSTRING" | "SUBBYTES";
export type PatternDirectionType = "INPUT" | "OUTPUT" | "BOTH";
export type PatternActionType = "FIND" | "IGNORE";

export interface Service {
  port: number;
  name: string;
  decryptTls: boolean;
  http: boolean;
  urldecodeHttpRequests: boolean;
  mergeAdjacentPackets: boolean;
  parseWebSockets: boolean;
}

export interface Pattern {
  id: number;
  enabled: boolean;
  deleted: boolean;
  name: string;
  value: string;
  color: string;
  searchType: PatternSearchType;
  directionType: PatternDirectionType;
  actionType: PatternActionType;
  serviceId: number | null;
}

export interface PatternInput extends Omit<Pattern, "id" | "enabled" | "deleted"> {
  id?: number;
}

export interface Stream {
  id: number;
  service: number;
  protocol: string;
  startTimestamp: number;
  endTimestamp: number;
  foundPatternsIds: number[];
  favorite: boolean;
  ttl: number;
  userAgentHash: string | null;
  sizeBytes: number;
  packetsCount: number;
}

export interface FoundPattern {
  patternId: number;
  startPosition: number;
  endPosition: number;
}

export interface Packet {
  id: number;
  matches: FoundPattern[];
  timestamp: number;
  incoming: boolean;
  ungzipped: boolean;
  webSocketParsed: boolean;
  tlsDecrypted: boolean;
  hasHttpBody: boolean;
  content: string;
}

export interface RouteState {
  servicePort?: number;
  streamId?: number;
  patternId?: number;
}

export interface CountersUpdate {
  totalPackets: number;
  totalStreams: number;
  servicesPackets: Record<string, number>;
  servicesStreams: Record<string, number>;
}

export interface SubscriptionMessage {
  type: string;
  value: unknown;
}
