import { Text } from '@/components/ui/Text';
import { useTheme } from '@/theme/ThemeProvider';

/** The inline error line under an onboarding step's content; renders nothing without a message. */
export function StepError({ message }: { message: string | null }) {
  const { colors } = useTheme();
  if (!message) return null;
  return (
    <Text accessibilityRole="alert" testID="step-error" style={{ color: colors.danger }}>
      {message}
    </Text>
  );
}
