import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react-native';

import type { GigEdit } from '@/api/generated/model';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { gig } from './catalogTestData';
import { EditHistoryTab } from './EditHistoryTab';

const mockUndo = jest.fn();
let mockEdits: { data?: GigEdit[]; status: 'pending' | 'error' | 'success' };
const mockEditCalls: { id: string; enabled: boolean }[] = [];
const mockPicked = gig('gig-1', 'Keo');

jest.mock('@/api/generated/admin-catalog/admin-catalog', () => ({
  getAdminListGigEditsQueryKey: (id: string) => [`/api/v1/admin/catalog/gigs/${id}/edits`],
  useAdminListGigEdits: (id: string, options: { query: { enabled: boolean } }) => {
    mockEditCalls.push({ id, enabled: options.query.enabled });
    return { ...mockEdits, refetch: jest.fn() };
  },
  useAdminUndoGigEdit: () => ({
    mutate: mockUndo,
    reset: jest.fn(),
    isPending: false,
    isError: false,
  }),
}));
jest.mock('@/api/generated/admin-users/admin-users', () => ({
  useAdminUsersByIds: () => ({ data: [{ id: 'user-1', handle: 'sam' }] }),
}));
jest.mock('./GigSearchPicker', () => {
  const { Pressable, Text } = jest.requireActual('react-native');
  return {
    GigSearchPicker: ({ onChange }: { onChange: (g: unknown) => void }) => (
      <Pressable testID="pick-gig" onPress={() => onChange(mockPicked)}>
        <Text>pick</Text>
      </Pressable>
    ),
  };
});

function edit(id: string, overrides: Partial<GigEdit> = {}): GigEdit {
  return {
    id,
    gigId: 'gig-1',
    userId: 'user-1',
    field: 'TITLE',
    oldValue: 'Keo',
    newValue: 'Keo (live)',
    createdAt: '2026-10-04T10:00:00Z',
    undoable: true,
    ...overrides,
  };
}

async function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  await render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <EditHistoryTab />
      </ThemeProvider>
    </QueryClientProvider>,
  );
}

describe('EditHistoryTab', () => {
  beforeEach(() => {
    mockUndo.mockReset();
    mockEditCalls.length = 0;
    mockEdits = { status: 'success', data: [] };
  });

  it('asks for a gig first', async () => {
    await renderTab();
    expect(screen.getByTestId('edits-empty')).toBeOnTheScreen();
    expect(mockEditCalls.every((c) => !c.enabled)).toBe(true);
  });

  it('lists the edits with who made them, and what undid what', async () => {
    mockEdits = {
      status: 'success',
      data: [
        edit('e2', { oldValue: 'Keo (live)', newValue: 'Keo', undoesEditId: 'e1aaaaaaaa' }),
        edit('e1', { undoneByEditId: 'e2bbbbbbbb' }),
        edit('e0', { field: 'MERGE', oldValue: 'gone-gig-id', newValue: 'gig-1', undoable: false }),
      ],
    };
    await renderTab();
    await fireEvent.press(screen.getByTestId('pick-gig'));
    expect(mockEditCalls.at(-1)).toEqual({ id: 'gig-1', enabled: true });
    expect(screen.getByText('Title: Keo → Keo (live)')).toBeOnTheScreen();
    expect(screen.getByText('Undo of edit e1aaaaaa')).toBeOnTheScreen();
    expect(screen.getByText('Undone by edit e2bbbbbb')).toBeOnTheScreen();
    expect(screen.getAllByText('@sam')).toHaveLength(3);
    expect(screen.getByText("Can't be undone")).toBeOnTheScreen();
  });

  it('undoes an edit only after confirming', async () => {
    mockEdits = { status: 'success', data: [edit('e1')] };
    await renderTab();
    await fireEvent.press(screen.getByTestId('pick-gig'));
    await fireEvent.press(screen.getByTestId('edit-undo-e1'));
    expect(screen.getByText('• Set the title back to Keo')).toBeOnTheScreen();
    expect(mockUndo).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByTestId('confirm-ok'));
    expect(mockUndo).toHaveBeenCalledWith({ id: 'e1' });
  });

  it('says when a gig has no edits', async () => {
    await renderTab();
    await fireEvent.press(screen.getByTestId('pick-gig'));
    expect(screen.getByText('No edits')).toBeOnTheScreen();
  });
});
