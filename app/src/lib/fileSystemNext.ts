import type * as FileSystemNext from 'expo-file-system/build/next';
import type { FileHandle as NextFileHandle } from 'expo-file-system/build/next/ExpoFileSystem.types';

/**
 * expo-file-system's object API (`File`, `Directory`, `Paths`), which SDK 52 ships as
 * `expo-file-system/next`. That entry point is the package's TypeScript source, not a build, and it
 * does not compile under this app's stricter settings (`noUncheckedIndexedAccess`,
 * `noImplicitOverride`), so it is required at run time and typed from the package's own declarations
 * (`build/next`). Tests mock 'expo-file-system/next' as usual.
 */
const fileSystemNext = require('expo-file-system/next') as typeof FileSystemNext;

// Each class is exported as a value and as its instance type, as the class declaration would be.
export const File = fileSystemNext.File;
// eslint-disable-next-line @typescript-eslint/no-redeclare
export type File = FileSystemNext.File;
export const Directory = fileSystemNext.Directory;
// eslint-disable-next-line @typescript-eslint/no-redeclare
export type Directory = FileSystemNext.Directory;
export const Paths = fileSystemNext.Paths;
// SDK 52's `next` entry exports the file handle's type only from its types module.
export type FileHandle = NextFileHandle;
