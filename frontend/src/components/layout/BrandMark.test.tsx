import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import faviconSvg from '../../../public/brand/favicon.svg?raw';
import logoDarkSvg from '../../../public/brand/sa-logo-dark.svg?raw';
import logoLightSvg from '../../../public/brand/sa-logo-light.svg?raw';
import logoSvg from '../../../public/brand/sa-logo.svg?raw';
import markSvg from '../../../public/brand/sa-mark.svg?raw';
import manifestRaw from '../../../public/brand/site.webmanifest?raw';
import indexHtml from '../../../index.html?raw';
import { BRAND, pageTitle, SA_GLYPH } from '../../lib/brand';
import { BrandLoader } from '../BrandLoader';
import { BrandLockup, BrandMark, BrandWordmark } from './BrandMark';

describe('brand constants', () => {
  it('names the product SA with its subtitle, tagline and AI module', () => {
    expect(BRAND).toEqual({
      name: 'SA',
      subtitle: 'Enterprise Operations Platform',
      tagline: 'Intelligent Service Management. Unified Operations.',
      ai: 'SA AI',
    });
    expect(pageTitle('Incidents')).toBe('SA | Incidents');
    expect(pageTitle('  ')).toBe('SA | Enterprise Operations Platform');
    expect(pageTitle()).toBe('SA | Enterprise Operations Platform');
  });
});

describe('logo components', () => {
  it('BrandMark is the square monogram: decorative by default, named when titled', () => {
    const { container, rerender } = render(<BrandMark />);
    const svg = container.querySelector('svg')!;
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).toHaveAttribute('viewBox', '0 0 64 64');
    expect(svg.querySelector('rect')).not.toBeNull();
    expect(svg.querySelectorAll('path')).toHaveLength(2);
    expect(svg.querySelector('circle')).not.toBeNull();
    rerender(<BrandMark title="SA" />);
    expect(screen.getByRole('img', { name: 'SA' })).toHaveAttribute('data-brand', 'mark');
  });

  it('draws the shared SA letterforms with a champagne-gold gradient and unique gradient ids', () => {
    const { container } = render(
      <>
        <BrandWordmark />
        <BrandWordmark />
      </>,
    );
    const [first, second] = Array.from(container.querySelectorAll('svg'));
    expect(first.querySelector('path')).toHaveAttribute('d', SA_GLYPH.s);
    expect(first.querySelectorAll('path')[1]).toHaveAttribute('d', SA_GLYPH.a);
    const stops = Array.from(first.querySelectorAll('stop')).map((stop) => stop.getAttribute('stop-color'));
    expect(stops).toEqual(['#D6B77A', '#F1DFBA']);
    const firstId = first.querySelector('linearGradient')!.id;
    expect(firstId).not.toBe(second.querySelector('linearGradient')!.id);
    expect(first.querySelector('g')).toHaveAttribute('stroke', `url(#${firstId})`);
    expect(screen.getAllByRole('img', { name: 'SA' })).toHaveLength(2);
  });

  it('has an ink variant for light backgrounds', () => {
    const { container } = render(<BrandWordmark tone="ink" label={null} />);
    const svg = container.querySelector('svg')!;
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg.querySelector('linearGradient')).toBeNull();
    expect(svg.querySelector('g')).toHaveAttribute('stroke', SA_GLYPH.ink);
  });

  it('BrandLockup shows the wordmark with the platform subtitle (or a replacement)', () => {
    const { rerender, container } = render(<BrandLockup />);
    expect(screen.getByRole('img', { name: 'SA' })).toBeInTheDocument();
    expect(screen.getByText('Enterprise Operations Platform')).toBeInTheDocument();
    rerender(<BrandLockup subtitle="Acme Corp" label={null} />);
    expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    rerender(<BrandLockup compact />);
    expect(container.querySelector('.brand-lockup-sub')).toBeNull();
  });

  it('BrandLoader uses the same monogram and announces the product name', () => {
    const { container } = render(<BrandLoader message="Loading SA…" />);
    expect(screen.getByRole('status')).toHaveTextContent(/^SA.*Loading SA…$/);
    expect(container.querySelector('svg[data-brand="mark"]')).not.toBeNull();
    expect(screen.getByRole('status')).not.toHaveTextContent('360');
  });
});

describe('static logo set (public/brand)', () => {
  it('uses the same letterforms and colours as the React components', () => {
    for (const svg of [logoSvg, logoDarkSvg, logoLightSvg, markSvg, faviconSvg]) {
      expect(svg).toContain(`d="${SA_GLYPH.s}"`);
      expect(svg).toContain(`d="${SA_GLYPH.a}"`);
      expect(svg).toContain('<title id="t">SA</title>');
    }
    for (const svg of [logoSvg, logoDarkSvg, markSvg, faviconSvg]) {
      expect(svg).toContain('stop-color="#D6B77A"');
      expect(svg).toContain('stop-color="#F1DFBA"');
    }
    expect(logoLightSvg).toContain('stroke="#1A1714"');
    expect(markSvg).toContain('viewBox="0 0 64 64"');
  });

  it('ships a web manifest named SA with the obsidian theme colour', () => {
    const manifest = JSON.parse(manifestRaw) as { name: string; short_name: string; theme_color: string; background_color: string; icons: { src: string; sizes: string }[] };
    expect(manifest.name).toBe('SA');
    expect(manifest.short_name).toBe('SA');
    expect(manifest.theme_color).toBe('#101012');
    expect(manifest.background_color).toBe('#101012');
    expect(manifest.icons.map((icon) => `${icon.src} ${icon.sizes}`)).toEqual(['/brand/sa-icon-192.png 192x192', '/brand/sa-icon-512.png 512x512', '/brand/sa-mark.svg any']);
  });

  it('wires favicon, apple-touch-icon, manifest, title and Open Graph tags in index.html', () => {
    expect(indexHtml).toContain('<link rel="icon" href="/brand/favicon.svg" type="image/svg+xml" />');
    expect(indexHtml).toContain('<link rel="apple-touch-icon" href="/brand/sa-icon-180.png" sizes="180x180" />');
    expect(indexHtml).toContain('<link rel="manifest" href="/brand/site.webmanifest" />');
    expect(indexHtml).toContain('<title>SA | Enterprise Operations Platform</title>');
    expect(indexHtml).toContain('<meta property="og:site_name" content="SA" />');
    expect(indexHtml).toContain('<meta property="og:description" content="Intelligent Service Management. Unified Operations." />');
    expect(indexHtml).not.toContain('SA 360');
  });
});
