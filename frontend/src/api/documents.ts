import { graphql } from '../gql';

// Every document below is checked against schema.graphqls at build time by graphql-codegen, and
// the result types are generated from it. A field renamed in the schema fails `npm run build`
// rather than surfacing as undefined in the browser.

export const StatusDocument = graphql(`
  query Status {
    status {
      workspace
      workspaceExists
      os
      transcriptDirs
    }
  }
`);

export const EventsDocument = graphql(`
  subscription Events {
    events {
      seq
      ts
      source
      type
      summary
      path
      agent
      sessionId
      detail
    }
  }
`);

export const GitStatusDocument = graphql(`
  subscription GitStatus {
    gitStatus {
      repo
      branch
      head
      headSubject
      files {
        path
        status
        staged
      }
    }
  }
`);

// Fields are repeated rather than pulled into a fragment: GraphQL has no recursive fragments,
// and a fragment here would only add masking wrappers to the generated types for no gain.
export const ProcessTreeDocument = graphql(`
  subscription ProcessTree {
    processTree {
      at
      total
      roots {
        pid
        command
        cwd
        children {
          pid
          command
          cwd
          children {
            pid
            command
            cwd
            children {
              pid
              command
              cwd
            }
          }
        }
      }
    }
  }
`);

export const FileVersionsDocument = graphql(`
  query FileVersions($path: String!) {
    fileVersions(path: $path) {
      path
      head
      working
      binary
      tooLarge
    }
  }
`);
