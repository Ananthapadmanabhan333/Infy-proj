import { forwardRef, type InputHTMLAttributes } from 'react';
import { Search, X } from 'lucide-react';

type SearchInputProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value' | 'type'> & {
  value: string;
  onChange: (value: string) => void;
  /** Accessible name (defaults to the placeholder). */
  label?: string;
  /** Optional keyboard hint, e.g. "Ctrl K". */
  shortcut?: string;
  className?: string;
};

/**
 * Search box with icon and clear button. Controlled; pair with `useSearchInput` (hooks/useUrlState)
 * to debounce and sync with the URL.
 */
export const SearchInput = forwardRef<HTMLInputElement, SearchInputProps>(function SearchInput(
  { value, onChange, label, placeholder = 'Search…', shortcut, className = '', ...rest },
  ref,
) {
  return (
    <div className={`search-input ${className}`.trim()} role="search">
      <Search size={15} aria-hidden="true" />
      <input
        ref={ref}
        type="search"
        value={value}
        placeholder={placeholder}
        aria-label={label ?? placeholder}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape' && value) {
            event.preventDefault();
            onChange('');
          }
        }}
        autoComplete="off"
        {...rest}
      />
      {value ? (
        <button type="button" className="clear" onClick={() => onChange('')} aria-label="Clear search">
          <X size={13} />
        </button>
      ) : (
        shortcut && <kbd aria-hidden="true">{shortcut}</kbd>
      )}
    </div>
  );
});
