/**
 * Client-side mirror of the backend `PasswordPolicy` (com.nexus360.security.PasswordPolicy):
 * 10–128 characters, at least one letter and one digit, and — when the e-mail name has four or more
 * characters — not containing the e-mail name (case-insensitive). The API remains the authority;
 * this only gives live feedback.
 */
export const PASSWORD_MIN_LENGTH = 10;
export const PASSWORD_MAX_LENGTH = 128;
const EMAIL_NAME_MIN_LENGTH = 4;

const LETTER = /\p{L}/u;
const DIGIT = /\p{Nd}/u;

export type PasswordRuleId = 'length' | 'letter' | 'digit' | 'emailName';

export interface PasswordRuleResult {
  id: PasswordRuleId;
  label: string;
  met: boolean;
}

/** The part of the e-mail before "@", lower-cased; null when the policy does not apply to it. */
export function emailName(email: string | null | undefined): string | null {
  if (!email || !email.includes('@')) return null;
  const local = email.slice(0, email.indexOf('@')).toLowerCase();
  return local.length >= EMAIL_NAME_MIN_LENGTH ? local : null;
}

/** Every rule with whether `password` satisfies it (for the live checklist). */
export function passwordRules(password: string, email?: string | null): PasswordRuleResult[] {
  const length = password.length;
  const rules: PasswordRuleResult[] = [
    { id: 'length', label: `At least ${PASSWORD_MIN_LENGTH} characters`, met: length >= PASSWORD_MIN_LENGTH && length <= PASSWORD_MAX_LENGTH },
    { id: 'letter', label: 'At least one letter', met: LETTER.test(password) },
    { id: 'digit', label: 'At least one digit', met: DIGIT.test(password) },
  ];
  const name = emailName(email);
  if (name) {
    rules.push({ id: 'emailName', label: `Does not contain your e-mail name (${name})`, met: length > 0 && !password.toLowerCase().includes(name) });
  }
  return rules;
}

/** First violated rule as a sentence (same order and wording as the API), or null when the password is acceptable. */
export function passwordPolicyError(password: string, email?: string | null): string | null {
  if (password.length < PASSWORD_MIN_LENGTH) return `Password must be at least ${PASSWORD_MIN_LENGTH} characters long`;
  if (password.length > PASSWORD_MAX_LENGTH) return `Password must be at most ${PASSWORD_MAX_LENGTH} characters long`;
  if (!LETTER.test(password) || !DIGIT.test(password)) return 'Password must contain at least one letter and one digit';
  const name = emailName(email);
  if (name && password.toLowerCase().includes(name)) return 'Password must not contain your e-mail name';
  return null;
}
