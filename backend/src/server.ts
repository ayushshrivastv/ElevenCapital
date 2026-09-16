import { buildApp } from './app.js';
import { LiveMarketService } from './live-service.js';
import { configureBackendServerEnvironment } from './server-config.js';

await configureBackendServerEnvironment();
const port = Number(process.env.PORT ?? 8787);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('PORT must be an integer between 1024 and 65535');
// This development service intentionally cannot bind publicly through an environment variable.
const service = new LiveMarketService();
const app = await buildApp(service, true, { autoRefresh: true });
await app.listen({ port, host: '127.0.0.1' });
for (const signal of ['SIGINT', 'SIGTERM'] as const) process.once(signal, async () => {
  await app.close();
  process.exit(0);
});
