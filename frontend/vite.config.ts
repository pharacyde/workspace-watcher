import { defineConfig } from 'vite';

export default defineConfig({

  // Built straight into the jar's static resources, so `mvn package` produces one runnable
  // artifact and production needs no node at all.
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },

  server: {
    // Bound explicitly: Vite otherwise listens on ::1 only, so http://127.0.0.1:5173 fails while
    // http://localhost:5173 works, which is a confusing five minutes for anyone.
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    // In development the UI is served by Vite with hot module replacement while the backend keeps
    // running on 8080. Both the HTTP endpoint and the graphql-ws socket are proxied, so the app
    // talks to a same-origin /graphql either way and needs no environment switch in the code.
    proxy: {
      '/graphql': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        ws: true,
      },
      '/graphiql': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
});
