import { describe, expect, it } from 'vitest';
import { emailName, passwordPolicyError, passwordRules } from './passwordPolicy';

describe('password policy (mirror of the backend PasswordPolicy)', () => {
  it('requires 10–128 characters with a letter and a digit', () => {
    expect(passwordPolicyError('Short1')).toBe('Password must be at least 10 characters long');
    expect(passwordPolicyError('a'.repeat(127) + '12')).toBe('Password must be at most 128 characters long');
    expect(passwordPolicyError('onlyletters')).toBe('Password must contain at least one letter and one digit');
    expect(passwordPolicyError('1234567890')).toBe('Password must contain at least one letter and one digit');
    expect(passwordPolicyError('Brighter2026')).toBeNull();
    // Character.isLetter / isDigit accept non-ASCII letters and digits too.
    expect(passwordPolicyError('Ünïcødé١٢٣٤')).toBeNull();
  });

  it('rejects passwords containing the e-mail name when it has at least four characters', () => {
    expect(emailName('Meera.Nair@nexus360.test')).toBe('meera.nair');
    expect(emailName('ab@x.test')).toBeNull();
    expect(passwordPolicyError('MEERA.NAIR-2026', 'meera.nair@nexus360.test')).toBe('Password must not contain your e-mail name');
    expect(passwordPolicyError('ab-1234567890', 'ab@x.test')).toBeNull();
  });

  it('reports each rule for the live checklist', () => {
    expect(passwordRules('', 'meera@x.test').map((rule) => [rule.id, rule.met])).toEqual([
      ['length', false],
      ['letter', false],
      ['digit', false],
      ['emailName', false],
    ]);
    expect(passwordRules('Brighter2026', 'meera@x.test').every((rule) => rule.met)).toBe(true);
    expect(passwordRules('Brighter2026').map((rule) => rule.id)).not.toContain('emailName');
  });
});
