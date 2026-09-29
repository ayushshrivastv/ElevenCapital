# Eleven launchpad — independent research scaffold

This directory is an original Solana/Anchor virtual-reserve launchpad prototype. It does **not** contain Meteora code, implement Meteora DBC/DAMM v2, or create a Meteora-labelled pool. It is isolated from the Android app and backend. It has not been audited, deployed, or used with funds. **Deployment is blocked, including a Testnet demo deployment, until the missing exit/migration behavior and security checks below are resolved.**

## Implemented

- One fixed-supply, six-decimal SPL mint per launch, with all tokens initially held by a program-derived token vault. The mint authority is revoked after the mint CPI.
- WSOL quote custody in a program-derived associated token account. Users must wrap SOL before trading; the program does not accept native SOL directly.
- Checked, integer-only virtual constant-product quotes. For quote input `q`, protocol fee `f = ceil(q × fee_bps / 10,000)`, and `n = q − f`, buying returns `floor(V_base × n / (V_quote + n))` base atoms. Selling `b` base atoms pays gross `floor(V_quote × b / (V_base + b))` quote atoms, minus a fee rounded up. The virtual quote reserve increases by the **net** buy input and decreases by the **gross** sell output; the vault receives/pays those same reserve amounts, while fees flow separately to treasury.
- Exact-in buys/sells with caller-provided minimum output and Unix deadline. All arithmetic is checked; zero/dust and reserve-exceeding trades fail. The code uses only the classic SPL Token program, avoiding Token-2022 transfer hooks in this prototype.
- An immutable launch fee and quote threshold, a one-way `ThresholdReached` flag, and two-stage admin transfer. A designated bootstrap public key, governance admin, and separate guardian protect pause controls. Fees go to the configured treasury's WSOL token account.

## Economic invariants to preserve

1. `mint.supply == total_supply` after launch, and no mint authority remains.
2. `0 <= sold_base <= total_supply`; the base vault has enough inventory for every buy.
3. `quote_reserve` is the program-accounted quote available to redeem sold tokens; direct token-account donations cannot inflate the threshold or the amount redeemable by trading.
4. `V_base > 0`, `V_quote > 0`, and `V_base × V_quote` never decreases across a correctly rounded swap. The host tests exercise this, but invariant/property fuzzing is still required.
5. Each successful buy/sell has exactly one input token transfer, one output token transfer, and the computed fee transfer. Solana transaction atomicity rolls back all state and CPIs if any transfer fails.
6. Once the quote threshold is reached, no more buys occur. Sells remain enabled while unpaused so holders can redeem from the remaining quote reserve; the flag is not falsely described as migration.

## Deliberately **not** implemented

- No DAMM v2 or Eleven AMM migration, LP mint/lock, graduation keeper, or post-graduation liquidity. `ThresholdReached` is **not** a live pool; quote/base assets remain in the launch vaults. Do **not** deploy with users or describe this as an end-to-end launch until migration and a holder-safe cancellation/exit path are specified and reviewed.
- A guardian pause currently stops both buys and sells. There is no emergency redemption while paused, and unsold inventory has no recovery route. Both are deployment blockers; relaxing pause or adding an admin withdrawal without a reviewed invariant would be unsafe.
- No creator allocation, vesting schedule, anti-sniping controls, dynamic fee schedule, token metadata account/URI, direct SOL wrapping, or initial creator buy. These require separate designs, invariant tests, and security review.
- Only a classic virtual constant-product curve exists. The app's Flat, Exponential, and Long cards are **not** represented here and must not be advertised as controlling this program. This program has no Meteora SDK or program dependency.
- No app/backend integration. The current CHTMS UI preview remains local and is not an on-chain mint.

The current Eleven UI reference uses a 1,000,000,000-token supply, 100 bps fee, 10 SOL quote threshold, 20% migration allocation, and optional 10% creator vesting over 12 months. Only the supply/fee/threshold can be expressed here; the migration allocation and vesting cannot. A supply of one billion with six decimals is passed as **1,000,000,000,000,000 base atoms**, not `1,000,000,000`.

## Build and safety gate

`Anchor.toml` and `declare_id!` use a public example address **only so the source can compile**. Generate a fresh program keypair and sync its ID before any deployment. `BOOTSTRAP_ADMIN` is intentionally the unsignable System Program address, so `initialize_global` cannot succeed until an explicitly approved Eleven governance/multisig public key is set. Do not infer that key from the local Solana CLI wallet or use a personal key as long-term upgrade authority.

From this directory, `cargo test --offline -j 1` is intended to run host math tests if Anchor 0.32.1 is cached. The host tests **have not run** on this Mac: Apple's `cc` linker exits before compiling the program because the Xcode licence has not been accepted. `cargo fmt --all` did parse/format the source, but that is not a build or test result. Before any Testnet deployment, add an independent review, adversarial integration/fuzz tests, metadata, migration/exit design, multisig authority plan, and verify the precise deployed program binary/hash on-chain. Testnet has no official Meteora DBC/DAMM v2 programs; any future Eleven-owned pool must be labelled Eleven, not Meteora.
