import { Linking } from 'react-native';

import { isWebUrl, openWebLink } from './openLink';

describe('openWebLink', () => {
  it('accepts only http and https URLs', () => {
    expect(isWebUrl('https://www.ticketmaster.co.uk/event/1')).toBe(true);
    expect(isWebUrl('HTTP://example.com')).toBe(true);
    for (const bad of [
      'javascript:alert(1)',
      'intent://x#Intent;end',
      'soundcheck://x',
      'file:///etc/passwd',
      '',
      null,
      undefined,
    ]) {
      expect(isWebUrl(bad)).toBe(false);
    }
  });

  it('hands web links to the system and ignores everything else', async () => {
    const open = jest.spyOn(Linking, 'openURL').mockResolvedValue(true);
    expect(await openWebLink('javascript:alert(1)')).toBe(false);
    expect(open).not.toHaveBeenCalled();
    expect(await openWebLink(' https://tickets.example.com/e1 ')).toBe(true);
    expect(open).toHaveBeenCalledWith('https://tickets.example.com/e1');
    open.mockRestore();
  });
});
