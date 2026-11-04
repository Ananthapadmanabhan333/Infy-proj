-- Product renamed from "NEXUS 360" to "SA 360" (owner's request, 4 Nov 2026).
-- Only the default organisation name is updated; a name an administrator has already customised is left alone.
UPDATE system_settings
SET setting_value = 'SA 360',
    updated_at    = now()
WHERE setting_key = 'org.name'
  AND setting_value = 'NEXUS 360';
