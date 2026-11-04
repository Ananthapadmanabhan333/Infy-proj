import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { Markdown, parseMarkdown, safeHref } from './Markdown';

const source = `# Restart the payroll pool

Check **active connections** and *pool limits* first. Run \`systemctl status payroll\`.

- Drain traffic
- Restart the service

1. Verify
2. Close the incident

\`\`\`bash
kubectl rollout restart deploy/payroll
\`\`\`

See [the runbook](/runbooks/4) or [vendor docs](https://example.com/docs).
<script>alert('x')</script>
[bad link](javascript:alert(1))`;

describe('Markdown renderer', () => {
  it('parses block structure', () => {
    expect(parseMarkdown(source).map((block) => block.type)).toEqual(['heading', 'paragraph', 'list', 'list', 'code', 'paragraph']);
  });

  it('renders headings, emphasis, lists, code and safe links', () => {
    const { container } = render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Markdown source={source} />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { level: 2, name: 'Restart the payroll pool' })).toBeInTheDocument();
    expect(screen.getByText('active connections').tagName).toBe('STRONG');
    expect(screen.getByText('pool limits').tagName).toBe('EM');
    expect(screen.getByText('systemctl status payroll').tagName).toBe('CODE');
    expect(screen.getAllByRole('listitem')).toHaveLength(4);
    expect(container.querySelector('pre code')).toHaveTextContent('kubectl rollout restart deploy/payroll');
    expect(screen.getByRole('link', { name: 'the runbook' })).toHaveAttribute('href', '/runbooks/4');
    expect(screen.getByRole('link', { name: 'vendor docs' })).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('never renders raw HTML or unsafe links', () => {
    const { container } = render(
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Markdown source={source} />
      </MemoryRouter>,
    );
    expect(container.querySelector('script')).toBeNull();
    expect(container).toHaveTextContent("<script>alert('x')</script>");
    expect(screen.queryByRole('link', { name: 'bad link' })).not.toBeInTheDocument();
    expect(safeHref('javascript:alert(1)')).toBeNull();
    expect(safeHref('//evil.example.com')).toBeNull();
  });
});
