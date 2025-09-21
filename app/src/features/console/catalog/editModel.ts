import type { GigEdit } from '@/api/generated/model';
import { formatGigTime } from '@/features/gigs/format';

/** What each field is called on the gig page. */
export function fieldLabel(field: string): string {
  switch (field) {
    case 'ACT_ADDED':
      return 'Act';
    case 'STARTS_AT':
      return 'Start time';
    case 'DATE':
      return 'Date';
    case 'VENUE':
      return 'Venue';
    case 'HEADLINER':
      return 'Headliner';
    case 'TITLE':
      return 'Title';
    case 'MERGE':
      return 'Merge';
    default:
      return field;
  }
}

/** One side of an edit in words: a time in UK time, a name for an id, the text itself otherwise. */
export function valueText(
  edit: GigEdit,
  value: string | null | undefined,
  label: string | null | undefined,
): string {
  if (value == null) return '—';
  switch (edit.field) {
    case 'STARTS_AT':
    case 'DATE':
      return formatGigTime(value, 'Europe/London');
    case 'ACT_ADDED':
    case 'VENUE':
    case 'HEADLINER':
      return label ?? value.slice(0, 8);
    case 'MERGE':
      return value.slice(0, 8);
    default:
      return value;
  }
}

/** "Added Keo", "Removed Keo", "Start time: Fri 2 Oct, 20:00 → Fri 2 Oct, 21:30". */
export function editSummary(edit: GigEdit): string {
  if (edit.field === 'ACT_ADDED') {
    return edit.oldValue == null
      ? `Added ${valueText(edit, edit.newValue, edit.newLabel)}`
      : `Removed ${valueText(edit, edit.oldValue, edit.oldLabel)}`;
  }
  if (edit.field === 'MERGE') {
    return `Merged gig ${valueText(edit, edit.oldValue, null)} into this one`;
  }
  return `${fieldLabel(edit.field)}: ${valueText(edit, edit.oldValue, edit.oldLabel)} → ${valueText(
    edit,
    edit.newValue,
    edit.newLabel,
  )}`;
}

/** The confirm dialog's lines for an undo: what goes back, and that it is logged. */
export function undoChanges(edit: GigEdit): string[] {
  const back =
    edit.field === 'ACT_ADDED'
      ? edit.oldValue == null
        ? `Take ${valueText(edit, edit.newValue, edit.newLabel)} off the bill (refused if a clip is of this act)`
        : `Put ${valueText(edit, edit.oldValue, edit.oldLabel)} back on the bill`
      : `Set the ${fieldLabel(edit.field).toLowerCase()} back to ${valueText(edit, edit.oldValue, edit.oldLabel)}`;
  return [back, 'Log the undo in this history, with you as its author'];
}
