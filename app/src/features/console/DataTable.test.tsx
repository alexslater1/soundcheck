import { fireEvent, render, screen, within } from '@testing-library/react-native';

import { Text } from '@/components/ui/Text';
import { ThemeProvider } from '@/theme/ThemeProvider';

import { DataTable, nextSort, sortRows, type Column } from './DataTable';

type Row = { id: string; name: string; posts: number | null; synthetic: boolean };

const rows: Row[] = [
  { id: '1', name: 'bea', posts: 3, synthetic: false },
  { id: '2', name: 'abe', posts: null, synthetic: true },
  { id: '3', name: 'cal', posts: 10, synthetic: false },
  { id: '4', name: 'dee', posts: 3, synthetic: true },
];

const columns: Column<Row>[] = [
  { key: 'name', title: 'Name', sortValue: (r) => r.name, render: (r) => <Text>{r.name}</Text> },
  {
    key: 'posts',
    title: 'Posts',
    sortValue: (r) => r.posts,
    render: (r) => <Text>{String(r.posts)}</Text>,
  },
  { key: 'synthetic', title: 'Kind', sortValue: (r) => r.synthetic, render: () => null },
  { key: 'links', title: 'Links', render: () => null },
];

const ids = (list: Row[]) => list.map((r) => r.id);

describe('sortRows', () => {
  it('keeps the server order without a sort', async () => {
    expect(ids(sortRows(rows, columns, null))).toEqual(['1', '2', '3', '4']);
  });

  it('sorts strings', async () => {
    expect(ids(sortRows(rows, columns, { key: 'name', direction: 'asc' }))).toEqual([
      '2',
      '1',
      '3',
      '4',
    ]);
    expect(ids(sortRows(rows, columns, { key: 'name', direction: 'desc' }))).toEqual([
      '4',
      '3',
      '1',
      '2',
    ]);
  });

  it('sorts numbers, keeps ties in server order and puts nulls last both ways', async () => {
    expect(ids(sortRows(rows, columns, { key: 'posts', direction: 'asc' }))).toEqual([
      '1',
      '4',
      '3',
      '2',
    ]);
    expect(ids(sortRows(rows, columns, { key: 'posts', direction: 'desc' }))).toEqual([
      '3',
      '1',
      '4',
      '2',
    ]);
  });

  it('sorts booleans', async () => {
    expect(ids(sortRows(rows, columns, { key: 'synthetic', direction: 'desc' }))).toEqual([
      '2',
      '4',
      '1',
      '3',
    ]);
  });

  it('ignores a column that cannot be sorted', async () => {
    expect(ids(sortRows(rows, columns, { key: 'links', direction: 'asc' }))).toEqual([
      '1',
      '2',
      '3',
      '4',
    ]);
  });
});

describe('nextSort', () => {
  it('cycles ascending, descending, off; a new column starts ascending', async () => {
    const asc = nextSort(null, 'name');
    expect(asc).toEqual({ key: 'name', direction: 'asc' });
    const desc = nextSort(asc, 'name');
    expect(desc).toEqual({ key: 'name', direction: 'desc' });
    expect(nextSort(desc, 'name')).toBeNull();
    expect(nextSort(desc, 'posts')).toEqual({ key: 'posts', direction: 'asc' });
  });
});

async function renderTable(props: Partial<Parameters<typeof DataTable<Row>>[0]> = {}) {
  return render(
    <ThemeProvider>
      <DataTable
        testID="t"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.id}
        status="success"
        emptyTitle="Nobody here"
        {...props}
      />
    </ThemeProvider>,
  );
}

describe('DataTable', () => {
  it('sorts when a header is pressed and marks the column', async () => {
    await renderTable();
    await fireEvent.press(screen.getByTestId('t-sort-name'));
    const names = screen
      .getAllByTestId('t-row')
      .map((r) => within(r).getAllByText(/^[a-z]{3}$/)[0]?.props.children);
    expect(names).toEqual(['abe', 'bea', 'cal', 'dee']);
    expect(screen.getByText('NAME ▲')).toBeOnTheScreen();
    expect(screen.queryByTestId('t-sort-links')).toBeNull();
  });

  it('shows skeletons while loading, not the empty state', async () => {
    await renderTable({ status: 'pending', rows: undefined });
    expect(screen.getByTestId('t-loading')).toBeOnTheScreen();
    expect(screen.queryByTestId('t-empty')).toBeNull();
  });

  it('shows the empty state', async () => {
    await renderTable({ rows: [] });
    expect(screen.getByText('Nobody here')).toBeOnTheScreen();
  });

  it('shows the error state with a retry', async () => {
    const onRetry = jest.fn();
    await renderTable({ status: 'error', onRetry });
    expect(screen.getByText("Couldn't load this table")).toBeOnTheScreen();
    await fireEvent.press(screen.getByText('Try again'));
    expect(onRetry).toHaveBeenCalled();
  });

  it('offers "Load more" only while the cursor says there is more', async () => {
    const onLoadMore = jest.fn();
    const { rerender } = await renderTable({ hasMore: true, onLoadMore });
    await fireEvent.press(screen.getByTestId('t-more'));
    expect(onLoadMore).toHaveBeenCalledTimes(1);
    await rerender(
      <ThemeProvider>
        <DataTable
          testID="t"
          columns={columns}
          rows={rows}
          rowKey={(r) => r.id}
          status="success"
          emptyTitle="x"
        />
      </ThemeProvider>,
    );
    expect(screen.queryByTestId('t-more')).toBeNull();
  });

  it('shows content under the rows that ask for it, and styles the rows it is told to', async () => {
    await renderTable({
      renderBelow: (r) => (r.synthetic ? <Text>{`details of ${r.name}`}</Text> : null),
      rowStyle: (r) => (r.name === 'cal' ? { backgroundColor: '#ff0000' } : undefined),
    });
    expect(screen.getAllByTestId('t-below')).toHaveLength(2);
    expect(screen.getByText('details of abe')).toBeOnTheScreen();
    const tinted = screen
      .getAllByTestId('t-row')
      .filter((r) => JSON.stringify(r.props.style).includes('#ff0000'));
    expect(tinted).toHaveLength(1);
  });
});
