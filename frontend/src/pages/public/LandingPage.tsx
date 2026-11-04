import { lazy } from 'react';
import { EnterpriseReliability } from '../../components/public/EnterpriseReliability';
import { HeroSection } from '../../components/public/HeroSection';
import { HowItWorks } from '../../components/public/HowItWorks';
import { LazySection } from '../../components/public/LazySection';
import { ProductCapabilities } from '../../components/public/ProductCapabilities';
import { PublicLayout } from '../../components/public/PublicLayout';
import { usePageMeta } from '../../hooks/usePageMeta';
import { BRAND, pageTitle } from '../../lib/brand';

const PlatformExplainers = lazy(() => import('../../components/public/PlatformExplainers'));

/** Public landing page at `/` (spec PUBLIC_ACCESS §1, §6) — shown to everyone, signed in or not. */
export default function LandingPage() {
  usePageMeta(
    pageTitle(BRAND.subtitle),
    `${BRAND.tagline} SA brings incident, problem and change management, the service desk, assets and CMDB, SLA monitoring, workflow automation, analytics, a knowledge base and SA AI into one enterprise operations workspace.`,
  );
  return (
    <PublicLayout className="pub-landing">
      <HeroSection />
      <ProductCapabilities />
      <HowItWorks />
      <EnterpriseReliability />
      <LazySection component={PlatformExplainers} minHeight="70vh" />
    </PublicLayout>
  );
}
