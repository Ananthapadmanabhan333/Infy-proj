import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useForm, type Resolver } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ArrowLeft, Clock3, Lock, Send, ShieldCheck } from 'lucide-react';
import { useCatalogItemForm } from '../../api/forms';
import { useCreateServiceRequest } from '../../api/serviceDesk';
import { SelectField, TextareaField, TextField } from '../FormField';
import { DynamicFormFields } from '../forms/DynamicFormFields';
import { useToast } from '../Toast';
import { useAuth } from '../../hooks/useAuth';
import { errorMessage, toApiError } from '../../lib/api';
import { EMPLOYEE_REQUEST_PRIORITIES, PRIORITIES, PRIORITY_LABELS, STAFF, roleLabel } from '../../lib/constants';
import { initialValues, serverFieldErrors, toFormData, validateDynamic, type DynamicValue, type DynamicValues } from '../../lib/dynamicForm';
import { serviceRequestBaseSchema } from '../../lib/schemas';
import type { CatalogItemDto, FormField, Priority, ServiceRequestDto } from '../../lib/types';

interface RequestFormValues {
  title: string;
  details: string;
  priority: Priority;
}

interface RequestFormProps {
  item: CatalogItemDto;
  onCreated: (request: ServiceRequestDto) => void;
  onCancel: () => void;
  /** Shows "Choose another item" (New Request picker). */
  onBack?: () => void;
}

/**
 * Dynamic request form for a catalogue item: title, the item's form definition (current version from
 * `GET /catalog/items/{id}/form`, conditional fields, multi-select, user picker, file uploads), priority and details.
 * Validated client side and again by the API, whose field errors are mapped back onto the fields.
 * Employees may choose Low–High; staff any priority (§4).
 */
export function RequestForm({ item, onCreated, onCancel, onBack }: RequestFormProps) {
  const { hasRole } = useAuth();
  const staff = hasRole(...STAFF);
  const priorities = staff ? PRIORITIES : EMPLOYEE_REQUEST_PRIORITIES;
  const create = useCreateServiceRequest();
  const itemForm = useCatalogItemForm(item.id);
  const toast = useToast();
  // The item mirrors its form's current version; the form endpoint is authoritative once loaded.
  const fields: FormField[] = useMemo(() => (itemForm.data?.fields as FormField[] | undefined) ?? item.formFields ?? [], [itemForm.data, item.formFields]);
  const canSubmit = itemForm.data ? itemForm.data.canSubmit : item.canSubmit !== false;
  const [values, setValues] = useState<DynamicValues>(() => initialValues(fields));
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitted, setSubmitted] = useState(false);

  useEffect(() => {
    // A newer definition arrived: keep what was typed for keys that still exist.
    setValues((current) => ({ ...initialValues(fields), ...Object.fromEntries(Object.entries(current).filter(([key]) => fields.some((field) => field.key === key))) }));
  }, [fields]);

  const schema = useMemo(
    () => serviceRequestBaseSchema.extend({ priority: z.enum(priorities as [Priority, ...Priority[]], { errorMap: () => ({ message: 'Choose a priority' }) }) }),
    [priorities],
  );
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RequestFormValues>({
    resolver: zodResolver(schema) as unknown as Resolver<RequestFormValues>,
    defaultValues: { title: item.name, details: '', priority: 'MEDIUM' },
  });

  const change = (key: string, value: DynamicValue) => {
    setValues((current) => {
      const next = { ...current, [key]: value };
      if (submitted) setFieldErrors(validateDynamic(fields, next));
      return next;
    });
  };

  const send = handleSubmit((form) => {
    const clientErrors = validateDynamic(fields, values);
    setFieldErrors(clientErrors);
    if (Object.keys(clientErrors).length) return;
    create.mutate(
      {
        catalogItemId: item.id,
        title: form.title.trim(),
        details: form.details.trim() || undefined,
        formData: toFormData(fields, values),
        priority: form.priority,
      },
      {
        onSuccess: (request) => {
          toast.success(`${request.number} submitted${request.status === 'PENDING_APPROVAL' ? ' — awaiting approval' : ''}`, {
            action: { label: `View ${request.number}`, to: `/service-desk?tab=mine&request=${request.id}` },
          });
          onCreated(request);
        },
        onError: (error) => {
          const apiError = toApiError(error);
          setFieldErrors(serverFieldErrors(fields, apiError.fieldErrors, apiError.message));
        },
      },
    );
  });

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    setSubmitted(true);
    // Validate the dynamic fields even when the base fields fail, so every problem shows at once.
    setFieldErrors(validateDynamic(fields, values));
    void send(event);
  };

  const mappedServerError = create.isError && Object.keys(fieldErrors).length > 0;
  const hasBaseErrors = Object.keys(errors).length > 0;

  if (!canSubmit) {
    return (
      <div className="callout" role="alert">
        <Lock size={16} aria-hidden="true" />
        <div>
          <b>You cannot submit this request type.</b> It is limited to certain roles or departments. Contact the service desk if you need it.
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate aria-label={`Request ${item.name}`}>
      <div className="mb-3 flex flex-wrap gap-2">
        <span className="chip">
          <Clock3 size={11} aria-hidden="true" /> Fulfilled in about {item.fulfillmentHours} h by {item.fulfillmentDepartmentName}
        </span>
        <span className="chip">
          <ShieldCheck size={11} aria-hidden="true" /> {item.requiresApproval ? `Requires ${roleLabel(item.approverRole ?? 'IT_MANAGER')} approval` : 'No approval needed'}
        </span>
        {itemForm.data?.formVersion ? <span className="chip">Form v{itemForm.data.formVersion}</span> : null}
      </div>
      <div className="form-row">
        <TextField label="Request title" required maxLength={200} error={errors.title?.message} {...register('title')} />
        <SelectField label="Priority" required error={errors.priority?.message} hint={staff ? undefined : 'Critical is reserved for the support team.'} {...register('priority')}>
          {priorities.map((priority) => (
            <option key={priority} value={priority}>
              {PRIORITY_LABELS[priority]}
            </option>
          ))}
        </SelectField>
      </div>
      <DynamicFormFields fields={fields} values={values} onChange={change} errors={fieldErrors} disabled={create.isPending} />
      <TextareaField label="Additional details" placeholder="Anything else the fulfilment team should know" maxLength={5000} error={errors.details?.message} {...register('details')} />
      {create.isError && !mappedServerError && !hasBaseErrors && (
        <p className="field-error" role="alert">
          {errorMessage(create.error)}
        </p>
      )}
      <div className="modal-foot">
        {onBack && (
          <button type="button" className="link-btn mr-auto text-[11px]" onClick={onBack} disabled={create.isPending}>
            <ArrowLeft size={13} aria-hidden="true" /> Choose another item
          </button>
        )}
        <button type="button" className="subtle-btn" onClick={onCancel} disabled={create.isPending}>
          Cancel
        </button>
        <button type="submit" className="gold-btn" disabled={create.isPending}>
          <Send size={14} aria-hidden="true" /> {create.isPending ? 'Submitting…' : 'Submit request'}
        </button>
      </div>
    </form>
  );
}
