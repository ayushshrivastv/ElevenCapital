import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const standalonePath = path.resolve(scriptDirectory, "..", "..", "index.html");
const html = await readFile(standalonePath, "utf8");

const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};

const scriptBundle = html.match(/<script\b[^>]*data-standalone-bundle[^>]*>([\s\S]*?)<\/script>/i);
const styleBundle = html.match(/<style\b[^>]*data-standalone-bundle[^>]*>([\s\S]*?)<\/style>/i);

assert(scriptBundle?.[1], "Missing inline JavaScript bundle");
assert(styleBundle?.[1], "Missing inline stylesheet bundle");
assert(!/<script\b[^>]*\bsrc=/i.test(html), "Found an external script dependency");
assert(!/<link\b[^>]*\brel="stylesheet"/i.test(html), "Found an external stylesheet dependency");
assert(!/Sponsored:|Rollbit/i.test(html), "Found removed sponsored content");
assert(!/Pool Creator|CHTMS Pool|Meteora Pool Authority/.test(html), "Found a superseded entity label");
assert(/CHTMS/.test(html), "Missing CHTMS content");
assert(/Eleven Capital/.test(html), "Missing Eleven Capital content");
assert(/Meteora Dynamic Bonding Curve/.test(html), "Missing Meteora content");
assert(/Meteora DBC: Pool Authority/.test(html), "Missing exact Meteora pool-authority label");
assert(/9EX9dkYMFGrrYYUvYhiEGqMREHMUbg2x7qXyV9dmtskL/.test(html), "Missing randomized creator address");

for (const mimeType of ["font/ttf", "image/png", "image/svg+xml"]) {
  assert(html.includes(`data:${mimeType};base64,`), `Missing embedded ${mimeType} asset`);
}

new Function(scriptBundle[1]);

console.log(`Verified standalone HTML: ${standalonePath}`);
console.log(`Size: ${(Buffer.byteLength(html) / 1024 / 1024).toFixed(2)} MiB`);
