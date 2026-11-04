import { FlaskConical } from 'lucide-react';
import { PageHeader } from '../../components/PageHeader';
import { EmptyState } from '../../components/States';
import { Tabs } from '../../components/Tabs';
import { useAuth } from '../../hooks/useAuth';
import { useTabParam } from '../../hooks/useUrlState';
import { SETTINGS_TABS, type SettingsTabId } from '../../lib/constants';
import { AiTab } from '../settings/AiTab';
import { BusinessHoursTab } from '../settings/BusinessHoursTab';
import { CatalogueTab } from '../settings/CatalogueTab';
import { DemoToolsTab } from '../settings/DemoToolsTab';
import { EmailTab } from '../settings/EmailTab';
import { IncidentCategoriesTab } from '../settings/IncidentCategoriesTab';
import { NotificationDefaultsTab } from '../settings/NotificationDefaultsTab';
import { OrganisationTab } from '../settings/OrganisationTab';
import { SimulationTab } from '../settings/SimulationTab';
import { SlaPoliciesTab } from '../settings/SlaPoliciesTab';
import { SystemHealthTab } from '../settings/SystemHealthTab';
import '../../components/admin/admin.css';

/** Tabs visible to a role (spec §14: each tab is role-gated). */
export function settingsTabsFor(role: string | null | undefined) {
  return SETTINGS_TABS.filter((tab) => Boolean(role) && (tab.roles as string[]).includes(role as string));
}

/** /settings (§14): Organisation, SLA Policies, Business Hours, Incident Categories, Service Catalogue, Notifications, AI, E-mail, System Health, Demo Tools, Simulation mode. */
export default function SettingsPage() {
  const { user } = useAuth();
  const tabs = settingsTabsFor(user?.role);
  const ids = tabs.map((tab) => tab.id);
  const [tab, setTab] = useTabParam<SettingsTabId>(ids, ids[0] ?? 'sla');

  return (
    <>
      <PageHeader title="Settings" subtitle="Organisation, SLA, categories, catalogue, notifications, AI, e-mail and system settings" />
      <div className="page-stack">
        {tabs.length === 0 ? (
          <EmptyState title="No settings available for your role" description="Settings are managed by IT Admins and System Admins." />
        ) : (
          <>
            <Tabs<SettingsTabId> label="Settings sections" value={tab} onChange={setTab} tabs={tabs.map(({ id, label }) => ({ id, label }))}>
              {(tab === 'demo' || tab === 'simulation') && (
                <span className="chip">
                  <FlaskConical size={11} /> {tab === 'demo' ? 'Demo data only' : 'Sample data, flagged SIMULATION'}
                </span>
              )}
            </Tabs>
            {tab === 'organisation' && <OrganisationTab />}
            {tab === 'sla' && <SlaPoliciesTab />}
            {tab === 'business-hours' && <BusinessHoursTab />}
            {tab === 'categories' && <IncidentCategoriesTab />}
            {tab === 'catalogue' && <CatalogueTab />}
            {tab === 'notifications' && <NotificationDefaultsTab />}
            {tab === 'ai' && <AiTab />}
            {tab === 'email' && <EmailTab />}
            {tab === 'health' && <SystemHealthTab />}
            {tab === 'demo' && <DemoToolsTab />}
            {tab === 'simulation' && <SimulationTab />}
          </>
        )}
      </div>
    </>
  );
}
