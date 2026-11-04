import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { KeyRound } from 'lucide-react';
import { useChangePassword } from '../../api/auth';
import { applyFieldErrors, errorMessage } from '../../lib/api';
import { changePasswordSchema, type ChangePasswordValues } from '../../lib/schemas';
import { TextField } from '../FormField';

export function ChangePasswordForm({ onChanged, submitLabel = 'Update password' }: { onChanged: () => void; submitLabel?: string }) {
  const change = useChangePassword();
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<ChangePasswordValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  });

  const onSubmit = handleSubmit((values) =>
    change.mutate(
      { currentPassword: values.currentPassword, newPassword: values.newPassword },
      {
        onSuccess: () => {
          reset();
          onChanged();
        },
        onError: (error) => applyFieldErrors(error, setError),
      },
    ),
  );

  return (
    <form onSubmit={onSubmit} noValidate>
      <TextField
        label="Current password"
        type="password"
        autoComplete="current-password"
        required
        error={errors.currentPassword?.message}
        {...register('currentPassword')}
      />
      <TextField
        label="New password"
        type="password"
        autoComplete="new-password"
        required
        hint="At least 10 characters, including letters and digits."
        error={errors.newPassword?.message}
        {...register('newPassword')}
      />
      <TextField
        label="Confirm new password"
        type="password"
        autoComplete="new-password"
        required
        error={errors.confirmPassword?.message}
        {...register('confirmPassword')}
      />
      {change.isError && !Object.keys(errors).length && (
        <p className="field-error" role="alert">
          {errorMessage(change.error)}
        </p>
      )}
      <button type="submit" className="gold-btn full" disabled={change.isPending}>
        <KeyRound size={15} /> {change.isPending ? 'Updating…' : submitLabel}
      </button>
    </form>
  );
}
