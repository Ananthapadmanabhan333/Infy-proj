import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Search } from 'lucide-react';
import { useGlobalSearch } from '../../api/search';
import { useDebounce } from '../../hooks/useDebounce';
import { useAuth } from '../../hooks/useAuth';
import { useClickOutside } from '../../hooks/useClickOutside';
import { titleCase } from '../../lib/format';
import { USER_READERS, roleLabel } from '../../lib/constants';

interface ResultEntry {
  key: string;
  group: string;
  title: string;
  subtitle: string;
  to: string | null;
}

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);

export function GlobalSearch() {
  const [term, setTerm] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const debounced = useDebounce(term, 300);
  const search = useGlobalSearch(debounced);
  const navigate = useNavigate();
  const { hasRole } = useAuth();
  const inputRef = useRef<HTMLInputElement>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const listId = useId();
  const canSeeUsers = hasRole(...USER_READERS);

  useClickOutside(wrapperRef, open, () => setOpen(false));

  // Ctrl/⌘ + K focuses the search box from anywhere.
  useEffect(() => {
    const onKey = (event: globalThis.KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        inputRef.current?.focus();
        inputRef.current?.select();
        setOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const entries = useMemo<ResultEntry[]>(() => {
    const data = search.data;
    if (!data || debounced.trim().length < 2) return [];
    return [
      ...data.incidents.map((incident) => ({
        key: `i-${incident.id}`,
        group: 'Incidents',
        title: `${incident.number} · ${incident.title}`,
        subtitle: [titleCase(incident.priority), titleCase(incident.status), incident.serviceName].filter(Boolean).join(' · '),
        to: `/incidents/${incident.id}`,
      })),
      ...data.articles.map((article) => ({
        key: `a-${article.id}`,
        group: 'Knowledge',
        title: `${article.number} · ${article.title}`,
        subtitle: [titleCase(article.articleType), titleCase(article.category)].join(' · '),
        to: `/knowledge/${article.id}`,
      })),
      ...data.services.map((service) => ({
        key: `s-${service.id}`,
        group: 'Services',
        title: service.name,
        subtitle: `${service.code} · ${titleCase(service.status)}`,
        to: `/services/${service.id}`,
      })),
      ...data.users.map((user) => ({
        key: `u-${user.id}`,
        group: 'People',
        title: user.fullName,
        subtitle: [user.email, roleLabel(user.role), user.departmentName].filter(Boolean).join(' · '),
        to: canSeeUsers ? `/users?search=${encodeURIComponent(user.email)}` : null,
      })),
    ];
  }, [search.data, debounced, canSeeUsers]);

  useEffect(() => setActive(0), [entries.length]);

  const go = (entry: ResultEntry | undefined) => {
    if (!entry?.to) return;
    setOpen(false);
    setTerm('');
    navigate(entry.to);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setOpen(true);
      setActive((index) => Math.min(entries.length - 1, index + 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((index) => Math.max(0, index - 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      go(entries[active]);
    } else if (event.key === 'Escape') {
      setOpen(false);
      inputRef.current?.blur();
    }
  };

  const showDropdown = open && term.trim().length >= 2;
  let lastGroup = '';

  return (
    <div className="global-search" ref={wrapperRef}>
      <Search size={16} aria-hidden="true" />
      <input
        ref={inputRef}
        type="search"
        role="combobox"
        aria-label="Search incidents, services, users and knowledge"
        aria-expanded={showDropdown}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={showDropdown && entries[active] ? `${listId}-${entries[active].key}` : undefined}
        placeholder="Search incidents, services, users, knowledge..."
        value={term}
        onChange={(event) => {
          setTerm(event.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        autoComplete="off"
      />
      <kbd aria-hidden="true">{isMac ? '⌘ K' : 'Ctrl K'}</kbd>
      {showDropdown && (
        <div className="search-results" id={listId} role="listbox" aria-label="Search results">
          {(search.isFetching && !search.data) || debounced !== term ? (
            <div className="dropdown-item" aria-live="polite">
              Searching…
            </div>
          ) : search.isError ? (
            <div className="dropdown-item red-text">Search is unavailable right now.</div>
          ) : entries.length === 0 ? (
            <div className="dropdown-item">No results for “{debounced}”.</div>
          ) : (
            entries.map((entry, index) => {
              const header = entry.group !== lastGroup ? entry.group : null;
              lastGroup = entry.group;
              return (
                <div key={entry.key}>
                  {header && (
                    <div className="dropdown-group" role="presentation">
                      {header}
                    </div>
                  )}
                  <button
                    type="button"
                    id={`${listId}-${entry.key}`}
                    role="option"
                    aria-selected={index === active}
                    aria-disabled={!entry.to}
                    data-active={index === active}
                    className="dropdown-item"
                    onMouseEnter={() => setActive(index)}
                    onClick={() => go(entry)}
                    tabIndex={-1}
                  >
                    <div className="min-w-0">
                      <b className="block truncate">{entry.title}</b>
                      <small className="truncate">{entry.subtitle}</small>
                    </div>
                  </button>
                </div>
              );
            })
          )}
        </div>
      )}
    </div>
  );
}
