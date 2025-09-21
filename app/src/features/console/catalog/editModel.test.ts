import type { GigEdit } from '@/api/generated/model';

import { editSummary, fieldLabel, undoChanges, valueText } from './editModel';

function edit(overrides: Partial<GigEdit>): GigEdit {
  return {
    id: 'e1',
    gigId: 'g1',
    userId: 'user-1',
    field: 'TITLE',
    oldValue: 'Keo',
    newValue: 'Keo live',
    createdAt: '2026-10-04T10:00:00Z',
    undoable: true,
    ...overrides,
  };
}

describe('editModel', () => {
  it('shows times in UK time and ids by name', () => {
    const time = edit({ field: 'STARTS_AT', oldValue: '2026-10-02T19:00:00Z' });
    expect(valueText(time, time.oldValue, null)).toMatch(/2 Oct.*20:00/); // 19:00Z is 20:00 BST
    const act = edit({ field: 'HEADLINER', oldValue: 'a1b2c3d4-0000', oldLabel: 'Keo' });
    expect(valueText(act, act.oldValue, act.oldLabel)).toBe('Keo');
    expect(valueText(act, act.oldValue, null)).toBe('a1b2c3d4');
    expect(valueText(act, null, null)).toBe('—');
    expect(fieldLabel('DATE')).toBe('Date');
  });

  it('sums an edit up in one line', () => {
    expect(editSummary(edit({}))).toBe('Title: Keo → Keo live');
    expect(
      editSummary(edit({ field: 'ACT_ADDED', oldValue: null, newValue: 'x', newLabel: 'Opener' })),
    ).toBe('Added Opener');
    expect(
      editSummary(edit({ field: 'ACT_ADDED', oldValue: 'x', oldLabel: 'Opener', newValue: null })),
    ).toBe('Removed Opener');
  });

  it('says what an undo puts back', () => {
    expect(undoChanges(edit({}))[0]).toBe('Set the title back to Keo');
    expect(
      undoChanges(
        edit({ field: 'ACT_ADDED', oldValue: null, newValue: 'x', newLabel: 'Opener' }),
      )[0],
    ).toBe('Take Opener off the bill (refused if a clip is of this act)');
    expect(
      undoChanges(
        edit({ field: 'ACT_ADDED', oldValue: 'x', oldLabel: 'Opener', newValue: null }),
      )[0],
    ).toBe('Put Opener back on the bill');
  });
});
