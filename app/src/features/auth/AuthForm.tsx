import { zodResolver } from '@hookform/resolvers/zod';
import {
  Controller,
  useForm,
  type DefaultValues,
  type FieldValues,
  type Path,
} from 'react-hook-form';
import { useState } from 'react';
import { Pressable, StyleSheet, TextInput, View, type TextInputProps } from 'react-native';
import type { z } from 'zod';

import { ApiError } from '@/api/http';
import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';
import { radius, spacing, touchTarget } from '@/theme/tokens';

export type FieldSpec<T extends FieldValues> = {
  name: Path<T>;
  label: string;
  props?: TextInputProps;
};

type Props<T extends FieldValues> = {
  schema: z.ZodType<T, z.ZodTypeDef, T>;
  defaults: DefaultValues<T>;
  fields: FieldSpec<T>[];
  submitLabel: string;
  onSubmit: (values: T) => Promise<void>;
};

/** Generic auth form: react-hook-form + zod, with server Problem Details mapped onto fields. */
export function AuthForm<T extends FieldValues>({
  schema,
  defaults,
  fields,
  submitLabel,
  onSubmit,
}: Props<T>) {
  const { colors } = useTheme();
  const [serverError, setServerError] = useState<string | null>(null);
  const { control, handleSubmit, setError, formState } = useForm<T>({
    resolver: zodResolver(schema),
    defaultValues: defaults,
  });

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await onSubmit(values);
    } catch (error) {
      if (error instanceof ApiError) {
        const fieldErrors = error.problem?.errors ?? [];
        for (const fe of fieldErrors) {
          setError(fe.field as Path<T>, { message: fe.message });
        }
        const byCode = codeToField(error.code);
        if (byCode) {
          setError(byCode as Path<T>, { message: error.message });
        } else if (fieldErrors.length === 0) {
          setServerError(error.message);
        }
      } else {
        setServerError('Something went wrong');
      }
    }
  });

  return (
    <View style={styles.form}>
      {fields.map((field) => (
        <Controller
          key={field.name}
          control={control}
          name={field.name}
          render={({ field: { value, onChange, onBlur }, fieldState }) => (
            <View style={styles.field}>
              <Text variant="label" muted>
                {field.label}
              </Text>
              <TextInput
                accessibilityLabel={field.label}
                value={value as string}
                onChangeText={onChange}
                onBlur={onBlur}
                placeholderTextColor={colors.textMuted}
                style={[
                  styles.input,
                  {
                    color: colors.text,
                    backgroundColor: colors.surface,
                    borderColor: fieldState.error ? colors.danger : colors.border,
                  },
                ]}
                {...field.props}
              />
              {fieldState.error ? (
                <Text variant="caption" style={{ color: colors.danger }}>
                  {fieldState.error.message}
                </Text>
              ) : null}
            </View>
          )}
        />
      ))}
      {serverError ? (
        <Text variant="caption" style={{ color: colors.danger }} testID="server-error">
          {serverError}
        </Text>
      ) : null}
      <Pressable
        accessibilityRole="button"
        onPress={submit}
        disabled={formState.isSubmitting}
        style={[
          styles.button,
          { backgroundColor: colors.accent, opacity: formState.isSubmitting ? 0.6 : 1 },
        ]}
      >
        <Text style={styles.buttonText}>{formState.isSubmitting ? '…' : submitLabel}</Text>
      </Pressable>
    </View>
  );
}

/** Stable API codes that belong to one field. */
function codeToField(code: string): string | null {
  switch (code) {
    case 'EMAIL_TAKEN':
    case 'INVALID_CREDENTIALS':
      return 'email';
    case 'HANDLE_TAKEN':
    case 'HANDLE_RESERVED':
    case 'HANDLE_INVALID':
      return 'handle';
    case 'PASSWORD_TOO_SHORT':
    case 'PASSWORD_TOO_LONG':
    case 'PASSWORD_TOO_COMMON':
      return 'password';
    default:
      return null;
  }
}

const styles = StyleSheet.create({
  form: { gap: spacing.md },
  field: { gap: spacing.xs },
  input: {
    minHeight: touchTarget,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    fontSize: 16,
  },
  button: {
    minHeight: touchTarget,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: spacing.sm,
  },
  buttonText: { color: '#FFFFFF', fontWeight: '700' },
});
