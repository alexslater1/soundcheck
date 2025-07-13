// A PNG imported from app/assets (Metro turns it into an asset: a number on Android, a URI on the web).
declare module '*.png' {
  import type { ImageSourcePropType } from 'react-native';

  const source: ImageSourcePropType;
  export default source;
}
