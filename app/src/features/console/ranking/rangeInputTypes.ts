/** The slider's props, shared by the web slider and its native stand-in. */
export type RangeInputProps = {
  value: number;
  min: number;
  max: number;
  step: number;
  label: string;
  /** The track's colour (the theme's accent). */
  accent: string;
  disabled?: boolean;
  onChange: (value: number) => void;
  testID?: string;
};
