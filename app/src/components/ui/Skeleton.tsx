import { View, type DimensionValue } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius } from '@/theme/tokens';

/** A grey block standing in for content that is still loading. */
export function Skeleton({ height, width = '100%' }: { height: number; width?: DimensionValue }) {
  const { colors } = useTheme();
  return (
    <View
      accessibilityLabel="Loading"
      style={{ height, width, borderRadius: radius.md, backgroundColor: colors.surfaceRaised }}
    />
  );
}
