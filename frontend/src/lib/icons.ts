import {
  AppWindow,
  Briefcase,
  Building2,
  Cloud,
  Cpu,
  Database,
  FileText,
  Folder,
  Globe,
  HardDrive,
  Headphones,
  House,
  KeyRound,
  Laptop,
  LayoutGrid,
  LifeBuoy,
  Lock,
  Mail,
  Monitor,
  Network,
  Package,
  Phone,
  Printer,
  Server,
  Shield,
  ShieldCheck,
  Smartphone,
  Truck,
  UserPlus,
  Users,
  Wifi,
  Wrench,
  type LucideIcon,
} from 'lucide-react';

// Curated subset of lucide icons for catalogue categories (keeps the bundle small).
// Unknown names fall back to a generic icon.
const ICONS: Record<string, LucideIcon> = {
  AppWindow,
  Boxes: Package,
  Briefcase,
  Building: Building2,
  Building2,
  Cloud,
  Cpu,
  Database,
  FileText,
  Folder,
  Globe,
  HardDrive,
  Headphones,
  Home: House,
  House,
  Key: KeyRound,
  KeyRound,
  Laptop,
  LayoutGrid,
  LifeBuoy,
  Lock,
  Mail,
  Monitor,
  Network,
  Package,
  Phone,
  Printer,
  Server,
  Shield,
  ShieldCheck,
  Smartphone,
  Truck,
  UserPlus,
  Users,
  Wifi,
  Wrench,
};

export const CATALOG_ICON_NAMES = Object.keys(ICONS).sort();

export function iconByName(name: string | null | undefined): LucideIcon {
  if (!name) return LayoutGrid;
  const pascal = name
    .split(/[-_\s]+/)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join('');
  return ICONS[name] ?? ICONS[pascal] ?? LayoutGrid;
}
