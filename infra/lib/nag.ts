import * as path from 'node:path';

import {
  IPolicyValidationContextBeta1,
  IPolicyValidationPluginBeta1,
  PolicyValidationPluginReportBeta1,
  PolicyViolationBeta1,
  Stack,
  Stage,
} from 'aws-cdk-lib';
import { AwsSolutionsChecks, IApplyRule, NagMessageLevel, NagPackProps, NagRuleCompliance } from 'cdk-nag';
// cdk-nag's own formatter for the text of a finding, so the ids built below match its findings exactly.
import { flattenCfnReference } from 'cdk-nag/lib/utils/flatten-cfn-reference';
import type { IConstruct } from 'constructs';

/** Construct metadata type under which every acknowledgement is recorded, as `{ [ruleId]: reason }`. */
export const ACKNOWLEDGED_RULES_METADATA_KEY = 'soundcheck:acknowledged-rules';

/**
 * One cdk-nag finding we accept, and why. `rule` is the AwsSolutions rule (`RDS3`), or a single finding of
 * a rule that reports several (`IAM5[Resource::*]`): each finding is acknowledged separately.
 */
export interface NagSuppression {
  readonly rule: string;
  readonly reason: string;
}

function requireReason(scope: IConstruct, rule: string, reason: string): void {
  if (reason.trim().length < 10) {
    throw new Error(`cdk-nag suppression ${rule} on ${scope.node.path} needs a real reason`);
  }
}

/** Records an acknowledgement on a construct; it covers the construct and everything under it. */
function acknowledge(scope: IConstruct, id: string, reason: string): void {
  scope.node.addMetadata(ACKNOWLEDGED_RULES_METADATA_KEY, { [id]: reason });
}

/** Whether `ruleId` is acknowledged on the resource or on any construct above it. */
function isAcknowledged(resource: IConstruct, ruleId: string): boolean {
  for (let current: IConstruct | undefined = resource; current !== undefined; current = current.node.scope) {
    for (const entry of current.node.metadata) {
      if (entry.type === ACKNOWLEDGED_RULES_METADATA_KEY && Object.keys(entry.data as object).includes(ruleId)) {
        return true;
      }
    }
  }
  return false;
}

/**
 * Acknowledges cdk-nag findings on one construct (and its children), recorded as construct metadata that
 * `SoundcheckNagChecks` reads. Every suppression must say why; an empty reason fails the synth.
 * `infra/test/nag.test.ts` lists every suppression the app makes.
 */
export function suppress(scope: IConstruct, ...suppressions: NagSuppression[]): void {
  for (const { rule, reason } of suppressions) {
    requireReason(scope, rule, reason);
    acknowledge(scope, `AwsSolutions-${rule}`, reason);
  }
}

/** Wildcard resources recorded by `suppressWildcardResources`, per construct, until validation. */
const pendingWildcards = new WeakMap<IConstruct, { resources: string[]; reason: string }[]>();

/**
 * Acknowledges IAM5 ("wildcard permissions") for specific wildcard resources, such as an object prefix
 * `arn:…:<bucket>/uploads/*`. cdk-nag names each finding by the resource's text, and when the bucket is
 * in another stack that text (an `Fn::ImportValue` export name) exists only once CDK has resolved the
 * cross-stack references, late in the synth. So the resource is recorded here, and `SoundcheckNagChecks`
 * acknowledges it just before checking, when its text is known.
 */
export function suppressWildcardResources(scope: IConstruct, resources: string[], reason: string): void {
  requireReason(scope, 'IAM5', reason);
  pendingWildcards.set(scope, [...(pendingWildcards.get(scope) ?? []), { resources, reason }]);
}

/**
 * cdk-nag's AwsSolutions rules as a CDK policy-validation plugin: CDK runs it once the templates are
 * synthesised (cross-stack references resolved), and every finding (error or warning level) that is not
 * acknowledged is a violation, which fails the synth. It first acknowledges the wildcard resources
 * recorded above. `validateScope` runs the same checks directly (tests).
 */
export class SoundcheckNagChecks extends AwsSolutionsChecks implements IPolicyValidationPluginBeta1 {
  readonly name = 'AwsSolutions';
  private readonly verboseDescriptions: boolean;
  private violations: PolicyViolationBeta1[] = [];

  /** `app` is what `validate` checks when CDK calls it during synthesis. */
  constructor(
    private readonly app?: IConstruct,
    props: NagPackProps = {},
  ) {
    // Findings go only into the validation report: no annotations, no CSV report in the cloud assembly.
    super({ ...props, reports: false });
    this.verboseDescriptions = props.verbose ?? false;
  }

  validate(_context: IPolicyValidationContextBeta1): PolicyValidationPluginReportBeta1 {
    if (this.app === undefined) throw new Error('SoundcheckNagChecks needs the app it validates');
    return this.validateScope(this.app);
  }

  validateScope(scope: IConstruct): PolicyValidationPluginReportBeta1 {
    for (const construct of scope.node.findAll()) {
      for (const { resources, reason } of pendingWildcards.get(construct) ?? []) {
        for (const resource of resources) {
          const text = flattenCfnReference(Stack.of(construct).resolve(resource));
          acknowledge(construct, `AwsSolutions-IAM5[Resource::${text}]`, reason);
        }
      }
      pendingWildcards.delete(construct);
    }

    this.violations = [];
    for (const construct of scope.node.findAll()) {
      this.visit(construct);
    }
    return { success: this.violations.length === 0, violations: this.violations };
  }

  /** Called by the AwsSolutions pack for every rule on every resource. */
  protected override applyRule(params: IApplyRule): void {
    const ruleId = `${this.readPackName}-${params.ruleSuffixOverride || params.rule.name}`;
    try {
      const result = params.rule(params.node);
      if (result === NagRuleCompliance.NON_COMPLIANT) {
        this.addViolation(ruleId, params);
      } else if (Array.isArray(result)) {
        for (const finding of result) {
          this.addViolation(`${ruleId}[${finding}]`, params);
        }
      }
    } catch (error) {
      this.addViolation(ruleId, params, (error as Error).message);
    }
  }

  /** Records an unacknowledged finding, grouped by rule id. */
  private addViolation(ruleName: string, params: IApplyRule, errorMessage?: string): void {
    if (isAcknowledged(params.node, ruleName)) return;
    const description = errorMessage
      ? 'Rule threw an error during validation. ' +
        (this.verboseDescriptions ? errorMessage : 'This is generally caused by a parameter referencing an intrinsic function.')
      : this.verboseDescriptions
        ? `${params.info} ${params.explanation}`
        : params.info;
    const stack = Stack.of(params.node);
    const resource = {
      resourceLogicalId: stack.resolve(params.node.logicalId) as string,
      templatePath: path.join(Stage.of(stack)?.outdir ?? '', stack.templateFile),
      locations: ['Properties'],
    };
    const existing = this.violations.find((v) => v.ruleName === ruleName);
    if (existing) {
      existing.violatingResources.push(resource);
    } else {
      this.violations.push({
        ruleName,
        description,
        severity: params.level === NagMessageLevel.ERROR ? 'error' : 'warning',
        violatingResources: [resource],
      });
    }
  }
}
