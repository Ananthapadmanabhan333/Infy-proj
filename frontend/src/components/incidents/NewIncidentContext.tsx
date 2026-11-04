import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { NewIncidentModal, type NewIncidentPrefill } from './NewIncidentModal';

interface NewIncidentApi {
  open: (prefill?: NewIncidentPrefill) => void;
}

const NewIncidentContext = createContext<NewIncidentApi | null>(null);

export function NewIncidentProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ open: boolean; prefill?: NewIncidentPrefill }>({ open: false });
  const open = useCallback((prefill?: NewIncidentPrefill) => setState({ open: true, prefill }), []);
  const close = useCallback(() => setState({ open: false }), []);
  const api = useMemo(() => ({ open }), [open]);
  return (
    <NewIncidentContext.Provider value={api}>
      {children}
      {state.open && <NewIncidentModal onClose={close} prefill={state.prefill} />}
    </NewIncidentContext.Provider>
  );
}

export function useNewIncident(): NewIncidentApi {
  const value = useContext(NewIncidentContext);
  if (!value) throw new Error('useNewIncident must be used inside <NewIncidentProvider>');
  return value;
}
