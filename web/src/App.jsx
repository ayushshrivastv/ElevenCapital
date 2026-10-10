import React, { useEffect, useMemo, useState } from "react";

const TX_SIGNATURE =
  "GUmY6f9aEWCen3h85yGC8o7oWo8TgdKysEhnfHCSXDpLougLTeWoMkEVcZwYHVrzDjwMFEvhY1rLtKZHXcurwvny";
const PREVIEW_ROUTES = new Set(["/", "/explorer", "/index.html"]);

function currentPageRoute() {
  const path = window.location.pathname;
  return `${PREVIEW_ROUTES.has(path) ? path : "/explorer"}${window.location.search}`;
}

const addresses = {
  creator: "9EX9dkYMFGrrYYUvYhiEGqMREHMUbg2x7qXyV9dmtskL",
  destination: "YXcLoprHUuvAtECBaiJCLCyDPuzVjCGt6ceyVMsaEa82",
  market: "Bn3Uq1zqJViyy1xZyrs47TaKvJSVMcWhz7gKQoRvYmuJ",
  pool1: "9wUPdyedRsDJ2XgQQ7CVLuyqmrDKUqiV4qvgJUkkh4Hf",
  pool2: "RuKgCQvbTutwMWkAYXrUKr195b5EWv1KzoWbhtb1kfv6",
  token1: "vuwRpguZgEtfM5WFjaY3WJP8gAHf3J6GifN9UTasTJvW",
  token2: "3hfLHMxLRvgxsPmB6vKGxEd1MtH99F9ZuYRrZ5cCC7QY",
};

const transaction = {
  block: "404418670",
  signature: TX_SIGNATURE,
  recentBlockHash: "GkZRQHaG1rAEw6oA3EVYLTnKCGjt5Eaf5ZtwdFTDGRTN",
};

const padTime = (value) => String(value).padStart(2, "0");

function formatUtcTimestamp(date) {
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return `${padTime(date.getUTCHours())}:${padTime(date.getUTCMinutes())}:${padTime(date.getUTCSeconds())} ${months[date.getUTCMonth()]} ${padTime(date.getUTCDate())}, ${date.getUTCFullYear()} (UTC)`;
}

function formatRelativeTime(startedAt, now) {
  const elapsedSeconds = Math.max(0, Math.floor((now - startedAt.getTime()) / 1000));
  if (elapsedSeconds < 60) return `${elapsedSeconds} second${elapsedSeconds === 1 ? "" : "s"} ago`;
  const minutes = Math.floor(elapsedSeconds / 60);
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"} ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? "" : "s"} ago`;
}

const balanceRows = [
  ["Eleven Capital", "WRITABLE · SIGNER · FEE PAYER", "+0.0000055", "72.313866759", "72.313872259", "+$0.0007892", "eleven"],
  ["CHTMS", "WRITABLE · SIGNER", "+0.0014616", "0", "0.0014616", "+$0.2097", "chtms"],
  [addresses.creator, "WRITABLE · SIGNER", "−0.47655996", "0.4937353", "0.01717534", "−$68.38", "creator"],
  ["Meteora DBC (CHTMS-WSOL) Market", "WRITABLE", "+0.00384192", "0", "0.00384192", "+$0.5513", "meteora"],
  ["Meteora DBC (CHTMS-WSOL) Pool 1", "WRITABLE", "+0.00203928", "0", "0.00203928", "+$0.2926", "meteora"],
  ["Meteora DBC (CHTMS-WSOL) Pool 2", "WRITABLE", "+0.45203928", "0", "0.45203928", "+$64.86", "meteora"],
  [addresses.destination, "WRITABLE", "+0.0151156", "0", "0.0151156", "+$2.16", "account"],
  [addresses.token2, "WRITABLE", "+0.00203928", "0", "0.00203928", "+$0.2926", "account"],
];

const tokenRows = [
  ["Meteora DBC (CHTMS-WSOL) Pool 1", "Meteora DBC: Pool Authority", "+984,975,757.4437763", "0", "984,975,757.443776258", "+$4,739.70", "CHTMS"],
  ["Meteora DBC (CHTMS-WSOL) Pool 2", "Meteora DBC: Pool Authority", "+0.45", "0", "0.45", "+$64.57", "WSOL"],
  [addresses.token2, addresses.creator, "+15,024,242.556223742", "0", "15,024,242.556223742", "+$72.29", "CHTMS"],
];

const instructions = [
  ["Compute Budget", "SetComputeUnitLimit", "150"],
  ["Compute Budget", "SetComputeUnitPrice", "150"],
  ["Meteora Dynamic Bonding Curve", "initialize_virtual_pool_with_spl_token", "126,379"],
  ["Associated Token Program", "createIdempotent", "22,315"],
  ["Associated Token Program", "createIdempotent", "21,839"],
  ["System Program", "transfer", "150"],
  ["Token Program", "syncNative", "3,045"],
  ["Meteora Dynamic Bonding Curve", "swap", "48,663"],
  ["Token Program", "closeAccount", "2,915"],
  ["System Program", "transfer", "150"],
];

function Icon({ name, size = 16, strokeWidth = 1.8 }) {
  const common = {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth,
    strokeLinecap: "round",
    strokeLinejoin: "round",
    "aria-hidden": true,
  };

  const paths = {
    search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-3.4-3.4" /></>,
    chevron: <path d="m8 10 4 4 4-4" />,
    copy: <><rect x="8" y="8" width="10" height="10" rx="2" /><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" /></>,
    check: <path d="m6 12 4 4 8-9" />,
    eye: <><path d="M2.5 12s3.5-5 9.5-5 9.5 5 9.5 5-3.5 5-9.5 5-9.5-5-9.5-5Z" /><circle cx="12" cy="12" r="2.4" /></>,
    settings: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06-2.83 2.83-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.04 1.56V21h-4v-.08A1.7 1.7 0 0 0 8.96 19.4a1.7 1.7 0 0 0-1.87.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15 1.7 1.7 0 0 0 3.08 14H3v-4h.08A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.34-1.87l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 8.96 4.6 1.7 1.7 0 0 0 10 3.08V3h4v.08A1.7 1.7 0 0 0 15.04 4.6a1.7 1.7 0 0 0 1.87-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9 1.7 1.7 0 0 0 20.92 10H21v4h-.08A1.7 1.7 0 0 0 19.4 15Z" /></>,
    sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.65 17.65l1.42 1.42M2 12h2M20 12h2M4.93 19.07l1.42-1.42M17.65 6.35l1.42-1.42" /></>,
    menu: <><path d="M4 7h16M4 12h16M4 17h16" /></>,
    close: <><path d="m6 6 12 12M18 6 6 18" /></>,
    arrow: <><path d="M7 17 17 7M8 7h9v9" /></>,
    swap: <><path d="m7 7-3 3 3 3M4 10h13M17 17l3-3-3-3M20 14H7" /></>,
    info: <><circle cx="12" cy="12" r="9" /><path d="M9.7 9a2.5 2.5 0 1 1 3.7 2.2c-.9.5-1.4 1-1.4 2M12 17h.01" /></>,
    spark: <path d="m12 2 1.6 5.4L19 9l-5.4 1.6L12 16l-1.6-5.4L5 9l5.4-1.6L12 2Z" />,
    lightbulb: <><path d="M9 18h6M10 22h4" /><path d="M8.5 14.5A6 6 0 1 1 15.5 14.5c-.9.8-1.5 1.8-1.5 3h-4c0-1.2-.6-2.2-1.5-3Z" /></>,
    download: <><path d="M12 3v12M7 10l5 5 5-5" /><path d="M5 17v3h14v-3" /></>,
    map: <><path d="m4 7 5-3 6 3 5-3v13l-5 3-6-3-5 3Z" /><path d="M9 4v13M15 7v13" /></>,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    history: <><path d="M3 12a9 9 0 1 0 3-6.7L3 8" /><path d="M3 3v5h5M12 7v5l3 2" /></>,
    expand: <><path d="M8 3H3v5M16 3h5v5M8 21H3v-5M16 21h5v-5" /></>,
    collapse: <><path d="M9 9H4V4M15 9h5V4M9 15H4v5M15 15h5v5" /></>,
    external: <><path d="M14 5h5v5M19 5l-8 8" /><path d="M17 13v5a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1h5" /></>,
    code: <><path d="m9 18-6-6 6-6M15 6l6 6-6 6" /></>,
  };

  return <svg {...common}>{paths[name] ?? <circle cx="12" cy="12" r="8" />}</svg>;
}

function Brand({ compact = false }) {
  const destination = window.location.protocol === "file:" ? "#overview" : currentPageRoute();
  return (
    <a className={`brand solscan-brand ${compact ? "brand--compact" : ""}`} href={destination} aria-label="Solscan">
      <img src="/solscan-wordmark.svg" alt="Solscan" />
    </a>
  );
}

function TokenAvatar({ type = "chtms", size = 20 }) {
  if (type === "eleven") {
    return <img className="token-avatar image-avatar" style={{ width: size, height: size }} src="/eleven-logo-source.png" alt="" />;
  }
  if (type === "chtms") {
    return <img className="token-avatar chtms-avatar" style={{ width: size, height: size }} src="/chtms-token.png" alt="" />;
  }
  if (type === "wsol") {
    return <img className="token-avatar wsol-avatar" style={{ width: size, height: size }} src="/wsol.png" alt="" />;
  }
  if (type === "meteora") {
    return <img className="token-avatar meteora-avatar" style={{ width: size, height: size }} src="/meteora-solscan.svg" alt="" />;
  }
  if (type === "creator") {
    return <img className="token-avatar creator-avatar" style={{ width: size, height: size }} src="/token-creator.svg" alt="" />;
  }
  if (type === "sol") {
    return <img className="token-avatar sol-avatar" style={{ width: size, height: size }} src="/solana-sol-logo.svg" alt="" />;
  }
  if (type === "account") {
    return <span className="token-avatar account-avatar" style={{ width: size, height: size }} aria-hidden="true">✦</span>;
  }
  return <span className="token-avatar account-avatar" style={{ width: size, height: size }} aria-hidden="true">✦</span>;
}

function CopyButton({ value, onCopy, label = "Copy" }) {
  const copy = async () => {
    let copied = false;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
        copied = true;
      }
    } catch {
      copied = false;
    }

    if (!copied) {
      const textarea = document.createElement("textarea");
      textarea.value = value;
      textarea.setAttribute("readonly", "");
      textarea.style.position = "fixed";
      textarea.style.opacity = "0";
      document.body.appendChild(textarea);
      textarea.select();
      copied = document.execCommand("copy");
      textarea.remove();
    }

    onCopy?.(copied ? label : "Copy unavailable");
  };
  return <button type="button" className="icon-copy" onClick={copy} aria-label={label}><Icon name="copy" size={13} /></button>;
}

function shorten(value, front = 7, back = 6) {
  return value.length > front + back + 3 ? `${value.slice(0, front)}...${value.slice(-back)}` : value;
}

function looksLikeAddress(value) {
  return /^[1-9A-HJ-NP-Za-km-z]{32,}$/.test(value);
}

function NameLink({ children, avatar, onCopy, value, shortenValue = false }) {
  const fullValue = value || String(children);
  return (
    <span className="name-link" title={fullValue}>
      {avatar && <TokenAvatar type={avatar} />}
      <button type="button" className="text-link">{shortenValue ? shorten(String(children)) : children}</button>
      <CopyButton value={fullValue} onCopy={onCopy} label={`${children} copied`} />
    </span>
  );
}

function TopNav({ dark, setDark, menuOpen, setMenuOpen }) {
  const navItems = ["Analytics", "Defi", "Leaderboard", "Blockchain", "Resources", "API"];
  return (
    <header className="site-header">
      <div className="desktop-header">
        <Brand />
        <div className="market-pill"><img src="/sol-price.png" alt="" /><b>$109.07</b><em>-6.19%</em></div>
        <div className="fee-pill">Avg Fee:&nbsp; <span>0.00001256</span></div>
        <nav className="desktop-nav" aria-label="Primary navigation">
          {navItems.map((item) => (
            <details className="nav-dropdown" key={item}>
              <summary>{item}<Icon name="chevron" size={13} /></summary>
              <div className="dropdown-card">
                <button type="button">Overview</button>
                <button type="button">Explore {item}</button>
                <button type="button">Documentation</button>
              </div>
            </details>
          ))}
          <button type="button" className="sign-in">Sign in</button>
        </nav>
        <button type="button" className="header-icon network-icon" aria-label="Network"><img src="/solscan-orbit.png" alt="" /></button>
        <button type="button" className="header-icon" onClick={() => setDark(!dark)} aria-label="Toggle color theme"><Icon name="sun" /></button>
        <button type="button" className="header-icon" aria-label="Site settings"><Icon name="settings" /></button>
        <button type="button" className="bare-icon compact-only" onClick={() => setMenuOpen(!menuOpen)} aria-label="Toggle menu"><Icon name={menuOpen ? "close" : "menu"} size={24} /></button>
        <button type="button" className="bare-icon compact-only" aria-label="Search"><Icon name="search" size={22} /></button>
      </div>

      <div className="mobile-header">
        <Brand compact />
        <div className="mobile-header-actions">
          <button type="button" className="header-icon network-icon" aria-label="Network"><img src="/solscan-orbit.png" alt="" /></button>
          <button type="button" className="header-icon" onClick={() => setDark(!dark)} aria-label="Toggle color theme"><Icon name="sun" /></button>
          <button type="button" className="header-icon" aria-label="Site settings"><Icon name="settings" /></button>
          <button type="button" className="bare-icon" onClick={() => setMenuOpen(!menuOpen)} aria-label="Toggle menu"><Icon name={menuOpen ? "close" : "menu"} size={24} /></button>
          <button type="button" className="bare-icon" aria-label="Search"><Icon name="search" size={22} /></button>
        </div>
      </div>
      <div className="mobile-market-strip">
        <img src="/sol-price.png" alt="" /><b>$109.07</b><em>-6.19%</em><span className="divider" />Avg Fee:&nbsp;<a href="#fees">0.00001256</a>
      </div>
      {menuOpen && (
        <nav className="mobile-menu" aria-label="Mobile navigation">
          {navItems.map((item) => <button type="button" key={item}>{item}<Icon name="chevron" /></button>)}
          <button type="button">Sign in</button>
        </nav>
      )}
    </header>
  );
}

function SearchBox({ onNotice }) {
  const [query, setQuery] = useState("");
  const submit = (event) => {
    event.preventDefault();
    onNotice(query.trim() ? `Search submitted: ${query.trim()}` : "Type an address or transaction to search");
  };
  return (
    <form className="search-box" onSubmit={submit}>
      <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search transactions, blocks, programs and tokens" aria-label="Search" />
      <button type="submit" aria-label="Submit search"><Icon name="search" size={21} /></button>
    </form>
  );
}

function PageIntro({ onNotice }) {
  return (
    <section className="page-intro">
      <div className="title-block">
        <h1>Transaction Details</h1>
        <div className="context-chips">
          <span><img src="/tag-create.svg" alt="" />Token: Create</span>
          <span>Pool: Create</span>
          <span><img src="/tag-swap.svg" alt="" />Swap</span>
          <span><img src="/tag-transfer.svg" alt="" />Transfer</span>
        </div>
      </div>
      <SearchBox onNotice={onNotice} />
    </section>
  );
}

function PromoRow() {
  const products = [["Buy", "◈"], ["Prediction", "◌"], ["Play", "▣"], ["Gaming", "♣"]];
  return (
    <section className="promo-section">
      <div className="product-buttons">
        {products.map(([product, icon]) => <button type="button" key={product}><span className="product-icon">{icon}</span>{product}<Icon name="chevron" size={14} /></button>)}
      </div>
    </section>
  );
}

function PrimaryTabs({ activeTab, setActiveTab }) {
  const tabs = [
    ["overview", "Overview"],
    ["balance_change", "Balance Changes"],
    ["raw", "Raw"],
  ];
  return (
    <div className="tab-toolbar">
      <div className="tabs" role="tablist" aria-label="Transaction views">
        {tabs.map(([id, label]) => (
          <button type="button" role="tab" aria-selected={activeTab === id} className={activeTab === id ? "active" : ""} key={id} onClick={() => setActiveTab(id)}>{label}</button>
        ))}
      </div>
      <div className="tab-actions">
        <a href="https://pro-api.solscan.io/pro-api-docs/v2.0/reference/v2-transaction-detail" target="_blank" rel="noreferrer"><Icon name="code" size={14} />API</a>
        <button type="button" aria-label="More options"><Icon name="menu" size={16} /><Icon name="chevron" size={13} /></button>
      </div>
    </div>
  );
}

function SummarySentence({ onCopy }) {
  return (
    <div className="summary-sentence">
      <span>Create pool</span>
      <NameLink avatar="chtms" onCopy={onCopy}>CHTMS</NameLink>
      <span>−</span>
      <NameLink avatar="wsol" onCopy={onCopy}>WSOL</NameLink>
      <span>on</span>
      <NameLink avatar="meteora" onCopy={onCopy}>Meteora Dynamic Bonding Curve</NameLink>
      <span>( via</span>
      <NameLink avatar="eleven" onCopy={onCopy}>Eleven Capital</NameLink>
      <span>)</span>
    </div>
  );
}

function SummaryCard({ onCopy, onNotice }) {
  return (
    <section className="card summary-card">
      <div className="summary-icon"><Icon name="download" size={21} /></div>
      <div className="summary-copy">
        <span className="eyebrow">Summary</span>
        <SummarySentence onCopy={onCopy} />
      </div>
      <button type="button" className="ai-button" onClick={() => onNotice("AI explanation: This transaction initializes a CHTMS–WSOL liquidity pool.")}><Icon name="lightbulb" size={14} />AI Explanation</button>
    </section>
  );
}

function DetailRow({ label, children, className = "" }) {
  return <div className={`detail-row ${className}`}><div className="detail-label"><Icon name="info" size={14} />{label}</div><div className="detail-value">{children}</div></div>;
}

function StatusBadge() {
  return <span className="status-badge"><Icon name="check" size={13} />SUCCESS</span>;
}

function Toggle({ label, value, onChange }) {
  return (
    <label className="toggle-label">{label}<input type="checkbox" checked={value} onChange={(event) => onChange(event.target.checked)} /><span className="toggle"><i /></span></label>
  );
}

function ActionRow({ icon, children }) {
  const source = icon === "swap" ? "/tag-swap.svg" : icon === "spark" ? "/tag-create.svg" : "/tag-transfer.svg";
  return <div className="action-row"><span className={`action-icon action-icon--${icon}`}><img src={source} alt="" /></span><div>{children}</div><Icon name="chevron" size={15} /></div>;
}

function TransactionActions({ onCopy, onNotice }) {
  const [summaryMode, setSummaryMode] = useState(true);
  const [keyActions, setKeyActions] = useState(false);
  const [tokenAccounts, setTokenAccounts] = useState(false);
  return (
    <section className="transaction-actions">
      <div className="section-heading-row">
        <h2><Icon name="info" size={14} />Transaction Actions <span>13 Transfer(s)</span></h2>
        <button type="button" className="tx-map tx-map-mobile" onClick={() => onNotice("Transaction map opened")}><Icon name="map" size={14} />Tx Maps</button>
      </div>
      <div className="actions-toolbar">
        <div className="mode-tabs">
          <button type="button" className={!summaryMode ? "active" : ""} onClick={() => setSummaryMode(false)}>Legacy Mode</button>
          <button type="button" className={summaryMode ? "active" : ""} onClick={() => setSummaryMode(true)}>Summary Mode</button>
        </div>
        <div className="action-toggles">
          <Toggle label="Key Actions" value={keyActions} onChange={setKeyActions} />
          <Toggle label="Token Account" value={tokenAccounts} onChange={setTokenAccounts} />
          <button type="button" className="tx-map" onClick={() => onNotice("Transaction map opened")}><Icon name="map" size={14} />Tx Maps</button>
        </div>
      </div>
      <div className="action-list">
        {summaryMode ? (
          <>
            <div className="action-group">
              <ActionRow icon="spark"><SummarySentence onCopy={onCopy} /></ActionRow>
              <ActionRow icon="arrow"><span>Transfer from </span><NameLink avatar="eleven" onCopy={onCopy}>Eleven Capital</NameLink><span> to </span><NameLink value={addresses.destination} shortenValue onCopy={onCopy}>{addresses.destination}</NameLink><span> for <b>0.0151156</b> <small>$1.64</small> </span><NameLink avatar="sol" onCopy={onCopy}>SOL</NameLink></ActionRow>
              <ActionRow icon="spark"><span>Mint to </span><NameLink avatar="meteora" onCopy={onCopy}>Meteora DBC: Pool Authority</NameLink><span> for <b>1,000,000,000</b> <small>$32,348.67</small> </span><NameLink avatar="chtms" onCopy={onCopy}>CHTMS</NameLink></ActionRow>
            </div>
            <div className="action-group">
              <ActionRow icon="swap"><span>Swap <b>0.45</b> <small>$49</small> </span><NameLink avatar="wsol" onCopy={onCopy}>WSOL</NameLink><span> for <b>15,024,242.556223742</b> <small>$486.01</small> </span><NameLink avatar="chtms" onCopy={onCopy}>CHTMS</NameLink><span> on </span><NameLink avatar="meteora" onCopy={onCopy}>Meteora Dynamic Bonding Curve</NameLink></ActionRow>
              <ActionRow icon="arrow"><span>Transfer from </span><NameLink avatar="creator" value={addresses.creator} shortenValue onCopy={onCopy}>{addresses.creator}</NameLink><span> to </span><NameLink avatar="meteora" onCopy={onCopy}>Meteora DBC: Pool Authority</NameLink><span> for <b>0.45</b> <small>$49</small> </span><NameLink avatar="wsol" onCopy={onCopy}>WSOL</NameLink></ActionRow>
              <ActionRow icon="arrow"><span>Transfer from </span><NameLink avatar="meteora" onCopy={onCopy}>Meteora DBC: Pool Authority</NameLink><span> to </span><NameLink avatar="creator" value={addresses.creator} shortenValue onCopy={onCopy}>{addresses.creator}</NameLink><span> for <b>15,024,242.556223742</b> <small>$486.01</small> </span><NameLink avatar="chtms" onCopy={onCopy}>CHTMS</NameLink></ActionRow>
            </div>
          </>
        ) : (
          <div className="legacy-list">
            {instructions.map(([program, action], index) => <div key={`${program}-${index}`}><b>#{index + 1}</b><span>{program}: {action}</span><CopyButton value={`${program}: ${action}`} onCopy={onCopy} label="Instruction copied" /></div>)}
          </div>
        )}
      </div>
    </section>
  );
}

function Metrics({ onCopy }) {
  const items = [
    ["Fee", "0.0000175 SOL ($0.001908)"],
    ["Priority Fee", "0.0000025 SOL ($0.0002726)"],
    ["Transaction cost", "231,481"],
    ["Compute Units Consumed / Limit", "225,756 / 250,000 (90.3%)"],
    ["Transaction Version", "0"],
  ];
  return (
    <section className="metrics" id="fees">
      {items.map(([label, value]) => <DetailRow key={label} label={label}><span>{value}</span></DetailRow>)}
      <DetailRow label="Recent Block Hash"><span className="hash-line"><span>{transaction.recentBlockHash}</span><CopyButton value={transaction.recentBlockHash} onCopy={onCopy} label="Block hash copied" /></span></DetailRow>
      <DetailRow label="Personal Label"><button type="button" className="text-link">Sign in to add personal label</button></DetailRow>
    </section>
  );
}

function Instructions({ onCopy }) {
  return (
    <>
    <section className="instructions card">
      <div className="instruction-head"><h2>Instruction Details</h2><div className="mini-tabs"><button type="button" className="active">List</button><button type="button">Tree</button></div></div>
      <div className="compute-box">
        <div><b>Compute Units Distribution</b><span>Total: <strong>225,756</strong></span></div>
        <div className="compute-bars">{instructions.map(([, , units], index) => <span key={index} style={{ "--h": `${18 + (Number(units.replaceAll(",", "")) / 126379) * 48}px` }} title={`Instruction ${index + 1}: ${units}`} />)}</div>
      </div>
      <div className="accordion-list">
        {instructions.map(([program, action, units], index) => (
          <details key={`${program}-${action}-${index}`}>
            <summary><span><b>#{index + 1}</b> - {program}: <strong>{action}</strong></span><span className="raw-label">Raw</span><Icon name="chevron" size={15} /></summary>
            <div className="instruction-body"><code>{`program: "${program}"\naction: "${action}"\ncomputeUnits: ${units.replaceAll(",", "")}`}</code><CopyButton value={`${program}: ${action}`} onCopy={onCopy} label="Instruction copied" /></div>
          </details>
        ))}
      </div>
    </section>
    <details className="program-logs card"><summary><span>Program Logs</span><span>Show details</span><Icon name="chevron" /></summary><pre>Program log: Instruction: initialize_virtual_pool_with_spl_token{"\n"}Program log: CHTMS–WSOL pool initialized{"\n"}Program consumed 225756 compute units{"\n"}Program returned success</pre></details>
    </>
  );
}

function Overview({ onCopy, onNotice }) {
  const [blockTime] = useState(() => new Date());
  const [clock, setClock] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setClock(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const timestamp = formatUtcTimestamp(blockTime);
  const timeAgo = formatRelativeTime(blockTime, clock);

  return (
    <>
      <SummaryCard onCopy={onCopy} onNotice={onNotice} />
      <section className="card overview-card">
        <div className="overview-top">
          <DetailRow label="Signature" className="signature-row">
            <div className="signature-value"><span title={transaction.signature}>{transaction.signature}</span><CopyButton value={transaction.signature} onCopy={onCopy} label="Transaction signature copied" /></div>
            <button type="button" className="inspect-button" onClick={() => onNotice("Transaction inspector opened")}><Icon name="eye" size={15} />Inspect Tx</button>
          </DetailRow>
          <DetailRow label="Block & Timestamp" className="desktop-block-row"><div className="block-time"><button type="button" className="text-link">{transaction.block}</button><CopyButton value={transaction.block} onCopy={onCopy} label="Block copied" /><Icon name="history" size={14} /><span className="time-ago">{timeAgo}</span><i className="timestamp-divider" /><Icon name="clock" size={17} /><span className="exact-timestamp">{timestamp}</span><Icon name="chevron" size={13} /></div></DetailRow>
          <DetailRow label="Block" className="mobile-block-row"><div className="mobile-row-value"><button type="button" className="text-link">{transaction.block}</button><CopyButton value={transaction.block} onCopy={onCopy} label="Block copied" /><Icon name="history" size={14} /></div></DetailRow>
          <DetailRow label="Timestamp" className="mobile-block-row"><div className="mobile-row-value"><span className="exact-timestamp">{timestamp}</span><Icon name="chevron" size={13} /></div></DetailRow>
          <DetailRow label="Result"><div className="result-line"><StatusBadge /><span>Finalized (MAX Confirmations)</span></div></DetailRow>
          <DetailRow label="Signer"><div className="signer-list"><NameLink avatar="eleven" onCopy={onCopy}>Eleven Capital</NameLink><NameLink avatar="chtms" onCopy={onCopy}>CHTMS</NameLink><NameLink avatar="creator" value={addresses.creator} shortenValue onCopy={onCopy}>{addresses.creator}</NameLink></div></DetailRow>
        </div>
        <TransactionActions onCopy={onCopy} onNotice={onNotice} />
        <Metrics onCopy={onCopy} />
      </section>
      <Instructions onCopy={onCopy} />
    </>
  );
}

function BalanceChanges({ onCopy }) {
  const [hideUnchanged, setHideUnchanged] = useState(true);
  return (
    <>
    <section className="card balance-card">
      <div className="balance-title-row"><div><h2>SOL Balance Change</h2><p>Show&nbsp; {balanceRows.length} / 19 &nbsp;accounts</p></div><Toggle label="Hide Unchanged" value={hideUnchanged} onChange={setHideUnchanged} /></div>
      <div className="table-scroll"><table><thead><tr><th>Address</th><th /><th>Change (SOL)</th><th>Balance Before</th><th>Balance After</th><th>Change Value</th></tr></thead><tbody>{balanceRows.map(([name, tags, change, before, after, value, avatar]) => <tr key={name}><td><NameLink avatar={avatar} value={name} shortenValue={looksLikeAddress(name)} onCopy={onCopy}>{name}</NameLink></td><td><div className="account-tags">{tags.split(" · ").map((tag) => <span key={tag}>{tag}</span>)}</div></td><td className={change.startsWith("+") ? "positive" : "negative"}>{change} ◎</td><td>{before}</td><td>{after}</td><td className={value.startsWith("+") ? "positive" : "negative"}>{value}</td></tr>)}</tbody></table></div>
    </section>

    <section className="card balance-card balance-card-token">
      <div className="balance-title-row"><div><h2>Token Balance Change</h2><p>Show&nbsp; {tokenRows.length} / {tokenRows.length} &nbsp;accounts</p></div><Toggle label="Hide Unchanged" value={hideUnchanged} onChange={setHideUnchanged} /></div>
      <div className="table-scroll"><table className="token-table"><thead><tr><th>Address</th><th>Owner</th><th>Change</th><th>Balance Before</th><th>Balance After</th><th>Change Value</th><th>Token</th></tr></thead><tbody>{tokenRows.map(([address, owner, change, before, after, value, token]) => <tr key={address}><td><NameLink avatar={address.includes("Meteora") ? "meteora" : "account"} value={address} shortenValue={looksLikeAddress(address)} onCopy={onCopy}>{address}</NameLink></td><td><NameLink avatar={owner.includes("Meteora") ? "meteora" : "creator"} value={owner} shortenValue={looksLikeAddress(owner)} onCopy={onCopy}>{owner}</NameLink></td><td className="positive">{change}</td><td>{before}</td><td>{after}</td><td className="positive">{value}</td><td><NameLink avatar={token === "WSOL" ? "wsol" : "chtms"} onCopy={onCopy}>{token}</NameLink></td></tr>)}</tbody></table></div>
    </section>
    </>
  );
}

function TreeLine({ children, depth = 0, accent = false, muted = false }) {
  return <div className={`tree-line ${accent ? "tree-line--accent" : ""} ${muted ? "muted" : ""}`} style={{ "--depth": depth }}>{children}</div>;
}

function RawTransaction({ onCopy }) {
  const [expanded, setExpanded] = useState(true);
  return (
    <section className="card raw-card">
      <div className="raw-toolbar"><button type="button" onClick={() => setExpanded(false)} aria-label="Collapse all"><Icon name="collapse" size={15} /></button><button type="button" className={expanded ? "active" : ""} onClick={() => setExpanded(true)} aria-label="Expand all"><Icon name="expand" size={15} /></button></div>
      <div className="json-tree">
        <TreeLine>{"{"} <em>6 items</em></TreeLine>
        <TreeLine depth={1}>⌄ <b>transaction</b>: {"{"} <em>2 items</em></TreeLine>
        <TreeLine depth={2}>› <b>signatures</b>: [ <span className="ellipsis">•••</span> ] <em>3 items</em></TreeLine>
        <TreeLine depth={2}>› <b>message</b>: {"{"} <span className="ellipsis">•••</span> {"}"} <em>4 items</em></TreeLine>
        <TreeLine depth={1}>{"}"}</TreeLine>
        <TreeLine depth={1}>⌄ <b>meta</b>: {"{"} <em>12 items</em></TreeLine>
        <TreeLine depth={2}><b>err</b>: <span className="null">NULL</span></TreeLine>
        <TreeLine depth={2}>› <b>status</b>: {"{"} <span className="ellipsis">•••</span> {"}"} <em>1 item</em></TreeLine>
        <TreeLine depth={2}><b>fee</b>: <span className="number">17500</span></TreeLine>
        {expanded && <>
          <TreeLine depth={2}>› <b>preBalances</b>: [ <span className="ellipsis">•••</span> ] <em>19 items</em></TreeLine>
          <TreeLine depth={2}>› <b>postBalances</b>: [ <span className="ellipsis">•••</span> ] <em>19 items</em></TreeLine>
          <TreeLine depth={2}>› <b>innerInstructions</b>: [ <span className="ellipsis">•••</span> ] <em>4 items</em></TreeLine>
          <TreeLine depth={2}><b>logMessages</b>: <em>128 items</em></TreeLine>
          <TreeLine depth={2}>› <b>preTokenBalances</b>: [ ] <em>0 items</em></TreeLine>
          <TreeLine depth={2}>› <b>postTokenBalances</b>: [ <span className="ellipsis">•••</span> ] <em>3 items</em></TreeLine>
          <TreeLine depth={2}>› <b>rewards</b>: [ ] <em>0 items</em></TreeLine>
          <TreeLine depth={2}><b>computeUnitsConsumed</b>: <span className="number">225756</span></TreeLine>
          <TreeLine depth={2}><b>costUnits</b>: <span className="number">231481</span></TreeLine>
        </>}
        <TreeLine depth={1}>{"}"}</TreeLine>
        <TreeLine depth={1}><b>version</b>: <span className="number">0</span></TreeLine>
        <TreeLine depth={1}><b>slot</b>: <span className="number">404418670</span></TreeLine>
        <TreeLine depth={1}><b>transactionIndex</b>: <span className="number">854</span></TreeLine>
        <TreeLine depth={1}><b>blockTime</b>: <span className="number">1791017592</span></TreeLine>
        <TreeLine>{"}"}</TreeLine>
      </div>
    </section>
  );
}

function Footer() {
  return (
    <footer className="site-footer">
      <p className="learn-note">A transaction is a cryptographically signed instruction that changes the blockchain state. Block explorers track the details of all transactions in the network. Learn more about transactions in our <a href="#knowledge">Knowledge Base</a>.</p>
      <div className="back-to-top"><button type="button" onClick={() => window.scrollTo({ top: 0, behavior: "smooth" })}>Back to Top ↑</button></div>
      <div className="footer-grid">
        <div className="footer-brand"><Brand compact /><p>Powered by Solscan</p><span>Solscan is the leading Block Explorer and Search, API &amp; Analytics Platform for Solana blockchain.</span></div>
        <div><h3>Company</h3><a href="#about">About Us</a><a href="#brand">Branding</a><a href="#contact">Contact Us</a><a href="#terms">Terms of Service</a><a href="#dmca">DMCA Copyright</a><a href="#privacy">Privacy Policy</a><a href="#bounty">Bug Bounty</a></div>
        <div><h3>Community</h3><a href="#docs">API Docs</a><a href="#docs">Solscan Docs</a><a href="#knowledge">Knowledge Base</a></div>
        <div><h3>Products &amp; Services</h3><a href="#advertise">Advertise</a><a href="#eaas">Explorer as a Service (EaaS)</a><a href="#api">API Plans</a><a href="#support">Priority Support</a><a href="#token">Token Update</a></div>
      </div>
      <div className="copyright">Solscan © 2026 <span>Donations: D27DgiipBR5dRdij2L6NQ27xwyiLK5Q2DsEM5ML5EuLK</span></div>
    </footer>
  );
}

function CookieBanner({ onClose }) {
  return (
    <aside className="cookie-banner" aria-label="Cookie notice">
      <Icon name="info" size={17} />
      <p>This website uses cookies to improve your experience. By continuing to use this website, you agree to its <a href="#terms">Terms</a> and <a href="#privacy">Privacy Policy</a>.</p>
      <button type="button" onClick={onClose}>Got it!</button>
    </aside>
  );
}

export default function App() {
  const initialTab = useMemo(() => {
    const hash = window.location.hash.replace("#", "");
    return ["overview", "balance_change", "raw"].includes(hash) ? hash : "overview";
  }, []);
  const [activeTab, setActiveTabState] = useState(initialTab);
  const [dark, setDark] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    if (window.location.protocol !== "file:" &&
        !PREVIEW_ROUTES.has(window.location.pathname)) {
      window.history.replaceState({}, "", `/explorer${window.location.hash}`);
    }
    document.title = "Transaction Details | Eleven Capital";
  }, []);

  useEffect(() => {
    const syncTabFromHash = () => {
      const hash = window.location.hash.replace("#", "");
      if (["overview", "balance_change", "raw"].includes(hash)) {
        setActiveTabState(hash);
      }
    };
    window.addEventListener("hashchange", syncTabFromHash);
    return () => window.removeEventListener("hashchange", syncTabFromHash);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    return () => delete document.documentElement.dataset.theme;
  }, [dark]);

  useEffect(() => {
    if (!notice) return undefined;
    const timer = window.setTimeout(() => setNotice(""), 2600);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const setActiveTab = (tab) => {
    setActiveTabState(tab);
    if (window.location.protocol === "file:") {
      window.location.hash = tab;
      return;
    }
    const hash = tab === "overview" ? "" : `#${tab}`;
    window.history.replaceState({}, "", `${currentPageRoute()}${hash}`);
  };

  return (
    <div className="app-shell">
      <TopNav dark={dark} setDark={setDark} menuOpen={menuOpen} setMenuOpen={setMenuOpen} />
      <main>
        <PageIntro onNotice={setNotice} />
        <PromoRow />
        <PrimaryTabs activeTab={activeTab} setActiveTab={setActiveTab} />
        {activeTab === "overview" && <Overview onCopy={setNotice} onNotice={setNotice} />}
        {activeTab === "balance_change" && <BalanceChanges onCopy={setNotice} />}
        {activeTab === "raw" && <RawTransaction onCopy={setNotice} />}
      </main>
      <Footer />
      {notice && <div className="toast" role="status">{notice}</div>}
    </div>
  );
}
