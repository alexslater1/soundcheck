import { currentPassword, newPassword, notACredential, signInEmail, signUpEmail } from './autofill';

describe('auth autofill hints', () => {
  it('marks the email fields as the credential username, never as a plain email', () => {
    // A password manager pairs the password with the field hinted "username"; with only "email"
    // Android guessed another field (the handle) and later filled it into Email.
    expect(signInEmail.autoComplete).toBe('username');
    expect(['username', 'username-new']).toContain(signUpEmail.autoComplete);
    expect(signInEmail.textContentType).toBe('username');
    expect(signUpEmail.keyboardType).toBe('email-address');
  });

  it('marks the passwords as current and new', () => {
    expect(currentPassword.secureTextEntry).toBe(true);
    expect(['password', 'current-password']).toContain(currentPassword.autoComplete);
    expect(['password-new', 'new-password']).toContain(newPassword.autoComplete);
  });

  it('keeps handle and display name out of autofill', () => {
    expect(notACredential.importantForAutofill).toBe('no');
    expect(notACredential.autoComplete).toBe('off');
  });
});
