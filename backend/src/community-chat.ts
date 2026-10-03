import { createHash } from 'node:crypto';
import { chmodSync, closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { z } from 'zod';
import type { PrivyPrincipal } from './privy-auth.js';

export const SPXTR_CHAT_ROOM = 'spxtr' as const;
const maximumMessages = 500;
const maximumResponseMessages = 50;
const maximumHistoryBytes = 4 * 1_024 * 1_024;
const StoredMessageSchema = z.object({
  id: z.number().int().positive().safe(),
  authorKey: z.string().regex(/^[a-f0-9]{64}$/),
  text: z.string().min(1).max(500),
  createdAt: z.string().datetime(),
}).strict();
const ChatFileSchema = z.object({
  schemaVersion: z.literal(1),
  nextId: z.number().int().positive().safe(),
  messages: z.array(StoredMessageSchema).max(maximumMessages),
  joinedAuthors: z.array(z.string().regex(/^[a-f0-9]{64}$/)).max(10_000),
  // Optional so persisted version 1 chat files written before resets still load.
  deletionVersion: z.number().int().nonnegative().safe().optional(),
}).strict().refine(value => value.messages.every((message, index) =>
  message.id < value.nextId && (index === 0 || message.id > value.messages[index - 1]!.id)) &&
  new Set(value.joinedAuthors).size === value.joinedAuthors.length);
type StoredMessage = z.infer<typeof StoredMessageSchema>;
type ChatFile = z.infer<typeof ChatFileSchema>;

export const ChatMessageRequestSchema = z.object({
  text: z.string().trim().min(1).max(500).refine(value => !/[\u0000-\u0008\u000b-\u001f\u007f]/.test(value)),
}).strict();
export const ChatCursorSchema = z.object({ after: z.string().regex(/^(0|[1-9]\d{0,14})$/).optional() }).strict();

export interface CommunityChatMessage {
  id: string;
  authorName: string;
  text: string;
  createdAt: string;
  isMine: boolean;
}

export class CommunityChatStorageError extends Error {
  readonly code = 'chat_storage_unavailable';
  readonly statusCode = 503;
  constructor() { super('Chat is temporarily unavailable. Try again later.'); }
}

export class CommunityChatAccessError extends Error {
  readonly code = 'chat_join_required';
  readonly statusCode = 403;
  constructor() { super('Join this chat before sending a message.'); }
}

function subjectKey(principal: PrivyPrincipal): string {
  return createHash('sha256').update(principal.subject).digest('hex');
}

function publicMessage(message: StoredMessage, viewerKey: string): CommunityChatMessage {
  return {
    id: String(message.id),
    authorName: `Member ${message.authorKey.slice(0, 6).toUpperCase()}`,
    text: message.text,
    createdAt: message.createdAt,
    isMine: message.authorKey === viewerKey,
  };
}

/** One SPXTR UI room only. No wallet address, balance, or on-chain ownership is asserted here. */
export class CommunityChatStore {
  private data: ChatFile = { schemaVersion: 1, nextId: 1, messages: [], joinedAuthors: [] };
  private healthy = true;

  constructor(private readonly filename: string | null = resolve('runtime/private-community-chat-spxtr.json'),
    private readonly now = Date.now) {
    if (filename === null) return;
    try {
      if (existsSync(filename)) {
        const raw = readFileSync(filename, 'utf8');
        if (Buffer.byteLength(raw) > maximumHistoryBytes) throw new Error('Chat history too large');
        this.data = ChatFileSchema.parse(JSON.parse(raw));
        chmodSync(filename, 0o600);
      }
    } catch { this.healthy = false; }
  }

  list(principal: PrivyPrincipal, after = 0): { schemaVersion: 1; roomId: typeof SPXTR_CHAT_ROOM;
    messages: CommunityChatMessage[]; nextCursor: string; canPost: boolean; deletionVersion: number } {
    this.requireHealthy();
    const messages = after === 0
      ? this.data.messages.slice(-maximumResponseMessages)
      : this.data.messages.filter(message => message.id > after).slice(0, maximumResponseMessages);
    const viewerKey = subjectKey(principal);
    return {
      schemaVersion: 1,
      roomId: SPXTR_CHAT_ROOM,
      messages: messages.map(message => publicMessage(message, viewerKey)),
      nextCursor: String(messages.at(-1)?.id ?? (after > this.data.nextId ? this.data.nextId - 1 : after)),
      canPost: this.data.joinedAuthors.includes(viewerKey),
      deletionVersion: this.data.deletionVersion ?? 0,
    };
  }

  join(principal: PrivyPrincipal): { schemaVersion: 1; roomId: typeof SPXTR_CHAT_ROOM; canPost: true } {
    this.requireHealthy();
    const authorKey = subjectKey(principal);
    if (!this.data.joinedAuthors.includes(authorKey)) {
      if (this.data.joinedAuthors.length >= 10_000) throw new CommunityChatStorageError();
      const next: ChatFile = { ...this.data, joinedAuthors: [...this.data.joinedAuthors, authorKey] };
      if (this.filename !== null) {
        try { this.atomicWrite(next); }
        catch { this.healthy = false; throw new CommunityChatStorageError(); }
      }
      this.data = next;
    }
    return { schemaVersion: 1, roomId: SPXTR_CHAT_ROOM, canPost: true };
  }

  post(principal: PrivyPrincipal, text: string): { schemaVersion: 1; roomId: typeof SPXTR_CHAT_ROOM;
    message: CommunityChatMessage } {
    this.requireHealthy();
    if (this.data.nextId >= Number.MAX_SAFE_INTEGER) throw new CommunityChatStorageError();
    const authorKey = subjectKey(principal);
    if (!this.data.joinedAuthors.includes(authorKey)) throw new CommunityChatAccessError();
    const message: StoredMessage = { id: this.data.nextId, authorKey, text, createdAt: new Date(this.now()).toISOString() };
    const next: ChatFile = { schemaVersion: 1, nextId: this.data.nextId + 1,
      messages: [...this.data.messages.slice(-(maximumMessages - 1)), message],
      joinedAuthors: this.data.joinedAuthors, deletionVersion: this.data.deletionVersion };
    if (this.filename !== null) {
      try { this.atomicWrite(next); }
      catch { this.healthy = false; throw new CommunityChatStorageError(); }
    }
    this.data = next;
    return { schemaVersion: 1, roomId: SPXTR_CHAT_ROOM, message: publicMessage(message, authorKey) };
  }

  /** Forget only the authenticated author's posts and participation in this UI room. */
  clearMine(principal: PrivyPrincipal): { schemaVersion: 1; roomId: typeof SPXTR_CHAT_ROOM; deletedCount: number } {
    this.requireHealthy();
    const authorKey = subjectKey(principal);
    const messages = this.data.messages.filter(message => message.authorKey !== authorKey);
    const deletedCount = this.data.messages.length - messages.length;
    const joinedAuthors = this.data.joinedAuthors.filter(joined => joined !== authorKey);
    if (deletedCount > 0 || joinedAuthors.length !== this.data.joinedAuthors.length) {
      const deletionVersion = this.data.deletionVersion ?? 0;
      if (deletionVersion >= Number.MAX_SAFE_INTEGER) throw new CommunityChatStorageError();
      const next: ChatFile = { ...this.data, messages, joinedAuthors, deletionVersion: deletionVersion + 1 };
      if (this.filename !== null) {
        try { this.atomicWrite(next); }
        catch { this.healthy = false; throw new CommunityChatStorageError(); }
      }
      this.data = next;
    }
    return { schemaVersion: 1, roomId: SPXTR_CHAT_ROOM, deletedCount };
  }

  private requireHealthy(): void { if (!this.healthy) throw new CommunityChatStorageError(); }

  private atomicWrite(value: ChatFile): void {
    if (this.filename === null) throw new Error('No chat history path');
    const serialized = `${JSON.stringify(value)}\n`;
    if (Buffer.byteLength(serialized) > maximumHistoryBytes) throw new Error('Chat history too large');
    const directory = dirname(this.filename);
    mkdirSync(directory, { recursive: true, mode: 0o700 }); chmodSync(directory, 0o700);
    const temporary = `${this.filename}.tmp-${process.pid}-${this.now()}`;
    let descriptor: number | null = null;
    try {
      descriptor = openSync(temporary, 'wx', 0o600);
      writeFileSync(descriptor, serialized, 'utf8');
      fsyncSync(descriptor); closeSync(descriptor); descriptor = null;
      renameSync(temporary, this.filename); chmodSync(this.filename, 0o600);
      const directoryFd = openSync(directory, 'r');
      try { fsyncSync(directoryFd); } finally { closeSync(directoryFd); }
    } catch (error) {
      if (descriptor !== null) closeSync(descriptor);
      rmSync(temporary, { force: true }); throw error;
    }
  }
}
