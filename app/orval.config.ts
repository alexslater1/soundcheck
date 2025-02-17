import { defineConfig, type InputTransformerFn } from 'orval';

const dropEnumStringLimits: InputTransformerFn = (spec) => {
  const visit = (node: unknown): void => {
    if (Array.isArray(node)) {
      node.forEach(visit);
    } else if (node !== null && typeof node === 'object') {
      const schema = node as Record<string, unknown>;
      if (Array.isArray(schema.enum)) {
        delete schema.pattern;
        delete schema.minLength;
        delete schema.maxLength;
      }
      Object.values(schema).forEach(visit);
    }
  };
  visit(spec);
  return spec;
};

// orval 7 sends an array query parameter as one comma-joined value (`genreIds=2%2C14`) unless the spec
// says `explode: true`. Unsaid, OpenAPI's default for a query parameter is explode (style form): one
// `genreIds=2&genreIds=14` per value, as the API reads them. Saying it gets that.
const explodeQueryArrays: InputTransformerFn = (spec) => {
  for (const item of Object.values(spec.paths ?? {})) {
    for (const operation of Object.values(item as Record<string, unknown>)) {
      const parameters = (operation as { parameters?: unknown } | null)?.parameters;
      if (!Array.isArray(parameters)) {
        continue;
      }
      for (const parameter of parameters as Record<string, unknown>[]) {
        const schema = parameter.schema as { type?: unknown } | undefined;
        if (
          parameter.in === 'query' &&
          schema?.type === 'array' &&
          parameter.explode === undefined &&
          (parameter.style ?? 'form') === 'form'
        ) {
          parameter.explode = true;
        }
      }
    }
  }
  return spec;
};

/**
 * Generates TanStack Query hooks and zod schemas from the committed OpenAPI snapshot.
 * Run with `make openapi` (which refreshes contracts/openapi.json first). Output is never edited by hand.
 */
export default defineConfig({
  soundcheck: {
    input: {
      target: '../contracts/openapi.json',
      override: { transformer: explodeQueryArrays },
    },
    output: {
      target: 'src/api/generated/endpoints.ts',
      schemas: 'src/api/generated/model',
      client: 'react-query',
      httpClient: 'fetch',
      mode: 'tags-split',
      clean: true,
      override: {
        mutator: {
          path: 'src/api/http.ts',
          name: 'customFetch',
        },
        fetch: {
          includeHttpResponseReturnType: false,
        },
        query: {
          useQuery: true,
          signal: true,
        },
      },
    },
  },
  soundcheckZod: {
    input: {
      target: '../contracts/openapi.json',
      // orval 7's zod output turns a string enum that also has a pattern or length limits into invalid
      // code (`zod.regex(…).enum(…)`). Every enum value meets those limits, so the enum alone checks the same.
      override: { transformer: dropEnumStringLimits },
    },
    output: {
      target: 'src/api/generated/zod',
      client: 'zod',
      mode: 'tags-split',
      clean: true,
    },
  },
});
