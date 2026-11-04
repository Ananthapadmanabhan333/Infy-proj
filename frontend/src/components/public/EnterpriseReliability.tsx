import { RELIABILITY } from './content';

/** "Built for Enterprise Reliability" strip — implemented controls only (spec §6). */
export function EnterpriseReliability() {
  return (
    <section id="reliability" className="pub-section pub-reliability" aria-labelledby="reliability-title">
      <div className="pub-container pub-rel-grid">
        <div className="pub-rel-intro">
          <h2 id="reliability-title">Built for Enterprise Reliability</h2>
          <p>Secure, scalable and ready for mission-critical operations.</p>
        </div>
        <ul className="pub-rel-list">
          {RELIABILITY.map((item) => {
            const Icon = item.icon;
            return (
              <li key={item.value}>
                <Icon size={36} strokeWidth={1.4} aria-hidden="true" />
                <span>
                  <b>{item.value}</b>
                  <small>{item.label}</small>
                </span>
              </li>
            );
          })}
        </ul>
      </div>
    </section>
  );
}
