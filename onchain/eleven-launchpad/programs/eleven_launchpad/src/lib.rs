//! Independent Eleven research program. This is not Meteora DBC or DAMM v2.
//! Do not deploy with user funds: migration, vesting, metadata, and independent
//! security review are not yet implemented. See the project README.

use anchor_lang::prelude::*;
use anchor_spl::associated_token::AssociatedToken;
use anchor_spl::token::{
    self, spl_token, Mint, MintTo, SetAuthority, Token, TokenAccount, Transfer,
};
use spl_token::instruction::AuthorityType;

// A compile-time placeholder. A fresh program keypair and `anchor keys sync`
// are required before any deployment.
declare_id!("Fg6PaFpoGXkYsidMpWxTWqkZp4wVghNKD2M1qvY65mFG");

// Intentionally inert: the System Program address cannot be a transaction
// signer. Replace with an explicitly approved Eleven governance public key
// before deployment. Never use a first-caller-wins singleton initializer.
const BOOTSTRAP_ADMIN: Pubkey = Pubkey::new_from_array([0; 32]);
const BPS_DENOMINATOR: u128 = 10_000;
const ABSOLUTE_MAX_FEE_BPS: u16 = 1_000;
const ACTIVE: u8 = 0;
const THRESHOLD_REACHED: u8 = 1;
const TOKEN_DECIMALS: u8 = 6;

#[program]
pub mod eleven_launchpad {
    use super::*;

    pub fn initialize_global(
        ctx: Context<InitializeGlobal>,
        guardian: Pubkey,
        fee_treasury: Pubkey,
        max_fee_bps: u16,
    ) -> Result<()> {
        require_keys_eq!(
            ctx.accounts.admin.key(),
            BOOTSTRAP_ADMIN,
            LaunchError::Unauthorized
        );
        require!(guardian != Pubkey::default(), LaunchError::InvalidAuthority);
        require!(
            fee_treasury != Pubkey::default(),
            LaunchError::InvalidAuthority
        );
        require!(max_fee_bps <= ABSOLUTE_MAX_FEE_BPS, LaunchError::InvalidFee);
        let global = &mut ctx.accounts.global;
        global.admin = ctx.accounts.admin.key();
        global.pending_admin = Pubkey::default();
        global.guardian = guardian;
        global.fee_treasury = fee_treasury;
        global.max_fee_bps = max_fee_bps;
        global.paused = false;
        global.bump = ctx.bumps.global;
        Ok(())
    }

    pub fn propose_admin(ctx: Context<AdminAction>, next_admin: Pubkey) -> Result<()> {
        require_keys_eq!(
            ctx.accounts.actor.key(),
            ctx.accounts.global.admin,
            LaunchError::Unauthorized
        );
        require!(
            next_admin != Pubkey::default(),
            LaunchError::InvalidAuthority
        );
        ctx.accounts.global.pending_admin = next_admin;
        Ok(())
    }

    pub fn accept_admin(ctx: Context<AdminAction>) -> Result<()> {
        require_keys_eq!(
            ctx.accounts.actor.key(),
            ctx.accounts.global.pending_admin,
            LaunchError::Unauthorized
        );
        ctx.accounts.global.admin = ctx.accounts.actor.key();
        ctx.accounts.global.pending_admin = Pubkey::default();
        Ok(())
    }

    pub fn set_guardian(ctx: Context<AdminAction>, guardian: Pubkey) -> Result<()> {
        require_keys_eq!(
            ctx.accounts.actor.key(),
            ctx.accounts.global.admin,
            LaunchError::Unauthorized
        );
        require!(guardian != Pubkey::default(), LaunchError::InvalidAuthority);
        ctx.accounts.global.guardian = guardian;
        Ok(())
    }

    pub fn set_fee_treasury(ctx: Context<AdminAction>, treasury: Pubkey) -> Result<()> {
        require_keys_eq!(
            ctx.accounts.actor.key(),
            ctx.accounts.global.admin,
            LaunchError::Unauthorized
        );
        require!(treasury != Pubkey::default(), LaunchError::InvalidAuthority);
        ctx.accounts.global.fee_treasury = treasury;
        Ok(())
    }

    pub fn set_paused(ctx: Context<AdminAction>, paused: bool) -> Result<()> {
        let global = &mut ctx.accounts.global;
        if paused {
            require!(
                ctx.accounts.actor.key() == global.admin
                    || ctx.accounts.actor.key() == global.guardian,
                LaunchError::Unauthorized
            );
        } else {
            require_keys_eq!(
                ctx.accounts.actor.key(),
                global.admin,
                LaunchError::Unauthorized
            );
        }
        global.paused = paused;
        Ok(())
    }

    /// Creates a six-decimal SPL token and places its entire fixed supply in
    /// the launch PDA's token vault. No creator inventory or vesting is issued.
    pub fn create_launch(ctx: Context<CreateLaunch>, params: LaunchParams) -> Result<()> {
        require!(!ctx.accounts.global.paused, LaunchError::Paused);
        require!(params.total_supply_raw > 0, LaunchError::InvalidSupply);
        require!(
            params.initial_virtual_base >= params.total_supply_raw,
            LaunchError::InvalidCurve
        );
        require!(params.initial_virtual_quote > 0, LaunchError::InvalidCurve);
        require!(
            params.graduation_quote_raw > 0,
            LaunchError::InvalidThreshold
        );
        require!(
            params.fee_bps <= ctx.accounts.global.max_fee_bps,
            LaunchError::InvalidFee
        );
        require_keys_eq!(
            ctx.accounts.quote_mint.key(),
            spl_token::native_mint::id(),
            LaunchError::WrongQuoteMint
        );

        let launch = &mut ctx.accounts.launch;
        launch.creator = ctx.accounts.creator.key();
        launch.salt = params.mint_salt;
        launch.mint = ctx.accounts.mint.key();
        launch.quote_mint = ctx.accounts.quote_mint.key();
        launch.base_vault = ctx.accounts.base_vault.key();
        launch.quote_vault = ctx.accounts.quote_vault.key();
        launch.virtual_base = u128::from(params.initial_virtual_base);
        launch.virtual_quote = u128::from(params.initial_virtual_quote);
        launch.total_supply = params.total_supply_raw;
        launch.sold_base = 0;
        launch.quote_reserve = 0;
        launch.graduation_quote = params.graduation_quote_raw;
        launch.fee_bps = params.fee_bps;
        launch.status = ACTIVE;
        launch.bump = ctx.bumps.launch;

        let creator_key = launch.creator;
        let salt_bytes = launch.salt.to_le_bytes();
        let bump_seed = [launch.bump];
        let signer_seeds: &[&[u8]] = &[b"launch", creator_key.as_ref(), &salt_bytes, &bump_seed];
        let signer = &[signer_seeds];
        token::mint_to(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.to_account_info(),
                MintTo {
                    mint: ctx.accounts.mint.to_account_info(),
                    to: ctx.accounts.base_vault.to_account_info(),
                    authority: launch.to_account_info(),
                },
                signer,
            ),
            params.total_supply_raw,
        )?;
        // Fixed supply: no authority can inflate this mint after creation.
        token::set_authority(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.to_account_info(),
                SetAuthority {
                    account_or_mint: ctx.accounts.mint.to_account_info(),
                    current_authority: launch.to_account_info(),
                },
                signer,
            ),
            AuthorityType::MintTokens,
            None,
        )?;
        emit!(LaunchCreated {
            creator: launch.creator,
            mint: launch.mint,
            total_supply: launch.total_supply,
            graduation_quote: launch.graduation_quote,
        });
        Ok(())
    }

    /// Exact quote input. The program computes base output and enforces the
    /// caller's minimum; the client never supplies a trusted price.
    pub fn buy(
        ctx: Context<Trade>,
        quote_in: u64,
        min_base_out: u64,
        deadline_unix: i64,
    ) -> Result<()> {
        require!(!ctx.accounts.global.paused, LaunchError::Paused);
        require!(
            Clock::get()?.unix_timestamp <= deadline_unix,
            LaunchError::Expired
        );
        require!(
            ctx.accounts.launch.status == ACTIVE,
            LaunchError::TradingClosed
        );
        let fee_bps = ctx.accounts.launch.fee_bps;
        let quote = quote_buy(
            ctx.accounts.launch.virtual_base,
            ctx.accounts.launch.virtual_quote,
            quote_in,
            fee_bps,
        )?;
        check_minimum_output(quote.base_out, min_base_out)?;
        require!(
            quote.base_out <= ctx.accounts.base_vault.amount,
            LaunchError::InsufficientReserve
        );
        let (new_sold, new_quote_reserve) = {
            let launch = &ctx.accounts.launch;
            (
                launch
                    .sold_base
                    .checked_add(quote.base_out)
                    .ok_or(LaunchError::MathOverflow)?,
                launch
                    .quote_reserve
                    .checked_add(quote.quote_net)
                    .ok_or(LaunchError::MathOverflow)?,
            )
        };
        require!(
            new_sold <= ctx.accounts.launch.total_supply,
            LaunchError::InsufficientReserve
        );
        {
            let launch = &mut ctx.accounts.launch;
            launch.virtual_base = quote.next_virtual_base;
            launch.virtual_quote = quote.next_virtual_quote;
            launch.sold_base = new_sold;
            launch.quote_reserve = new_quote_reserve;
            if new_quote_reserve >= launch.graduation_quote {
                launch.status = THRESHOLD_REACHED;
            }
        }

        token::transfer(
            CpiContext::new(
                ctx.accounts.token_program.to_account_info(),
                Transfer {
                    from: ctx.accounts.user_quote.to_account_info(),
                    to: ctx.accounts.quote_vault.to_account_info(),
                    authority: ctx.accounts.user.to_account_info(),
                },
            ),
            quote.quote_net,
        )?;
        if quote.fee > 0 {
            token::transfer(
                CpiContext::new(
                    ctx.accounts.token_program.to_account_info(),
                    Transfer {
                        from: ctx.accounts.user_quote.to_account_info(),
                        to: ctx.accounts.fee_quote.to_account_info(),
                        authority: ctx.accounts.user.to_account_info(),
                    },
                ),
                quote.fee,
            )?;
        }
        let mint_key = ctx.accounts.launch.mint;
        let creator_key = ctx.accounts.launch.creator;
        let salt_bytes = ctx.accounts.launch.salt.to_le_bytes();
        let bump_seed = [ctx.accounts.launch.bump];
        let signer_seeds: &[&[u8]] = &[b"launch", creator_key.as_ref(), &salt_bytes, &bump_seed];
        token::transfer(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.to_account_info(),
                Transfer {
                    from: ctx.accounts.base_vault.to_account_info(),
                    to: ctx.accounts.user_base.to_account_info(),
                    authority: ctx.accounts.launch.to_account_info(),
                },
                &[signer_seeds],
            ),
            quote.base_out,
        )?;
        emit!(TradeExecuted {
            mint: mint_key,
            trader: ctx.accounts.user.key(),
            is_buy: true,
            base_amount: quote.base_out,
            quote_amount: quote_in,
            fee_quote: quote.fee,
            status: ctx.accounts.launch.status,
        });
        Ok(())
    }

    /// Exact base input. Sells remain possible after the threshold is reached
    /// so token holders are not trapped by the omitted migration phase.
    pub fn sell(
        ctx: Context<Trade>,
        base_in: u64,
        min_quote_out: u64,
        deadline_unix: i64,
    ) -> Result<()> {
        require!(!ctx.accounts.global.paused, LaunchError::Paused);
        require!(
            Clock::get()?.unix_timestamp <= deadline_unix,
            LaunchError::Expired
        );
        let quote = quote_sell(
            ctx.accounts.launch.virtual_base,
            ctx.accounts.launch.virtual_quote,
            base_in,
            ctx.accounts.launch.fee_bps,
        )?;
        check_minimum_output(quote.quote_out, min_quote_out)?;
        require!(
            base_in <= ctx.accounts.launch.sold_base,
            LaunchError::InsufficientReserve
        );
        require!(
            quote.quote_gross <= ctx.accounts.launch.quote_reserve
                && quote.quote_gross <= ctx.accounts.quote_vault.amount,
            LaunchError::InsufficientReserve
        );
        {
            let launch = &mut ctx.accounts.launch;
            launch.virtual_base = quote.next_virtual_base;
            launch.virtual_quote = quote.next_virtual_quote;
            launch.sold_base = launch
                .sold_base
                .checked_sub(base_in)
                .ok_or(LaunchError::MathOverflow)?;
            launch.quote_reserve = launch
                .quote_reserve
                .checked_sub(quote.quote_gross)
                .ok_or(LaunchError::MathOverflow)?;
            // The threshold flag is deliberately one-way. There is no DAMM
            // migration or claim that liquidity has moved elsewhere.
        }
        token::transfer(
            CpiContext::new(
                ctx.accounts.token_program.to_account_info(),
                Transfer {
                    from: ctx.accounts.user_base.to_account_info(),
                    to: ctx.accounts.base_vault.to_account_info(),
                    authority: ctx.accounts.user.to_account_info(),
                },
            ),
            base_in,
        )?;
        let mint_key = ctx.accounts.launch.mint;
        let creator_key = ctx.accounts.launch.creator;
        let salt_bytes = ctx.accounts.launch.salt.to_le_bytes();
        let bump_seed = [ctx.accounts.launch.bump];
        let signer_seeds: &[&[u8]] = &[b"launch", creator_key.as_ref(), &salt_bytes, &bump_seed];
        let signer = &[signer_seeds];
        token::transfer(
            CpiContext::new_with_signer(
                ctx.accounts.token_program.to_account_info(),
                Transfer {
                    from: ctx.accounts.quote_vault.to_account_info(),
                    to: ctx.accounts.user_quote.to_account_info(),
                    authority: ctx.accounts.launch.to_account_info(),
                },
                signer,
            ),
            quote.quote_out,
        )?;
        if quote.fee > 0 {
            token::transfer(
                CpiContext::new_with_signer(
                    ctx.accounts.token_program.to_account_info(),
                    Transfer {
                        from: ctx.accounts.quote_vault.to_account_info(),
                        to: ctx.accounts.fee_quote.to_account_info(),
                        authority: ctx.accounts.launch.to_account_info(),
                    },
                    signer,
                ),
                quote.fee,
            )?;
        }
        emit!(TradeExecuted {
            mint: mint_key,
            trader: ctx.accounts.user.key(),
            is_buy: false,
            base_amount: base_in,
            quote_amount: quote.quote_out,
            fee_quote: quote.fee,
            status: ctx.accounts.launch.status,
        });
        Ok(())
    }
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone)]
pub struct LaunchParams {
    pub mint_salt: u64,
    pub total_supply_raw: u64,
    pub initial_virtual_base: u64,
    pub initial_virtual_quote: u64,
    pub graduation_quote_raw: u64,
    pub fee_bps: u16,
}

#[derive(Accounts)]
pub struct InitializeGlobal<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(init, payer = admin, space = 8 + GlobalConfig::LEN, seeds = [b"global"], bump)]
    pub global: Account<'info, GlobalConfig>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct AdminAction<'info> {
    pub actor: Signer<'info>,
    #[account(mut, seeds = [b"global"], bump = global.bump)]
    pub global: Account<'info, GlobalConfig>,
}

#[derive(Accounts)]
#[instruction(params: LaunchParams)]
pub struct CreateLaunch<'info> {
    #[account(mut)]
    pub creator: Signer<'info>,
    #[account(seeds = [b"global"], bump = global.bump)]
    pub global: Account<'info, GlobalConfig>,
    #[account(init, payer = creator, space = 8 + Launch::LEN, seeds = [b"launch", creator.key().as_ref(), &params.mint_salt.to_le_bytes()], bump)]
    pub launch: Account<'info, Launch>,
    #[account(
        init, payer = creator,
        mint::decimals = TOKEN_DECIMALS,
        mint::authority = launch,
        seeds = [b"mint", launch.key().as_ref()],
        bump
    )]
    pub mint: Account<'info, Mint>,
    #[account(address = spl_token::native_mint::id())]
    pub quote_mint: Account<'info, Mint>,
    #[account(init, payer = creator, associated_token::mint = mint, associated_token::authority = launch)]
    pub base_vault: Account<'info, TokenAccount>,
    #[account(init, payer = creator, associated_token::mint = quote_mint, associated_token::authority = launch)]
    pub quote_vault: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
    pub associated_token_program: Program<'info, AssociatedToken>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Trade<'info> {
    pub user: Signer<'info>,
    #[account(seeds = [b"global"], bump = global.bump)]
    pub global: Account<'info, GlobalConfig>,
    #[account(mut, seeds = [b"launch", launch.creator.as_ref(), &launch.salt.to_le_bytes()], bump = launch.bump)]
    pub launch: Account<'info, Launch>,
    #[account(mut, address = launch.base_vault, token::mint = launch.mint, token::authority = launch)]
    pub base_vault: Account<'info, TokenAccount>,
    #[account(mut, address = launch.quote_vault, token::mint = launch.quote_mint, token::authority = launch)]
    pub quote_vault: Account<'info, TokenAccount>,
    #[account(mut, token::mint = launch.mint, token::authority = user)]
    pub user_base: Account<'info, TokenAccount>,
    #[account(mut, token::mint = launch.quote_mint, token::authority = user)]
    pub user_quote: Account<'info, TokenAccount>,
    #[account(mut, token::mint = launch.quote_mint, token::authority = global.fee_treasury)]
    pub fee_quote: Account<'info, TokenAccount>,
    pub token_program: Program<'info, Token>,
}

#[account]
pub struct GlobalConfig {
    pub admin: Pubkey,
    pub pending_admin: Pubkey,
    pub guardian: Pubkey,
    pub fee_treasury: Pubkey,
    pub max_fee_bps: u16,
    pub paused: bool,
    pub bump: u8,
}
impl GlobalConfig {
    pub const LEN: usize = 32 * 4 + 2 + 1 + 1;
}

#[account]
pub struct Launch {
    pub creator: Pubkey,
    pub salt: u64,
    pub mint: Pubkey,
    pub quote_mint: Pubkey,
    pub base_vault: Pubkey,
    pub quote_vault: Pubkey,
    pub virtual_base: u128,
    pub virtual_quote: u128,
    pub total_supply: u64,
    pub sold_base: u64,
    pub quote_reserve: u64,
    pub graduation_quote: u64,
    pub fee_bps: u16,
    pub status: u8,
    pub bump: u8,
}
impl Launch {
    pub const LEN: usize = 32 * 5 + 16 * 2 + 8 * 5 + 2 + 1 + 1;
}

#[event]
pub struct LaunchCreated {
    pub creator: Pubkey,
    pub mint: Pubkey,
    pub total_supply: u64,
    pub graduation_quote: u64,
}

#[event]
pub struct TradeExecuted {
    pub mint: Pubkey,
    pub trader: Pubkey,
    pub is_buy: bool,
    pub base_amount: u64,
    pub quote_amount: u64,
    pub fee_quote: u64,
    pub status: u8,
}

#[derive(Debug, PartialEq, Eq)]
struct BuyQuote {
    base_out: u64,
    quote_net: u64,
    fee: u64,
    next_virtual_base: u128,
    next_virtual_quote: u128,
}

#[derive(Debug, PartialEq, Eq)]
struct SellQuote {
    quote_gross: u64,
    quote_out: u64,
    fee: u64,
    next_virtual_base: u128,
    next_virtual_quote: u128,
}

fn ceil_fee(amount: u64, bps: u16) -> Result<u64> {
    require!(bps <= ABSOLUTE_MAX_FEE_BPS, LaunchError::InvalidFee);
    let numerator = u128::from(amount)
        .checked_mul(u128::from(bps))
        .ok_or(LaunchError::MathOverflow)?
        .checked_add(BPS_DENOMINATOR - 1)
        .ok_or(LaunchError::MathOverflow)?;
    u64::try_from(numerator / BPS_DENOMINATOR).map_err(|_| error!(LaunchError::MathOverflow))
}

fn check_minimum_output(actual: u64, minimum: u64) -> Result<()> {
    require!(minimum > 0 && actual >= minimum, LaunchError::Slippage);
    Ok(())
}

fn quote_buy(
    virtual_base: u128,
    virtual_quote: u128,
    quote_in: u64,
    fee_bps: u16,
) -> Result<BuyQuote> {
    require!(
        virtual_base > 0 && virtual_quote > 0,
        LaunchError::InvalidCurve
    );
    require!(quote_in > 0, LaunchError::ZeroTrade);
    let fee = ceil_fee(quote_in, fee_bps)?;
    let quote_net = quote_in.checked_sub(fee).ok_or(LaunchError::MathOverflow)?;
    require!(quote_net > 0, LaunchError::DustTrade);
    let next_virtual_quote = virtual_quote
        .checked_add(u128::from(quote_net))
        .ok_or(LaunchError::MathOverflow)?;
    let base_out_wide = virtual_base
        .checked_mul(u128::from(quote_net))
        .ok_or(LaunchError::MathOverflow)?
        / next_virtual_quote;
    let base_out = u64::try_from(base_out_wide).map_err(|_| error!(LaunchError::MathOverflow))?;
    require!(base_out > 0, LaunchError::DustTrade);
    let next_virtual_base = virtual_base
        .checked_sub(base_out_wide)
        .ok_or(LaunchError::MathOverflow)?;
    Ok(BuyQuote {
        base_out,
        quote_net,
        fee,
        next_virtual_base,
        next_virtual_quote,
    })
}

fn quote_sell(
    virtual_base: u128,
    virtual_quote: u128,
    base_in: u64,
    fee_bps: u16,
) -> Result<SellQuote> {
    require!(
        virtual_base > 0 && virtual_quote > 0,
        LaunchError::InvalidCurve
    );
    require!(base_in > 0, LaunchError::ZeroTrade);
    let next_virtual_base = virtual_base
        .checked_add(u128::from(base_in))
        .ok_or(LaunchError::MathOverflow)?;
    let gross_wide = virtual_quote
        .checked_mul(u128::from(base_in))
        .ok_or(LaunchError::MathOverflow)?
        / next_virtual_base;
    let quote_gross = u64::try_from(gross_wide).map_err(|_| error!(LaunchError::MathOverflow))?;
    require!(quote_gross > 0, LaunchError::DustTrade);
    let fee = ceil_fee(quote_gross, fee_bps)?;
    let quote_out = quote_gross
        .checked_sub(fee)
        .ok_or(LaunchError::MathOverflow)?;
    require!(quote_out > 0, LaunchError::DustTrade);
    let next_virtual_quote = virtual_quote
        .checked_sub(gross_wide)
        .ok_or(LaunchError::MathOverflow)?;
    Ok(SellQuote {
        quote_gross,
        quote_out,
        fee,
        next_virtual_base,
        next_virtual_quote,
    })
}

#[error_code]
pub enum LaunchError {
    #[msg("Unauthorized")]
    Unauthorized,
    #[msg("Invalid authority")]
    InvalidAuthority,
    #[msg("Launchpad is paused")]
    Paused,
    #[msg("Fee exceeds the configured cap")]
    InvalidFee,
    #[msg("Invalid token supply")]
    InvalidSupply,
    #[msg("Invalid virtual-reserve curve")]
    InvalidCurve,
    #[msg("Invalid graduation threshold")]
    InvalidThreshold,
    #[msg("Quote mint must be native WSOL")]
    WrongQuoteMint,
    #[msg("Trade expired")]
    Expired,
    #[msg("Curve buying is closed after the threshold")]
    TradingClosed,
    #[msg("Insufficient reserve or inventory")]
    InsufficientReserve,
    #[msg("Minimum output not met")]
    Slippage,
    #[msg("Arithmetic overflow or underflow")]
    MathOverflow,
    #[msg("Trade amount must be positive")]
    ZeroTrade,
    #[msg("Trade is too small after fees or rounding")]
    DustTrade,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buy_then_sell_never_extracts_more_quote_than_paid() {
        let base = 1_000_000_000_000_000u128;
        let quote = 50_000_000_000u128;
        let buy = quote_buy(base, quote, 1_000_000_000, 100).unwrap();
        let sell = quote_sell(
            buy.next_virtual_base,
            buy.next_virtual_quote,
            buy.base_out,
            100,
        )
        .unwrap();
        assert!(sell.quote_out < 1_000_000_000);
        assert!(buy.next_virtual_base * buy.next_virtual_quote >= base * quote);
        assert!(
            sell.next_virtual_base * sell.next_virtual_quote
                >= buy.next_virtual_base * buy.next_virtual_quote
        );
    }

    #[test]
    fn amounts_are_monotone_and_fees_round_up() {
        let a = quote_buy(1_000_000_000, 1_000_000, 10_000, 100).unwrap();
        let b = quote_buy(1_000_000_000, 1_000_000, 20_000, 100).unwrap();
        assert!(b.base_out > a.base_out);
        assert_eq!(ceil_fee(1, 100).unwrap(), 1);
        assert_eq!(ceil_fee(10_000, 100).unwrap(), 100);
    }

    #[test]
    fn zero_dust_and_overflow_fail_closed() {
        assert!(quote_buy(100, 100, 0, 100).is_err());
        assert!(quote_buy(100, 100, 1, 100).is_err());
        assert!(quote_sell(100, 100, 0, 100).is_err());
        assert!(quote_buy(u128::MAX, 1, u64::MAX, 0).is_err());
        assert!(quote_sell(1, u128::MAX, u64::MAX, 0).is_err());
    }

    #[test]
    fn unknown_fee_is_rejected() {
        assert!(ceil_fee(1_000, 1_001).is_err());
    }

    #[test]
    fn zero_or_unmet_slippage_floor_is_rejected() {
        assert!(check_minimum_output(100, 0).is_err());
        assert!(check_minimum_output(100, 101).is_err());
        assert!(check_minimum_output(100, 100).is_ok());
    }

    #[test]
    fn bounded_property_sweep_preserves_curve_and_fee_invariants() {
        // Deterministic values keep this test reproducible and cheap. This is
        // not a replacement for stateful fuzzing or token-CPI integration tests.
        let mut seed = 0xE1E7_EE11_u64;
        for _ in 0..256 {
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
            let base = 1_000_000_000_000u128 + u128::from(seed % 1_000_000_000);
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
            let quote = 100_000_000u128 + u128::from(seed % 1_000_000_000);
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
            let quote_in = 10_000u64 + seed % 10_000_000;
            let fee_bps = [0, 1, 25, 100, 500, 1_000][(seed % 6) as usize];

            let buy = quote_buy(base, quote, quote_in, fee_bps).unwrap();
            assert!(buy.base_out > 0);
            assert_eq!(buy.quote_net + buy.fee, quote_in);
            let start_k = base * quote;
            let after_buy_k = buy.next_virtual_base * buy.next_virtual_quote;
            assert!(after_buy_k >= start_k);

            let sell = quote_sell(
                buy.next_virtual_base,
                buy.next_virtual_quote,
                buy.base_out,
                fee_bps,
            )
            .unwrap();
            assert!(sell.quote_out > 0);
            assert_eq!(sell.quote_out + sell.fee, sell.quote_gross);
            assert!(sell.quote_out <= quote_in);
            let after_sell_k = sell.next_virtual_base * sell.next_virtual_quote;
            assert!(after_sell_k >= after_buy_k);
        }
    }
}
