import { toggled } from './likeState';

describe('toggled', () => {
  it('likes: the heart fills and the count goes up by one', () => {
    expect(toggled({ likedByMe: false, likeCount: 4 })).toEqual({ likedByMe: true, likeCount: 5 });
  });

  it('unlikes: back down by one, never below zero', () => {
    expect(toggled({ likedByMe: true, likeCount: 5 })).toEqual({ likedByMe: false, likeCount: 4 });
    expect(toggled({ likedByMe: true, likeCount: 0 })).toEqual({ likedByMe: false, likeCount: 0 });
  });
});
