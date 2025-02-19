import { codeToField } from './AuthForm';

describe('codeToField', () => {
  it('maps stable API codes to the field they belong to', () => {
    expect(codeToField('EMAIL_TAKEN')).toBe('email');
    expect(codeToField('INVALID_CREDENTIALS')).toBe('email');
    expect(codeToField('HANDLE_RESERVED')).toBe('handle');
    expect(codeToField('PASSWORD_TOO_COMMON')).toBe('password');
    expect(codeToField('RATE_LIMITED')).toBeNull();
  });
});
