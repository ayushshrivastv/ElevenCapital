import type { RobinhoodAsset } from './robinhood.js';
import { reviewedRobinhoodCompanyLogos } from './robinhood-company-logo-map.js';

/** Replace a reviewed generic issuer image only for the exact canonical underlying instrument. */
export function reviewedRobinhoodCompanyLogo(asset: RobinhoodAsset): string | null {
  const reviewed = reviewedRobinhoodCompanyLogos[asset.id.toLowerCase()];
  const deployment = asset.deployments.length === 1 ? asset.deployments[0] : null;
  if (!reviewed || asset.tokenSymbol !== reviewed.symbol || asset.isin !== reviewed.isin ||
    deployment?.chainId !== 4663 || deployment.contractAddress.toLowerCase() !== reviewed.contract ||
    (asset.logoUrl && asset.logoUrl !== reviewed.issuerLogoUrl)) return null;
  return reviewed.companyLogoUrl;
}
