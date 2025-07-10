import { Image } from 'expo-image';
import { StyleSheet, View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { radius } from '@/theme/tokens';

import { Text } from './Text';

type Props = {
  uri?: string | null;
  /** The person's name: its first letter stands in when there is no picture. */
  name: string;
  size?: number;
};

/** A person's round picture, or the first letter of their name on the accent colour. */
export function Avatar({ uri, name, size = 32 }: Props) {
  const { colors } = useTheme();
  return (
    <View style={[styles.avatar, { width: size, height: size, backgroundColor: colors.accent }]}>
      {uri ? (
        <Image source={{ uri }} style={StyleSheet.absoluteFill} contentFit="cover" />
      ) : (
        <Text
          style={{
            color: colors.onAccent,
            fontSize: size * 0.45,
            lineHeight: size * 0.6,
            fontWeight: '700',
          }}
        >
          {name.slice(0, 1).toUpperCase()}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  avatar: {
    borderRadius: radius.pill,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
});
