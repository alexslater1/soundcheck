import { aws_ec2 as ec2 } from 'aws-cdk-lib';
import { Construct } from 'constructs';

import { SoundcheckStack, SoundcheckStackProps } from './base';
import { AVAILABILITY_ZONES } from './config';
import { suppress } from './nag';

/** PostgreSQL's port; the database keeps the default (cdk-nag RDS11 is suppressed in the Data stack). */
export const DB_PORT = 5432;
/** The port every container listens on (API, worker and job images). */
export const CONTAINER_PORT = 8080;

/**
 * The network: a VPC across two AZs with public subnets (Fargate tasks with public IPs, so no
 * NAT gateway) and isolated subnets (the internal ALB and RDS, no route to the internet at all), an S3
 * gateway endpoint, and one security group per kind of thing that talks:
 *
 *   CloudFront (prefix list) → albSg :80 → apiTaskSg :8080
 *   apiTaskSg, workerTaskSg, jobTaskSg → dbSg :5432
 *
 * Tasks may call out anywhere (Ticketmaster, Last.fm, setlist.fm, FCM, Grafana Cloud, ECR, SQS, Secrets
 * Manager) through the internet gateway.
 */
export class NetworkStack extends SoundcheckStack {
  readonly vpc: ec2.Vpc;
  readonly albSg: ec2.SecurityGroup;
  readonly apiTaskSg: ec2.SecurityGroup;
  readonly workerTaskSg: ec2.SecurityGroup;
  readonly jobTaskSg: ec2.SecurityGroup;
  readonly dbSg: ec2.SecurityGroup;

  constructor(scope: Construct, id: string, props: SoundcheckStackProps) {
    super(scope, id, props);

    this.vpc = new ec2.Vpc(this, 'Vpc', {
      ipAddresses: ec2.IpAddresses.cidr('10.0.0.0/16'),
      availabilityZones: AVAILABILITY_ZONES,
      natGateways: 0,
      subnetConfiguration: [
        // Tasks get a public IP only because their service asks for one (assignPublicIp).
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24, mapPublicIpOnLaunch: false },
        { name: 'isolated', subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
      // S3 traffic (uploads the worker reads, renditions it writes) stays on AWS's network and is free.
      gatewayEndpoints: { S3: { service: ec2.GatewayVpcEndpointAwsService.S3 } },
      restrictDefaultSecurityGroup: false,
    });
    suppress(this.vpc, {
      rule: 'VPC7',
      reason: 'No VPC flow logs: CloudWatch ingestion costs on a demo budget; revisit before real users.',
    });

    const sg = (sgId: string, description: string, allowAllOutbound = true) =>
      new ec2.SecurityGroup(this, sgId, { vpc: this.vpc, description, allowAllOutbound });

    this.albSg = sg('AlbSg', 'Internal ALB: HTTP from CloudFront origin-facing servers only');
    // The only way in: CloudFront's origin-facing servers, named by AWS's managed prefix list. The ALB is
    // internal, so nothing on the internet can reach it directly either.
    this.albSg.addIngressRule(
      ec2.Peer.prefixList(this.config.cloudFrontPrefixListId),
      ec2.Port.tcp(80),
      'HTTP from CloudFront (com.amazonaws.global.cloudfront.origin-facing)',
    );

    this.apiTaskSg = sg('ApiTaskSg', 'API tasks: 8080 from the ALB');
    this.apiTaskSg.addIngressRule(this.albSg, ec2.Port.tcp(CONTAINER_PORT), 'HTTP from the ALB');

    this.workerTaskSg = sg('WorkerTaskSg', 'Transcode workers: no inbound');
    this.jobTaskSg = sg('JobTaskSg', 'One-off jobs (cli role): no inbound');

    // The database never starts a connection, so it gets no outbound rule at all.
    this.dbSg = sg('DbSg', 'PostgreSQL: 5432 from the task security groups only', false);
    for (const [peer, who] of [
      [this.apiTaskSg, 'API'],
      [this.workerTaskSg, 'workers'],
      [this.jobTaskSg, 'jobs'],
    ] as const) {
      this.dbSg.addIngressRule(peer, ec2.Port.tcp(DB_PORT), `PostgreSQL from ${who}`);
    }
  }
}
