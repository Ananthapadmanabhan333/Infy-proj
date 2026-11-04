import { useEffect } from 'react';

function descriptionTag(): HTMLMetaElement {
  let tag = document.head.querySelector<HTMLMetaElement>('meta[name="description"]');
  if (!tag) {
    tag = document.createElement('meta');
    tag.name = 'description';
    document.head.appendChild(tag);
  }
  return tag;
}

/**
 * Sets `document.title` and the meta description for a route, restoring the previous values when the
 * route unmounts (so the authenticated app keeps its default title).
 */
export function usePageMeta(title: string, description: string): void {
  useEffect(() => {
    const previousTitle = document.title;
    const tag = descriptionTag();
    const previousDescription = tag.getAttribute('content');
    document.title = title;
    tag.setAttribute('content', description);
    return () => {
      document.title = previousTitle;
      if (previousDescription === null) tag.removeAttribute('content');
      else tag.setAttribute('content', previousDescription);
    };
  }, [title, description]);
}
