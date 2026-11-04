import { CAPABILITIES } from './content';
import { PublicLink } from './PublicLink';

/** Twelve capability cards (two rows of six on desktop, one scrolling row on phones); each scrolls to its explainer. */
export function ProductCapabilities() {
  return (
    <section id="capabilities" className="pub-section pub-capabilities" aria-labelledby="capabilities-title">
      <div className="pub-container">
        <h2 id="capabilities-title" className="pub-sr-only">
          Platform capabilities
        </h2>
        <ul className="pub-cap-grid">
          {CAPABILITIES.map((capability) => {
            const Icon = capability.icon;
            return (
              <li key={capability.id}>
                <PublicLink to={`/#${capability.id}`} className="pub-cap-card" aria-describedby={`cap-${capability.id}-desc`}>
                  <Icon className="pub-cap-icon" size={34} strokeWidth={1.5} aria-hidden="true" />
                  <span>
                    <b>{capability.title}</b>
                    <small id={`cap-${capability.id}-desc`}>{capability.short}</small>
                  </span>
                </PublicLink>
              </li>
            );
          })}
        </ul>
      </div>
    </section>
  );
}
