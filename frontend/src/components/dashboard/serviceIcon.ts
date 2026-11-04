import { Banknote, Briefcase, Building2, Cloud, Database, Globe, KeyRound, Laptop, Mail, Network, Printer, Server, ShieldCheck, Users, type LucideIcon } from 'lucide-react';

const RULES: [RegExp, LucideIcon][] = [
  [/payroll|salary|pay\b/, Banknote],
  [/\bhr\b|hr[\s-]?portal|human|people|workday/, Users],
  [/vpn|network|wifi|wi-fi|lan|wan|internet/, Network],
  [/mail|exchange|outlook|smtp/, Mail],
  [/drive|storage|share|sharepoint|file|cloud|o365|365/, Cloud],
  [/database|\bdb\b|sql|oracle|postgres/, Database],
  [/print/, Printer],
  [/identity|sso|auth|access|\bad\b|directory|iam/, KeyRound],
  [/security|firewall|antivirus|endpoint/, ShieldCheck],
  [/facilit|building|office|badge/, Building2],
  [/crm|erp|sap|finance|sales/, Briefcase],
  [/laptop|desktop|device|hardware/, Laptop],
  [/web|portal|site|intranet/, Globe],
];

/** Picks a Lucide icon for a business service from its name/code (falls back to a generic server). */
export function serviceIcon(name: string | null | undefined, code?: string | null): LucideIcon {
  const text = `${name ?? ''} ${code ?? ''}`.toLowerCase();
  return RULES.find(([pattern]) => pattern.test(text))?.[1] ?? Server;
}
