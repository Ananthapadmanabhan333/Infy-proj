import { ArrowRight, ChevronRight } from 'lucide-react';
import { HOW_IT_WORKS } from './content';
import { PublicLink } from './PublicLink';

export function HowItWorks() {
  return (
    <section id="how-it-works" className="pub-section pub-how" aria-labelledby="how-title">
      <div className="pub-container pub-how-grid">
        <div className="pub-how-intro">
          <h2 id="how-title">How It Works</h2>
          <p>From issue to resolution — a streamlined, intelligent lifecycle.</p>
          <PublicLink to="/#platform" className="pub-btn pub-btn-outline pub-btn-sm">
            <ArrowRight size={15} aria-hidden="true" /> See It In Action
          </PublicLink>
        </div>
        <ol className="pub-steps">
          {HOW_IT_WORKS.map((step, index) => {
            const Icon = step.icon;
            return (
              <li key={step.title} className="pub-step">
                {index > 0 && (
                  <span className="pub-step-arrow" aria-hidden="true">
                    <ChevronRight size={30} strokeWidth={1.6} />
                  </span>
                )}
                <Icon size={30} strokeWidth={1.5} aria-hidden="true" className="pub-step-icon" />
                <h3>
                  {index + 1}. {step.title}
                </h3>
                <p>{step.text}</p>
              </li>
            );
          })}
        </ol>
      </div>
    </section>
  );
}
