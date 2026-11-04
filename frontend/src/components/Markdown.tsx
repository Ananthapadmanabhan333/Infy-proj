import { Fragment, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

/*
 * Tiny, safe Markdown renderer for knowledge articles. It builds React elements
 * (never injects HTML), so any raw HTML in the source is shown as plain text.
 * Supported: #–###### headings, paragraphs, - / * / 1. lists, ``` fenced code,
 * `inline code`, **bold**, *italic* / _italic_, [links](https://…), > quotes, ---.
 */

export type MdBlock =
  | { type: 'heading'; level: number; text: string }
  | { type: 'paragraph'; text: string }
  | { type: 'code'; language: string; code: string }
  | { type: 'list'; ordered: boolean; items: string[] }
  | { type: 'quote'; text: string }
  | { type: 'rule' };

export function parseMarkdown(source: string): MdBlock[] {
  const lines = source.replace(/\r\n?/g, '\n').split('\n');
  const blocks: MdBlock[] = [];
  let paragraph: string[] = [];

  const flushParagraph = () => {
    if (paragraph.length) {
      blocks.push({ type: 'paragraph', text: paragraph.join(' ') });
      paragraph = [];
    }
  };

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    const trimmed = line.trim();

    const fence = /^```\s*([\w+-]*)\s*$/.exec(trimmed);
    if (fence) {
      flushParagraph();
      const code: string[] = [];
      i += 1;
      while (i < lines.length && !/^```\s*$/.test(lines[i].trim())) {
        code.push(lines[i]);
        i += 1;
      }
      blocks.push({ type: 'code', language: fence[1], code: code.join('\n') });
      continue;
    }

    if (!trimmed) {
      flushParagraph();
      continue;
    }

    const heading = /^(#{1,6})\s+(.+?)\s*#*$/.exec(trimmed);
    if (heading) {
      flushParagraph();
      blocks.push({ type: 'heading', level: heading[1].length, text: heading[2] });
      continue;
    }

    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      flushParagraph();
      blocks.push({ type: 'rule' });
      continue;
    }

    if (trimmed.startsWith('>')) {
      flushParagraph();
      const quote: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith('>')) {
        quote.push(lines[i].trim().replace(/^>\s?/, ''));
        i += 1;
      }
      i -= 1;
      blocks.push({ type: 'quote', text: quote.join(' ') });
      continue;
    }

    const unordered = /^[-*+]\s+(.*)$/;
    const ordered = /^\d+[.)]\s+(.*)$/;
    if (unordered.test(trimmed) || ordered.test(trimmed)) {
      flushParagraph();
      const isOrdered = ordered.test(trimmed);
      const pattern = isOrdered ? ordered : unordered;
      const items: string[] = [];
      while (i < lines.length) {
        const current = lines[i].trim();
        const match = pattern.exec(current);
        if (match) {
          items.push(match[1]);
        } else if (current && items.length && /^\s{2,}/.test(lines[i])) {
          items[items.length - 1] += ` ${current}`;
        } else {
          break;
        }
        i += 1;
      }
      i -= 1;
      blocks.push({ type: 'list', ordered: isOrdered, items });
      continue;
    }

    paragraph.push(trimmed);
  }
  flushParagraph();
  return blocks;
}

/** Only http(s), mailto, in-app absolute paths and anchors are linkable. */
export function safeHref(raw: string): string | null {
  const href = raw.trim();
  if (/^https?:\/\/[^\s]+$/i.test(href)) return href;
  if (/^mailto:[^\s]+$/i.test(href)) return href;
  if (/^\/(?!\/)[^\s]*$/.test(href)) return href;
  if (/^#[\w-]+$/.test(href)) return href;
  return null;
}

const INLINE =
  /(`[^`]+`)|(\*\*[^*]+\*\*|__[^_]+__)|(\*[^*\s][^*]*\*|(?<![\w])_[^_\s][^_]*_(?![\w]))|(\[[^\]]+\]\([^)\s]+\))/;

export function renderInline(text: string, keyPrefix = 'i'): ReactNode[] {
  const nodes: ReactNode[] = [];
  let rest = text;
  let index = 0;
  while (rest) {
    const match = INLINE.exec(rest);
    if (!match) {
      nodes.push(rest);
      break;
    }
    if (match.index > 0) nodes.push(rest.slice(0, match.index));
    const token = match[0];
    const key = `${keyPrefix}-${index}`;
    if (match[1]) {
      nodes.push(<code key={key}>{token.slice(1, -1)}</code>);
    } else if (match[2]) {
      nodes.push(<strong key={key}>{renderInline(token.slice(2, -2), key)}</strong>);
    } else if (match[3]) {
      nodes.push(<em key={key}>{renderInline(token.slice(1, -1), key)}</em>);
    } else if (match[4]) {
      const link = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(token);
      const label = link ? link[1] : token;
      const href = link ? safeHref(link[2]) : null;
      if (!href) {
        nodes.push(<Fragment key={key}>{label}</Fragment>);
      } else if (href.startsWith('/')) {
        nodes.push(
          <Link key={key} to={href}>
            {label}
          </Link>,
        );
      } else {
        nodes.push(
          <a key={key} href={href} target={href.startsWith('#') ? undefined : '_blank'} rel="noopener noreferrer">
            {label}
          </a>,
        );
      }
    }
    rest = rest.slice(match.index + token.length);
    index += 1;
  }
  return nodes;
}

export function Markdown({ source, className = '' }: { source: string; className?: string }) {
  const blocks = parseMarkdown(source);
  return (
    <div className={`md ${className}`}>
      {blocks.map((block, i) => {
        const key = `b${i}`;
        switch (block.type) {
          case 'heading': {
            const level = Math.min(block.level + 1, 6);
            const Tag = `h${level}` as 'h2' | 'h3' | 'h4' | 'h5' | 'h6';
            return <Tag key={key}>{renderInline(block.text, key)}</Tag>;
          }
          case 'paragraph':
            return <p key={key}>{renderInline(block.text, key)}</p>;
          case 'code':
            return (
              <pre key={key} data-language={block.language || undefined}>
                <code>{block.code}</code>
              </pre>
            );
          case 'list': {
            const items = block.items.map((item, j) => <li key={`${key}-${j}`}>{renderInline(item, `${key}-${j}`)}</li>);
            return block.ordered ? <ol key={key}>{items}</ol> : <ul key={key}>{items}</ul>;
          }
          case 'quote':
            return <blockquote key={key}>{renderInline(block.text, key)}</blockquote>;
          case 'rule':
            return <hr key={key} />;
          default:
            return null;
        }
      })}
    </div>
  );
}
