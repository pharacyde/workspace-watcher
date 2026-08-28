import type { CodegenConfig } from '@graphql-codegen/cli';

// The schema is the contract. TypeScript types come out of the same file the Java types are
// generated from, so a field renamed in the schema breaks the frontend build too, rather than
// showing up as undefined at runtime.
const config: CodegenConfig = {
  schema: '../src/main/resources/graphql/schema.graphqls',
  documents: ['src/**/*.ts'],
  ignoreNoDocuments: true,
  generates: {
    './src/gql/': {
      preset: 'client',
      config: {
        // Subscriptions are pure data here; no fragment masking to unwrap in every component.
        fragmentMasking: false,
      },
    },
  },
};

export default config;
