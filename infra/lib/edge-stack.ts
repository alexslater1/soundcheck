import * as path from 'node:path';

import {
  aws_cloudfront as cloudfront,
  aws_cloudfront_origins as origins,
  aws_ec2 as ec2,
  aws_elasticloadbalancingv2 as elbv2,
  aws_iam as iam,
  aws_s3 as s3,
  Annotations,
  CfnOutput,
  Duration,
  RemovalPolicy,
} from 'aws-cdk-lib';
import { Construct } from 'constructs';

import { SoundcheckStack, SoundcheckStackProps } from './base';
import { suppress } from './nag';

export interface EdgeStackProps extends SoundcheckStackProps {
  readonly vpc: ec2.IVpc;
  readonly albSg: ec2.ISecurityGroup;
}

const ACCESS_LOGS_REASON = 'No access logs: storage and request costs on a demo budget; revisit before real users.';

/**
 * The one public address and what sits directly behind it:
 *
 *   https://dxxxx.cloudfront.net
 *     /*        → web bucket (OAC), single-page-app rewrite in a CloudFront Function
 *     /api/*    → VPC origin → internal ALB :80 → (Compute's listener rule) → API tasks
 *     /media/*  → media bucket (OAC, media/* only)
 *
 * The ALB and both buckets live here, not in Compute or Data: OAC bucket policies name this
 * distribution, and a distribution in another stack would make a dependency cycle. Compute only adds a
 * listener rule and a target group, so this stack never depends on Compute.
 */
export class EdgeStack extends SoundcheckStack {
  readonly alb: elbv2.ApplicationLoadBalancer;
  readonly listener: elbv2.ApplicationListener;
  readonly webBucket: s3.Bucket;
  readonly mediaBucket: s3.Bucket;
  readonly distribution: cloudfront.Distribution;
  /** `https://dxxxx.cloudfront.net`, no trailing slash: the API's PUBLIC_BASE_URL. */
  readonly publicBaseUrl: string;

  constructor(scope: Construct, id: string, props: EdgeStackProps) {
    super(scope, id, props);

    // --- Internal ALB: reachable only through CloudFront's VPC origin (and albSg's prefix list). ---
    this.alb = new elbv2.ApplicationLoadBalancer(this, 'Alb', {
      vpc: props.vpc,
      internetFacing: false,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroup: props.albSg,
      idleTimeout: Duration.seconds(60),
      dropInvalidHeaderFields: true,
    });
    suppress(this.alb, { rule: 'ELB2', reason: ACCESS_LOGS_REASON });
    // Until Compute adds its /api/* rule (or while it is paused), the ALB answers 503 itself.
    this.listener = this.alb.addListener('Http', {
      port: 80,
      protocol: elbv2.ApplicationProtocol.HTTP,
      open: false,
      defaultAction: elbv2.ListenerAction.fixedResponse(503, {
        contentType: 'application/json',
        messageBody: '{"title":"API unavailable"}',
      }),
    });

    // --- Buckets: private, encrypted, TLS only. ---
    const privateBucket = {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
    };
    this.webBucket = new s3.Bucket(this, 'WebBucket', {
      ...privateBucket,
      removalPolicy: RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    });
    // RETAIN: the clips survive an accidental destroy; make aws-destroy empties and deletes it only after
    // a second confirmation.
    this.mediaBucket = new s3.Bucket(this, 'MediaBucket', {
      ...privateBucket,
      removalPolicy: RemovalPolicy.RETAIN,
      lifecycleRules: [
        { id: 'abort-incomplete-multipart', abortIncompleteMultipartUploadAfter: Duration.days(1) },
        {
          id: 'uploads-to-ia',
          prefix: 'uploads/',
          transitions: [{ storageClass: s3.StorageClass.INFREQUENT_ACCESS, transitionAfter: Duration.days(30) }],
        },
      ],
      // Browsers PUT straight to presigned URLs, and multipart completion reads each part's ETag.
      cors: [
        {
          allowedOrigins: [this.config.webOrigin ?? 'https://*.cloudfront.net'],
          allowedMethods: [s3.HttpMethods.PUT, s3.HttpMethods.GET, s3.HttpMethods.HEAD],
          allowedHeaders: ['*'],
          exposedHeaders: ['ETag'],
          maxAge: 3000,
        },
      ],
    });
    for (const bucket of [this.webBucket, this.mediaBucket]) {
      suppress(bucket, { rule: 'S1', reason: ACCESS_LOGS_REASON });
    }

    // --- CloudFront ---
    const spaRewrite = new cloudfront.Function(this, 'SpaRewrite', {
      code: cloudfront.FunctionCode.fromFile({ filePath: path.join(__dirname, 'functions', 'spa-rewrite.js') }),
      runtime: cloudfront.FunctionRuntime.JS_2_0,
      comment: 'Paths without a file extension (app routes) get /index.html',
    });

    // The local edge's headers (ops/caddy/Caddyfile) plus HSTS. CSP sets only frame-ancestors, so the
    // Expo web bundle's inline styles and scripts keep working.
    const securityHeaders = new cloudfront.ResponseHeadersPolicy(this, 'SecurityHeaders', {
      comment: 'Soundcheck security headers',
      securityHeadersBehavior: {
        strictTransportSecurity: { accessControlMaxAge: Duration.days(365), includeSubdomains: true, override: true },
        contentTypeOptions: { override: true },
        frameOptions: { frameOption: cloudfront.HeadersFrameOption.DENY, override: true },
        referrerPolicy: {
          referrerPolicy: cloudfront.HeadersReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN,
          override: true,
        },
        contentSecurityPolicy: { contentSecurityPolicy: "frame-ancestors 'none'", override: true },
      },
    });

    // Everything the viewer sent reaches the API, plus CloudFront-Viewer-Address: behind CloudFront the
    // ALB sees a CloudFront address, so the backend reads the viewer's IP from this header.
    const apiOriginRequest = new cloudfront.OriginRequestPolicy(this, 'ApiOriginRequest', {
      comment: 'All viewer headers, cookies and query strings, plus CloudFront-Viewer-Address',
      headerBehavior: cloudfront.OriginRequestHeaderBehavior.all('CloudFront-Viewer-Address'),
      cookieBehavior: cloudfront.OriginRequestCookieBehavior.all(),
      queryStringBehavior: cloudfront.OriginRequestQueryStringBehavior.all(),
    });

    // The media origin is given a reference to the bucket, not the bucket itself, so CloudFront's L2
    // can't write its default grant (GetObject on every object). The statement below grants media/* only:
    // uploads/ holds originals, which are never public.
    const mediaBucketForOrigin = s3.Bucket.fromBucketAttributes(this, 'MediaBucketForOrigin', {
      bucketArn: this.mediaBucket.bucketArn,
      bucketName: this.mediaBucket.bucketName,
      bucketRegionalDomainName: this.mediaBucket.bucketRegionalDomainName,
    });

    this.distribution = new cloudfront.Distribution(this, 'Distribution', {
      comment: `Soundcheck ${this.config.env}`,
      priceClass: cloudfront.PriceClass.PRICE_CLASS_100,
      httpVersion: cloudfront.HttpVersion.HTTP2_AND_3,
      defaultRootObject: 'index.html',
      defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(this.webBucket),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
        responseHeadersPolicy: securityHeaders,
        functionAssociations: [{ function: spaRewrite, eventType: cloudfront.FunctionEventType.VIEWER_REQUEST }],
      },
      additionalBehaviors: {
        '/api/*': {
          origin: origins.VpcOrigin.withApplicationLoadBalancer(this.alb, {
            httpPort: 80,
            protocolPolicy: cloudfront.OriginProtocolPolicy.HTTP_ONLY,
            readTimeout: Duration.seconds(60),
          }),
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
          allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
          cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
          originRequestPolicy: apiOriginRequest,
          responseHeadersPolicy: securityHeaders,
        },
        '/media/*': {
          origin: origins.S3BucketOrigin.withOriginAccessControl(mediaBucketForOrigin),
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
          allowedMethods: cloudfront.AllowedMethods.ALLOW_GET_HEAD_OPTIONS,
          cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
          responseHeadersPolicy: securityHeaders,
        },
      },
    });
    Annotations.of(this.distribution).acknowledgeWarning(
      '@aws-cdk/aws-cloudfront-origins:updateImportedBucketPolicyOac',
      'Deliberate: the media bucket policy below grants media/* only.',
    );
    this.mediaBucket.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'CloudFrontReadsRenditionsOnly',
        principals: [new iam.ServicePrincipal('cloudfront.amazonaws.com')],
        actions: ['s3:GetObject'],
        resources: [this.mediaBucket.arnForObjects('media/*')],
        conditions: { StringEquals: { 'AWS:SourceArn': this.distribution.distributionArn } },
      }),
    );
    suppress(
      this.distribution,
      { rule: 'CFR1', reason: 'No geo restriction: a UK demo, but viewers abroad are not a risk worth the rule.' },
      { rule: 'CFR2', reason: 'No WAF: a web ACL costs more than the demo budget; the API rate-limits itself.' },
      { rule: 'CFR3', reason: ACCESS_LOGS_REASON },
      {
        rule: 'CFR4',
        reason: 'Default *.cloudfront.net certificate (no custom domain), which pins the minimum to TLSv1.',
      },
    );

    this.publicBaseUrl = `https://${this.distribution.distributionDomainName}`;
    new CfnOutput(this, 'PublicBaseUrl', { value: this.publicBaseUrl });
    new CfnOutput(this, 'DistributionId', { value: this.distribution.distributionId });
    new CfnOutput(this, 'MediaBucketName', { value: this.mediaBucket.bucketName });
    new CfnOutput(this, 'WebBucketName', { value: this.webBucket.bucketName });
  }
}
