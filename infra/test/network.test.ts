import type { NetworkStack } from '../lib/network-stack';
import { buildApp, resourcesOf, stackOf, templateOf } from './helpers';

type Rule = Record<string, unknown>;

/** No NAT, two AZs, and security groups that only admit the next hop in. */
describe('SoundcheckNetwork', () => {
  const app = buildApp();
  const network = stackOf<NetworkStack>(app, 'SoundcheckNetwork');
  const template = templateOf(app, 'SoundcheckNetwork');
  const resources = template.toJSON().Resources as Record<string, { Properties: Record<string, unknown> }>;
  const idOf = (sg: { node: { defaultChild?: unknown } }) => network.getLogicalId(sg.node.defaultChild as never);
  const ref = (sg: { node: { defaultChild?: unknown } }) => ({ 'Fn::GetAtt': [idOf(sg), 'GroupId'] });

  /** Every ingress rule into a security group: inline ones and standalone SecurityGroupIngress resources. */
  const ingressInto = (sg: { node: { defaultChild?: unknown } }): Rule[] => [
    ...((resources[idOf(sg)]!.Properties.SecurityGroupIngress as Rule[] | undefined) ?? []),
    ...resourcesOf(template, 'AWS::EC2::SecurityGroupIngress')
      .map(([, p]) => p)
      .filter((p) => JSON.stringify(p.GroupId) === JSON.stringify(ref(sg))),
  ];

  it('has no NAT gateway', () => {
    template.resourceCountIs('AWS::EC2::NatGateway', 0);
  });

  it('spans two AZs with a public and an isolated subnet in each', () => {
    const subnets = resourcesOf(template, 'AWS::EC2::Subnet').map(([, p]) => p);
    expect(subnets).toHaveLength(4);
    expect(new Set(subnets.map((s) => s.AvailabilityZone))).toEqual(new Set(['eu-north-1a', 'eu-north-1b']));
    expect(subnets.every((s) => s.MapPublicIpOnLaunch === false)).toBe(true);
    template.resourceCountIs('AWS::EC2::InternetGateway', 1);
  });

  it('routes S3 through a gateway endpoint on all four route tables', () => {
    const endpoints = resourcesOf(template, 'AWS::EC2::VPCEndpoint').map(([, p]) => p);
    expect(endpoints).toHaveLength(1);
    expect(endpoints[0]).toMatchObject({ VpcEndpointType: 'Gateway' });
    expect(JSON.stringify(endpoints[0]!.ServiceName)).toContain('.s3');
    expect(endpoints[0]!.RouteTableIds).toHaveLength(4);
  });

  it('admits HTTP into the ALB only from the CloudFront origin-facing prefix list', () => {
    expect(ingressInto(network.albSg)).toEqual([
      expect.objectContaining({ IpProtocol: 'tcp', FromPort: 80, ToPort: 80, SourcePrefixListId: 'pl-fab65393' }),
    ]);
  });

  it('admits the API tasks only from the ALB on 8080, and nothing into workers or jobs', () => {
    expect(ingressInto(network.apiTaskSg)).toEqual([
      expect.objectContaining({ FromPort: 8080, ToPort: 8080, SourceSecurityGroupId: ref(network.albSg) }),
    ]);
    expect(ingressInto(network.workerTaskSg)).toEqual([]);
    expect(ingressInto(network.jobTaskSg)).toEqual([]);
  });

  it('admits the database only from the three task security groups on 5432', () => {
    const rules = ingressInto(network.dbSg);
    expect(rules.map((p) => [p.FromPort, p.ToPort])).toEqual([
      [5432, 5432],
      [5432, 5432],
      [5432, 5432],
    ]);
    expect(new Set(rules.map((p) => JSON.stringify(p.SourceSecurityGroupId)))).toEqual(
      new Set([network.apiTaskSg, network.workerTaskSg, network.jobTaskSg].map((sg) => JSON.stringify(ref(sg)))),
    );
    // allowAllOutbound: false leaves only CDK's "no traffic" placeholder egress rule.
    expect(resources[idOf(network.dbSg)]!.Properties.SecurityGroupEgress).toEqual([
      expect.objectContaining({ CidrIp: '255.255.255.255/32' }),
    ]);
  });
});
