import { useId, type HTMLAttributes, type ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';
import { Link } from 'react-router-dom';

type PanelProps = Omit<HTMLAttributes<HTMLElement>, 'title'> & {
  children: ReactNode;
  /** Renders a PanelTitle and labels the section with it. */
  title?: ReactNode;
  subtitle?: ReactNode;
  /** Right-hand header content (buttons, FilterSelect…). */
  actions?: ReactNode;
  /** Text action at the top right ("View all ›"), as a link (`to`) or button (`onAction`). */
  action?: string;
  to?: string;
  onAction?: () => void;
  /** Elevated surface (#25211D). */
  raised?: boolean;
};

/** Rounded card with a 1 px warm border. */
export function Panel({ className = '', children, title, subtitle, actions, action, to, onAction, raised, ...rest }: PanelProps) {
  const titleId = useId();
  const labelled = title !== undefined && !rest['aria-label'] && !rest['aria-labelledby'];
  return (
    <section className={`panel ${raised ? 'raised' : ''} ${className}`.trim()} aria-labelledby={labelled ? titleId : undefined} {...rest}>
      {title !== undefined && (
        <PanelTitle id={titleId} title={title} subtitle={subtitle} action={action} to={to} onAction={onAction}>
          {actions}
        </PanelTitle>
      )}
      {children}
    </section>
  );
}

interface PanelTitleProps {
  title: ReactNode;
  subtitle?: ReactNode;
  /** Text action rendered as a button (onAction) or link (to). */
  action?: string;
  onAction?: () => void;
  to?: string;
  /** Arbitrary right-hand content (buttons, selects…). */
  children?: ReactNode;
  id?: string;
}

export function PanelTitle({ title, subtitle, action, onAction, to, children, id }: PanelTitleProps) {
  return (
    <div className="panel-title">
      <div>
        <h3 id={id}>{title}</h3>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {(children || action) && (
        <div className="panel-title-actions">
          {children}
          {action && to && (
            <Link className="panel-action" to={to}>
              {action} <ChevronRight size={14} />
            </Link>
          )}
          {action && !to && (
            <button type="button" className="panel-action" onClick={onAction}>
              {action} <ChevronRight size={14} />
            </button>
          )}
        </div>
      )}
    </div>
  );
}
