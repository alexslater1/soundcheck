import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';

import { WEB_STACK_NAME } from '../lib/app';
import { buildApp, templateOf } from './helpers';

/** The Web stack exists only when there is a web export to copy. */
describe('SoundcheckWeb', () => {
  it('is absent without webDist', () => {
    expect(buildApp().node.tryFindChild(WEB_STACK_NAME)).toBeUndefined();
  });

  it('copies webDist into the web bucket and invalidates index.html', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'soundcheck-web-'));
    try {
      fs.writeFileSync(path.join(dir, 'index.html'), '<!doctype html><title>Soundcheck</title>');
      const app = buildApp({ webDist: dir });
      const template = templateOf(app, WEB_STACK_NAME);
      template.hasResourceProperties('Custom::CDKBucketDeployment', {
        Prune: true,
        DistributionPaths: ['/index.html', '/'],
      });
      expect(app.synth().getStackByName(WEB_STACK_NAME).dependencies.map((d) => d.id)).toContain(
        'SoundcheckObservability',
      );
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});
