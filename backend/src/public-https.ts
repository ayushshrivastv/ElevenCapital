import { isIP } from 'node:net';

const UNSAFE_TEXT = /[\s\u0000-\u001f\u007f]/u;
const DNS_LABEL = /^(?:xn--)?[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

/**
 * A deterministic URL check for values that will be published, not fetched.
 * DNS resolution is deliberately outside this contract; literal IP addresses,
 * local names, credentials, and explicit ports are never accepted.
 */
export function isPublicHttpsUrl(value: string): boolean {
  if (UNSAFE_TEXT.test(value)) return false;
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port) return false;
    const authority = value.slice(value.indexOf('://') + 3).split(/[/?#]/, 1)[0] ?? '';
    if (!authority || authority.includes('@') || authority.includes(':')) return false;
    const hostname = url.hostname.toLowerCase().replace(/\.$/, '');
    if (isIP(hostname) !== 0 || hostname === 'localhost' || hostname.endsWith('.localhost') ||
      hostname.endsWith('.local')) return false;
    const labels = hostname.split('.');
    return labels.length >= 2 && hostname.length <= 253 && labels.every(label => DNS_LABEL.test(label));
  } catch { return false; }
}
