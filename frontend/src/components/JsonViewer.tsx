/** Read-only pretty-printed JSON (React escapes the content; nothing is rendered as HTML). */
export function JsonViewer({ value, label }: { value: unknown; label?: string }) {
  let text: string;
  try {
    text = JSON.stringify(value, null, 2) ?? 'null';
  } catch {
    text = String(value);
  }
  return (
    <pre className="json-viewer mt-2" aria-label={label ?? 'JSON details'} tabIndex={0}>
      {text}
    </pre>
  );
}
