import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const webDirectory = path.resolve(scriptDirectory, "..");
const projectDirectory = path.resolve(webDirectory, "..");
const distDirectory = path.join(webDirectory, "dist");
const distHtmlPath = path.join(distDirectory, "index.html");
const standaloneHtmlPath = path.join(projectDirectory, "index.html");

let html = await readFile(distHtmlPath, "utf8");
const scriptTag = html.match(/<script\b[^>]*type="module"[^>]*src="([^"]+)"[^>]*><\/script>/i);
const styleTag = html.match(/<link\b[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>/i);

if (!scriptTag || !styleTag) {
  throw new Error("Expected one Vite module script and one stylesheet in dist/index.html");
}

const resolveDistReference = (reference) => {
  const relativeReference = reference.replace(/^\.\//, "").replace(/^\//, "");
  return path.join(distDirectory, relativeReference);
};

let javascript = await readFile(resolveDistReference(scriptTag[1]), "utf8");
let css = await readFile(resolveDistReference(styleTag[1]), "utf8");

const publicAssets = [
  ["/roboto.ttf", "roboto.ttf", "font/ttf"],
  ["/solscan-logo.webp", "solscan-logo.webp", "image/webp"],
  ["/solscan-wordmark.svg", "solscan-wordmark.svg", "image/svg+xml"],
  ["/solscan-orbit.png", "solscan-orbit.png", "image/png"],
  ["/sol-price.png", "sol-price.png", "image/png"],
  ["/eleven-logo-source.png", "eleven-logo-source.png", "image/png"],
  ["/chtms-token.png", "chtms-token.png", "image/png"],
  ["/meteora-symbol.svg", "meteora-symbol.svg", "image/svg+xml"],
  ["/meteora-solscan.svg", "meteora-solscan.svg", "image/svg+xml"],
  ["/wsol.png", "wsol.png", "image/png"],
  ["/token-creator.svg", "token-creator.svg", "image/svg+xml"],
  ["/solana-icon.svg", "solana-icon.svg", "image/svg+xml"],
  ["/solana-sol-logo.svg", "solana-sol-logo.svg", "image/svg+xml"],
  ["/tag-create.svg", "tag-create.svg", "image/svg+xml"],
  ["/tag-swap.svg", "tag-swap.svg", "image/svg+xml"],
  ["/tag-transfer.svg", "tag-transfer.svg", "image/svg+xml"],
];

for (const [publicPath, filename, mimeType] of publicAssets) {
  const bytes = await readFile(path.join(webDirectory, "public", filename));
  const dataUrl = `data:${mimeType};base64,${bytes.toString("base64")}`;
  javascript = javascript.split(publicPath).join(dataUrl);
  css = css.split(publicPath).join(dataUrl);
}

javascript = javascript.replace(/<\/script/gi, "<\\/script");
css = css.replace(/<\/style/gi, "<\\/style");

html = html.replace(styleTag[0], () => `<style data-standalone-bundle>${css}</style>`);
html = html.replace(scriptTag[0], () => `<script type="module" data-standalone-bundle>${javascript}</script>`);
html = html.replace("<head>", "<head>\n    <!-- Self-contained build: open this file directly; no server required. -->");

const unresolvedReferences = [scriptTag[1], styleTag[1], ...publicAssets.map(([publicPath]) => publicPath)];
for (const unresolvedReference of unresolvedReferences) {
  if (html.includes(`src="${unresolvedReference}"`) || html.includes(`href="${unresolvedReference}"`) || html.includes(`url(${unresolvedReference})`)) {
    throw new Error(`Standalone build still contains a local dependency: ${unresolvedReference}`);
  }
}

await writeFile(distHtmlPath, html);
await writeFile(standaloneHtmlPath, html);

console.log(`Standalone HTML written to ${standaloneHtmlPath}`);
