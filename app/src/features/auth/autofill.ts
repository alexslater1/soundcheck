import { Platform, type TextInputProps } from 'react-native';

/**
 * Autofill hints for the auth forms. Password managers store a credential as a (username, password)
 * pair, and the username is whichever field carries the "username" hint, so the email field must carry
 * it, not just "email". Without it Google's autofill guessed: it saved the sign-up form's handle
 * ("alex") as the username and later filled that into Email. Fields that are not part of the
 * credential are excluded from autofill altogether.
 */
const emailBase: TextInputProps = {
  autoCapitalize: 'none',
  autoCorrect: false,
  keyboardType: 'email-address',
  textContentType: 'username',
};

/** Email on the sign-in form: the saved username. */
export const signInEmail: TextInputProps = { ...emailBase, autoComplete: 'username' };

/**
 * Email on the sign-up form: a new username (Android offers to save it with the new password). The
 * browser has no `username-new` token; plain `username` is what it expects next to `new-password`.
 */
export const signUpEmail: TextInputProps = {
  ...emailBase,
  autoComplete: Platform.OS === 'android' ? 'username-new' : 'username',
};

export const currentPassword: TextInputProps = {
  secureTextEntry: true,
  autoComplete: Platform.OS === 'web' ? 'current-password' : 'password',
  textContentType: 'password',
};

export const newPassword: TextInputProps = {
  secureTextEntry: true,
  autoComplete: Platform.OS === 'web' ? 'new-password' : 'password-new',
  textContentType: 'newPassword',
};

/** Handle and display name: never offered for autofill, never mistaken for the username. */
export const notACredential: TextInputProps = {
  autoComplete: 'off',
  importantForAutofill: 'no',
  textContentType: 'none',
};
