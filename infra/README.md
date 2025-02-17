# infra

AWS CDK v2 (TypeScript) stacks — `SoundcheckNetwork`, `SoundcheckData`, `SoundcheckCompute`, `SoundcheckEdge`,
`SoundcheckObservability`, `SoundcheckCi` — with cdk-nag `AwsSolutionsChecks` on every synth. Empty stubs; resources come later.

```bash
npm install
npm test            # assertion tests (offline synth, cdk-nag clean)
npx cdk synth       # offline with placeholder account; real account/region come from the CLI profile
```

Never `cdk deploy`/`destroy` without the owner's explicit go-ahead. `lib/app.ts` builds the app so `bin/` and tests share one definition.
