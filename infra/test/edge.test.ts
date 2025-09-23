import { Match } from 'aws-cdk-lib/assertions';

import type { EdgeStack } from '../lib/edge-stack';
import { buildApp, resourcesOf, stackOf, templateOf } from './helpers';

type Json = Record<string, unknown>;

/** CloudFront in front of everything; the ALB and buckets reachable only through it. */
describe('SoundcheckEdge', () => {
  const app = buildApp();
  const edge = stackOf<EdgeStack>(app, 'SoundcheckEdge');
  const template = templateOf(app, 'SoundcheckEdge');
  const logicalId = (c: { node: { defaultChild?: unknown } }) => edge.getLogicalId(c.node.defaultChild as never);
  const webId = logicalId(edge.webBucket);
  const mediaId = logicalId(edge.mediaBucket);
  const [[, distribution]] = resourcesOf(template, 'AWS::CloudFront::Distribution') as [[string, { DistributionConfig: Json }]];
  const config = distribution.DistributionConfig as {
    Origins: (Json & { Id: string; DomainName: unknown })[];
    DefaultCacheBehavior: Json & { TargetOriginId: string };
    CacheBehaviors: (Json & { PathPattern: string; TargetOriginId: string })[];
  };
  const originOf = (behaviour: { TargetOriginId: string }) => config.Origins.find((o) => o.Id === behaviour.TargetOriginId)!;
  const behaviour = (pattern: string) => config.CacheBehaviors.find((b) => b.PathPattern === pattern)!;

  it('has an internal ALB whose listener answers 503 by default', () => {
    template.hasResourceProperties('AWS::ElasticLoadBalancingV2::LoadBalancer', {
      Scheme: 'internal',
      Type: 'application',
    });
    template.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
      Port: 80,
      Protocol: 'HTTP',
      DefaultActions: [
        Match.objectLike({ Type: 'fixed-response', FixedResponseConfig: Match.objectLike({ StatusCode: '503' }) }),
      ],
    });
  });

  it('blocks all public access on both buckets and refuses plain HTTP', () => {
    for (const id of [webId, mediaId]) {
      const bucket = (template.toJSON().Resources[id] as { Properties: Json }).Properties;
      expect(bucket.PublicAccessBlockConfiguration).toEqual({
        BlockPublicAcls: true,
        BlockPublicPolicy: true,
        IgnorePublicAcls: true,
        RestrictPublicBuckets: true,
      });
      expect(JSON.stringify(bucket.BucketEncryption)).toContain('AES256');
    }
    const policies = resourcesOf(template, 'AWS::S3::BucketPolicy').map(([, p]) => p);
    for (const id of [webId, mediaId]) {
      const policy = policies.find((p) => JSON.stringify(p.Bucket) === JSON.stringify({ Ref: id }));
      expect(JSON.stringify(policy)).toContain('"aws:SecureTransport":"false"');
    }
  });

  it('keeps the media bucket on delete; the web bucket goes with the stack', () => {
    template.hasResource('AWS::S3::Bucket', { DeletionPolicy: 'Retain', Properties: Match.objectLike({ CorsConfiguration: Match.anyValue() }) });
    expect((template.toJSON().Resources[mediaId] as Json).DeletionPolicy).toBe('Retain');
    expect((template.toJSON().Resources[webId] as Json).DeletionPolicy).toBe('Delete');
  });

  it("lets browsers PUT to the media bucket and read each part's ETag", () => {
    const media = (template.toJSON().Resources[mediaId] as { Properties: Json }).Properties;
    expect(media.CorsConfiguration).toEqual({
      CorsRules: [
        {
          AllowedOrigins: ['https://*.cloudfront.net'],
          AllowedMethods: ['PUT', 'GET', 'HEAD'],
          AllowedHeaders: ['*'],
          ExposedHeaders: ['ETag'],
          MaxAge: 3000,
        },
      ],
    });
    expect(JSON.stringify(media.LifecycleConfiguration)).toContain('"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1}');
    expect(JSON.stringify(media.LifecycleConfiguration)).toContain('"Prefix":"uploads/"');
  });

  it('uses the real web origin for CORS once it is known', () => {
    const withOrigin = templateOf(buildApp({ webOrigin: 'https://d123.cloudfront.net' }), 'SoundcheckEdge');
    expect(JSON.stringify(withOrigin.toJSON())).toContain('"AllowedOrigins":["https://d123.cloudfront.net"]');
  });

  it('routes the default behaviour to the web bucket, /api/* to the VPC origin and /media/* to the media bucket', () => {
    expect(config.CacheBehaviors.map((b) => b.PathPattern).sort()).toEqual(['/api/*', '/media/*']);
    expect(JSON.stringify(originOf(config.DefaultCacheBehavior).DomainName)).toContain(webId);
    expect(JSON.stringify(originOf(behaviour('/media/*')).DomainName)).toContain(mediaId);
    expect(originOf(behaviour('/api/*')).VpcOriginConfig).toBeDefined();
    for (const b of [config.DefaultCacheBehavior, ...config.CacheBehaviors]) {
      expect(b.ViewerProtocolPolicy).toBe('redirect-to-https');
    }
    expect(distribution.DistributionConfig).toMatchObject({
      PriceClass: 'PriceClass_100',
      HttpVersion: 'http2and3',
      DefaultRootObject: 'index.html',
    });
  });

  it('reaches both buckets through Origin Access Control', () => {
    template.resourceCountIs('AWS::CloudFront::OriginAccessControl', 2);
    for (const pattern of [undefined, '/media/*']) {
      const origin = originOf(pattern ? behaviour(pattern) : config.DefaultCacheBehavior);
      expect(origin.OriginAccessControlId).toBeDefined();
      expect(origin.S3OriginConfig).toEqual({ OriginAccessIdentity: '' });
    }
  });

  it('lets CloudFront read media/* only, never uploads/*', () => {
    const media = resourcesOf(template, 'AWS::S3::BucketPolicy').find(
      ([, p]) => JSON.stringify(p.Bucket) === JSON.stringify({ Ref: mediaId }),
    )![1] as { PolicyDocument: { Statement: Json[] } };
    const cloudFront = media.PolicyDocument.Statement.filter(
      (s) => JSON.stringify(s.Principal) === JSON.stringify({ Service: 'cloudfront.amazonaws.com' }),
    );
    expect(cloudFront).toHaveLength(1);
    expect(cloudFront[0]!.Effect).toBe('Allow');
    expect(cloudFront[0]!.Action).toBe('s3:GetObject');
    expect(JSON.stringify(cloudFront[0]!.Resource)).toMatch(/\/media\/\*"\]\]\}$/);
    expect(JSON.stringify(cloudFront[0]!.Condition)).toContain('AWS:SourceArn');
    expect(JSON.stringify(media)).not.toContain('uploads/');
  });

  it('reaches the ALB over a VPC origin, HTTP only on port 80', () => {
    template.hasResourceProperties('AWS::CloudFront::VpcOrigin', {
      VpcOriginEndpointConfig: Match.objectLike({ HTTPPort: 80, OriginProtocolPolicy: 'http-only' }),
    });
    expect(originOf(behaviour('/api/*')).VpcOriginConfig).toMatchObject({ OriginReadTimeout: 60 });
  });

  it('never caches the API and forwards the viewer address', () => {
    const api = behaviour('/api/*');
    expect(api.CachePolicyId).toBe('4135ea2d-6df8-44a3-9df3-4b5a84be39ad'); // Managed-CachingDisabled
    expect(api.AllowedMethods).toEqual(['GET', 'HEAD', 'OPTIONS', 'PUT', 'PATCH', 'POST', 'DELETE']);
    template.hasResourceProperties('AWS::CloudFront::OriginRequestPolicy', {
      OriginRequestPolicyConfig: Match.objectLike({
        HeadersConfig: { HeaderBehavior: 'allViewerAndWhitelistCloudFront', Headers: ['CloudFront-Viewer-Address'] },
        CookiesConfig: { CookieBehavior: 'all' },
        QueryStringsConfig: { QueryStringBehavior: 'all' },
      }),
    });
    expect(behaviour('/media/*').AllowedMethods).toEqual(['GET', 'HEAD', 'OPTIONS']);
  });

  it('runs the single-page-app rewrite on the default behaviour only', () => {
    expect(config.DefaultCacheBehavior.FunctionAssociations).toEqual([
      expect.objectContaining({ EventType: 'viewer-request' }),
    ]);
    for (const b of config.CacheBehaviors) {
      expect(b.FunctionAssociations).toBeUndefined();
    }
    template.hasResourceProperties('AWS::CloudFront::Function', {
      FunctionConfig: Match.objectLike({ Runtime: 'cloudfront-js-2.0' }),
    });
  });

  it('sends HSTS and the other security headers on every behaviour', () => {
    template.hasResourceProperties('AWS::CloudFront::ResponseHeadersPolicy', {
      ResponseHeadersPolicyConfig: Match.objectLike({
        SecurityHeadersConfig: {
          StrictTransportSecurity: { AccessControlMaxAgeSec: 31536000, IncludeSubdomains: true, Override: true },
          ContentTypeOptions: { Override: true },
          FrameOptions: { FrameOption: 'DENY', Override: true },
          ReferrerPolicy: { ReferrerPolicy: 'strict-origin-when-cross-origin', Override: true },
          ContentSecurityPolicy: { ContentSecurityPolicy: "frame-ancestors 'none'", Override: true },
        },
      }),
    });
    const policyIds = [config.DefaultCacheBehavior, ...config.CacheBehaviors].map((b) => JSON.stringify(b.ResponseHeadersPolicyId));
    expect(new Set(policyIds).size).toBe(1);
  });

  it('exports the contract outputs', () => {
    const outputs = template.toJSON().Outputs as Record<string, { Value: unknown }>;
    expect(Object.keys(outputs)).toEqual(
      expect.arrayContaining(['PublicBaseUrl', 'DistributionId', 'MediaBucketName', 'WebBucketName']),
    );
    expect(JSON.stringify(outputs.PublicBaseUrl!.Value)).toMatch(/^\{"Fn::Join":\["",\["https:\/\/",/);
  });
});
