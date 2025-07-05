import * as SecureStore from 'expo-secure-store';

import { refreshTokenStore } from './secureStore';

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

const getItem = SecureStore.getItemAsync as jest.MockedFunction<typeof SecureStore.getItemAsync>;

describe('refreshTokenStore.get', () => {
  it('answers null when nothing is stored', async () => {
    getItem.mockResolvedValue(null);
    await expect(refreshTokenStore.get()).resolves.toBeNull();
  });

  it('rejects when the store cannot be read, which is not the same as signed out', async () => {
    getItem.mockRejectedValue(new Error('Could not decrypt'));
    await expect(refreshTokenStore.get()).rejects.toThrow('Could not decrypt');
  });
});
