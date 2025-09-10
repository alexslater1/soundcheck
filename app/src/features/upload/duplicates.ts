import { create } from 'zustand';

/** A clip the server refused because it is already in one of the user's posts. */
export type DuplicateNotice = {
  localId: string;
  ownerId: string;
  fileName: string | null;
  postId: string;
};

type DuplicateNoticesState = {
  notices: DuplicateNotice[];
  add: (notice: DuplicateNotice) => void;
  dismiss: (localId: string) => void;
};

/**
 * "You've posted this clip already" notices on the Create tab. Not persisted: the clip has left the list,
 * and the note only matters while the user is looking.
 */
export const useDuplicateNotices = create<DuplicateNoticesState>()((set) => ({
  notices: [],
  add: (notice) =>
    set((state) => ({
      notices: [notice, ...state.notices.filter((n) => n.localId !== notice.localId)],
    })),
  dismiss: (localId) =>
    set((state) => ({ notices: state.notices.filter((n) => n.localId !== localId) })),
}));
