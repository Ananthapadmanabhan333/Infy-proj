import { z } from 'zod';
import {
  ANNOUNCEMENT_SEVERITIES,
  APPROVER_ROLES,
  ARTICLE_STATUSES,
  ARTICLE_TYPES,
  CATEGORIES,
  INTEGRATION_TYPES,
  LEVELS,
  LINK_TYPES,
  PRIORITIES,
  RESOLUTION_CODES,
  ROLES,
  WORK_DAYS,
  WORKFLOW_TRIGGERS,
} from './constants';
import type { FormField } from './types';

// Mirrors backend Bean Validation constraints. Select inputs carry IDs as strings
// ('' = none) and are converted to numbers when building request payloads.

const idString = z.string();
const requiredId = (label: string) => z.string().min(1, `${label} is required`);
const enumOrBlank = <T extends string>(values: readonly [T, ...T[]]) => z.union([z.enum(values), z.literal('')]);
const asTuple = <T extends string>(values: T[]) => values as [T, ...T[]];

// ---------- Auth ----------
export const emailSchema = z.string().trim().min(1, 'Email is required').max(254).email('Enter a valid email address');

export const passwordPolicy = z
  .string()
  .min(10, 'Password must be at least 10 characters')
  .max(128, 'Password must be at most 128 characters')
  .regex(/[A-Za-z]/, 'Password must contain at least one letter')
  .regex(/\d/, 'Password must contain at least one digit');

export const loginSchema = z.object({
  email: emailSchema,
  password: z.string().min(1, 'Password is required'),
  /** Persistent session on this device (RELEASE3_SPEC §4); unticked → browser-session cookie. */
  rememberMe: z.boolean().optional(),
});
export type LoginValues = z.infer<typeof loginSchema>;

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Current password is required'),
    newPassword: passwordPolicy,
    confirmPassword: z.string().min(1, 'Confirm your new password'),
  })
  .refine((v) => v.newPassword === v.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' })
  .refine((v) => v.newPassword !== v.currentPassword, { path: ['newPassword'], message: 'New password must differ from the current one' });
export type ChangePasswordValues = z.infer<typeof changePasswordSchema>;

// ---------- Incidents ----------
const incidentTitle = z
  .string()
  .trim()
  .min(5, 'Title must be at least 5 characters')
  .max(200, 'Title must be at most 200 characters');
const incidentDescription = z.string().max(5000, 'Description must be at most 5000 characters');
const affectedUsers = z
  .number({ invalid_type_error: 'Enter the number of affected users' })
  .int('Enter a whole number')
  .min(1, 'At least 1 user must be affected')
  .max(1_000_000, 'Enter a realistic number of users');

export const incidentCreateSchema = z.object({
  title: incidentTitle,
  description: incidentDescription,
  serviceId: idString,
  impact: enumOrBlank(asTuple(LEVELS)),
  urgency: enumOrBlank(asTuple(LEVELS)),
  affectedUsers,
});
export type IncidentCreateValues = z.infer<typeof incidentCreateSchema>;

export const incidentUpdateSchema = z.object({
  title: incidentTitle,
  description: incidentDescription,
  category: z.enum(asTuple(CATEGORIES)),
  impact: z.enum(asTuple(LEVELS)),
  urgency: z.enum(asTuple(LEVELS)),
  serviceId: idString,
  affectedUsers,
});
export type IncidentUpdateValues = z.infer<typeof incidentUpdateSchema>;

export const resolveSchema = z.object({
  resolutionCode: z.union([z.enum(asTuple(RESOLUTION_CODES)), z.literal('')]).refine((v) => v.length > 0, 'Choose a resolution code'),
  resolutionNotes: z.string().trim().min(5, 'Describe how the incident was resolved (min 5 characters)').max(5000),
  rootCause: z.string().max(2000, 'Root cause must be at most 2000 characters'),
  resolutionArticleId: idString,
});
export type ResolveValues = z.infer<typeof resolveSchema>;

export const reasonSchema = z.object({
  reason: z.string().trim().min(3, 'A reason is required').max(1000, 'Reason must be at most 1000 characters'),
});
export type ReasonValues = z.infer<typeof reasonSchema>;

export const commentSchema = z.object({
  body: z.string().trim().min(1, 'Write a comment first').max(5000, 'Comments are limited to 5000 characters'),
});
export type CommentValues = z.infer<typeof commentSchema>;

export const assignSchema = z
  .object({ departmentId: idString, assigneeId: idString })
  .refine((v) => v.departmentId !== '' || v.assigneeId !== '', { path: ['departmentId'], message: 'Choose a department or an assignee' });
export type AssignValues = z.infer<typeof assignSchema>;

export const linkSchema = z.object({
  targetId: requiredId('Incident'),
  linkType: z.enum(asTuple(LINK_TYPES)),
});
export type LinkValues = z.infer<typeof linkSchema>;

// ---------- Users & departments ----------
const fullName = z.string().trim().min(2, 'Full name is required').max(120);
const optionalText = (max: number) => z.string().max(max, `Must be at most ${max} characters`);

export const userCreateSchema = z.object({
  email: emailSchema,
  fullName,
  role: z.enum(asTuple(ROLES)),
  departmentId: idString,
  jobTitle: optionalText(120),
  phone: optionalText(40),
  password: passwordPolicy,
});
export type UserCreateValues = z.infer<typeof userCreateSchema>;

export const userUpdateSchema = z.object({
  fullName,
  role: z.enum(asTuple(ROLES)),
  departmentId: idString,
  jobTitle: optionalText(120),
  phone: optionalText(40),
  active: z.boolean(),
});
export type UserUpdateValues = z.infer<typeof userUpdateSchema>;

export const resetPasswordSchema = z.object({ newPassword: passwordPolicy });
export type ResetPasswordValues = z.infer<typeof resetPasswordSchema>;

const code = z
  .string()
  .trim()
  .min(2, 'Code is required')
  .max(40)
  .regex(/^[A-Z0-9_-]+$/, 'Use upper-case letters, digits, - or _');

export const departmentSchema = z.object({
  code,
  name: z.string().trim().min(2, 'Name is required').max(120),
  description: optionalText(500),
  managerId: idString,
  email: z.union([z.literal(''), emailSchema]),
});
export type DepartmentValues = z.infer<typeof departmentSchema>;

// ---------- Services ----------
export const serviceSchema = z.object({
  code,
  name: z.string().trim().min(2, 'Name is required').max(120),
  description: optionalText(1000),
  departmentId: requiredId('Owning department'),
  ownerId: idString,
  tier: z.enum(['1', '2', '3']),
  keywords: optionalText(500),
  maintenance: z.boolean(),
});
export type ServiceValues = z.infer<typeof serviceSchema>;

// ---------- Catalogue ----------
export const catalogCategorySchema = z.object({
  code,
  name: z.string().trim().min(2, 'Name is required').max(120),
  description: optionalText(500),
  icon: optionalText(60),
});
export type CatalogCategoryValues = z.infer<typeof catalogCategorySchema>;

export const formFieldSchema = z
  .object({
    key: z
      .string()
      .trim()
      .min(1, 'Key is required')
      .max(60)
      .regex(/^[a-zA-Z][a-zA-Z0-9_]*$/, 'Letters, digits and _ only; start with a letter'),
    label: z.string().trim().min(1, 'Label is required').max(120),
    type: z.enum(['text', 'textarea', 'select', 'number', 'date', 'checkbox', 'email', 'multiselect', 'file', 'user']),
    required: z.boolean(),
    options: z.string(),
    placeholder: z.string().max(200),
  })
  .refine((f) => (f.type !== 'select' && f.type !== 'multiselect') || f.options.split(',').some((o) => o.trim()), {
    path: ['options'],
    message: 'Select fields need at least one option',
  });

export const catalogItemSchema = z
  .object({
    categoryId: requiredId('Category'),
    name: z.string().trim().min(3, 'Name is required').max(150),
    description: optionalText(2000),
    requiresApproval: z.boolean(),
    approverRole: enumOrBlank(asTuple(APPROVER_ROLES)),
    fulfillmentHours: z.number({ invalid_type_error: 'Enter a number of hours' }).int().min(1, 'At least 1 hour').max(2000),
    fulfillmentDepartmentId: requiredId('Fulfilment department'),
    active: z.boolean(),
    formFields: z.array(formFieldSchema),
  })
  .refine((v) => !v.requiresApproval || v.approverRole !== '', { path: ['approverRole'], message: 'Choose who approves' })
  .refine((v) => new Set(v.formFields.map((f) => f.key)).size === v.formFields.length, {
    path: ['formFields'],
    message: 'Form field keys must be unique',
  });
export type CatalogItemValues = z.infer<typeof catalogItemSchema>;

/**
 * Builds the zod schema for a catalogue item's request form at runtime from its
 * `formFields` definition.
 */
export function buildCatalogFormSchema(fields: FormField[]): z.ZodObject<Record<string, z.ZodTypeAny>> {
  const shape: Record<string, z.ZodTypeAny> = {};
  fields.forEach((field) => {
    const label = field.label || field.key;
    switch (field.type) {
      case 'text':
      case 'textarea': {
        const max = field.type === 'text' ? 500 : 5000;
        const base = z.string().trim().max(max, `${label} must be at most ${max} characters`);
        shape[field.key] = field.required ? base.min(1, `${label} is required`) : base;
        break;
      }
      case 'select': {
        const options = field.options ?? [];
        shape[field.key] = z
          .string()
          .refine((v) => (field.required ? v !== '' : true), `${label} is required`)
          .refine((v) => v === '' || options.includes(v), `Choose a valid option for ${label}`);
        break;
      }
      case 'number': {
        const toNumber = (v: unknown) => {
          if (v === '' || v === null || v === undefined) return undefined;
          if (typeof v === 'number') return Number.isNaN(v) ? undefined : v;
          const parsed = Number(v);
          return Number.isNaN(parsed) ? v : parsed;
        };
        const num = z.number({ required_error: `${label} is required`, invalid_type_error: `${label} must be a number` });
        shape[field.key] = z.preprocess(toNumber, field.required ? num : num.optional());
        break;
      }
      case 'date': {
        const iso = /^\d{4}-\d{2}-\d{2}$/;
        shape[field.key] = z
          .string()
          .refine((v) => (field.required ? v !== '' : true), `${label} is required`)
          .refine((v) => v === '' || iso.test(v), `${label} must be a valid date`);
        break;
      }
      case 'checkbox': {
        shape[field.key] = field.required
          ? z.boolean().refine((v) => v === true, `${label} must be confirmed`)
          : z.boolean();
        break;
      }
      default:
        shape[field.key] = z.unknown();
    }
  });
  return z.object(shape);
}

export const serviceRequestBaseSchema = z.object({
  title: z.string().trim().min(5, 'Title must be at least 5 characters').max(200),
  details: z.string().max(5000),
});

// ---------- Knowledge ----------
export const articleSchema = z.object({
  title: z.string().trim().min(5, 'Title must be at least 5 characters').max(200),
  summary: z.string().trim().min(10, 'Summary must be at least 10 characters').max(500),
  content: z.string().trim().min(20, 'Content must be at least 20 characters').max(50_000),
  articleType: z.enum(asTuple(ARTICLE_TYPES)),
  category: z.enum(asTuple(CATEGORIES)),
  serviceId: idString,
  tags: optionalText(500),
  status: z.enum(asTuple(ARTICLE_STATUSES)),
});
export type ArticleValues = z.infer<typeof articleSchema>;

// ---------- Announcements ----------
export const announcementSchema = z
  .object({
    title: z.string().trim().min(3, 'Title is required').max(200),
    body: z.string().trim().min(5, 'Message is required').max(5000),
    severity: z.enum(asTuple(ANNOUNCEMENT_SEVERITIES)),
    serviceId: idString,
    startsAt: z.string(),
    endsAt: z.string(),
    published: z.boolean(),
  })
  .refine((v) => !v.startsAt || !v.endsAt || new Date(v.endsAt) > new Date(v.startsAt), {
    path: ['endsAt'],
    message: 'End must be after start',
  });
export type AnnouncementValues = z.infer<typeof announcementSchema>;

// ---------- Workflows ----------
export const conditionRuleSchema = z.object({
  field: z.string().min(1, 'Choose a field'),
  operator: z.string().min(1, 'Choose an operator'),
  value: z.string().trim().min(1, 'Enter a value'),
});

export const workflowActionSchema = z.object({
  type: z.string().min(1, 'Choose an action'),
  params: z.record(z.string()),
});

export const workflowSchema = z.object({
  name: z.string().trim().min(3, 'Name must be at least 3 characters').max(120),
  description: optionalText(1000),
  triggerEvent: z.enum(asTuple(WORKFLOW_TRIGGERS)),
  active: z.boolean(),
  maxExecutionsPerHour: z.number({ invalid_type_error: 'Enter a number' }).int().min(1).max(10_000),
  maxRetries: z.number({ invalid_type_error: 'Enter a number' }).int().min(0).max(10),
  retryBackoffSeconds: z.number({ invalid_type_error: 'Enter a number' }).int().min(0).max(86_400),
  match: z.enum(['ALL', 'ANY']),
  rules: z.array(conditionRuleSchema),
  actions: z.array(workflowActionSchema).min(1, 'Add at least one action'),
  changeNote: optionalText(500),
});
export type WorkflowValues = z.infer<typeof workflowSchema>;

// ---------- Runbooks ----------
export const runbookSchema = z.object({
  title: z.string().trim().min(3, 'Title must be at least 3 characters').max(200),
  description: optionalText(2000),
  serviceId: idString,
  categories: z.array(z.enum(asTuple(CATEGORIES))),
  /** One prerequisite per line. */
  prerequisites: optionalText(4000),
  steps: z
    .array(
      z.object({
        title: z.string().trim().min(2, 'Step title is required').max(200),
        instruction: z.string().trim().min(2, 'Instruction is required').max(4000),
        requiresApproval: z.boolean(),
      }),
    )
    .min(1, 'Add at least one step'),
  requiresApproval: z.boolean(),
  estimatedMinutes: z.number({ invalid_type_error: 'Enter minutes' }).int().min(1).max(10_000).nullable(),
  active: z.boolean(),
});
export type RunbookValues = z.infer<typeof runbookSchema>;

// ---------- Integrations ----------
export const integrationSchema = z
  .object({
    name: z.string().trim().min(3, 'Name is required').max(120),
    type: z.enum(asTuple(INTEGRATION_TYPES)),
    enabled: z.boolean(),
    endpointUrl: z.string().trim().max(1000),
    secret: z.string().max(500),
    subscribedEvents: z.array(z.string()),
    defaultServiceId: idString,
  })
  .refine((v) => v.type === 'MONITORING_INBOUND' || /^https:\/\/|^http:\/\/localhost/.test(v.endpointUrl), {
    path: ['endpointUrl'],
    message: 'Enter an https:// endpoint URL',
  });
export type IntegrationValues = z.infer<typeof integrationSchema>;

// ---------- SLA & configuration ----------
export const slaPolicySchema = z
  .object({
    name: z.string().trim().min(3).max(120),
    priority: z.enum(asTuple(PRIORITIES)),
    responseMinutes: z.number({ invalid_type_error: 'Enter minutes' }).int().min(1).max(100_000),
    resolutionMinutes: z.number({ invalid_type_error: 'Enter minutes' }).int().min(1).max(500_000),
    businessHoursOnly: z.boolean(),
    calendarId: idString,
    atRiskPercent: z.number({ invalid_type_error: 'Enter a percentage' }).int().min(1).max(99),
    active: z.boolean(),
    description: optionalText(500),
  })
  .refine((v) => v.resolutionMinutes >= v.responseMinutes, {
    path: ['resolutionMinutes'],
    message: 'Resolution target must be at least the response target',
  });
export type SlaPolicyValues = z.infer<typeof slaPolicySchema>;

const timeOfDay = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, 'Use HH:MM');
export const calendarSchema = z
  .object({
    name: z.string().trim().min(2).max(120),
    timezone: z.string().trim().min(2, 'Timezone is required').max(60),
    workDays: z.array(z.enum(WORK_DAYS)).min(1, 'Choose at least one work day'),
    startTime: timeOfDay,
    endTime: timeOfDay,
  })
  .refine((v) => v.endTime > v.startTime, { path: ['endTime'], message: 'End time must be after start time' });
export type CalendarValues = z.infer<typeof calendarSchema>;

export const holidaySchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a date'),
  name: z.string().trim().min(2, 'Name is required').max(120),
});
export type HolidayValues = z.infer<typeof holidaySchema>;

export const simulateElapsedSchema = z.object({
  incidentNumber: z
    .string()
    .trim()
    .regex(/^INC-\d+$/i, 'Use an incident number such as INC-7001'),
  minutes: z.number({ invalid_type_error: 'Enter minutes' }).int().min(1, 'At least 1 minute').max(100_000),
});
export type SimulateElapsedValues = z.infer<typeof simulateElapsedSchema>;
