// `npm start`: quick local demo without Keycloak, against a gateway running the "dev" Spring profile.
// Use `npm run start:auth` to serve the dev build with Keycloak login enabled (environment.ts).
// Standalone on purpose: this file replaces environment.ts at build time, so it cannot import it.
export const environment = {
  apiUrl: '',
  auth: {
    enabled: false,
    url: 'http://localhost:8180',
    realm: 'banks',
    clientId: 'banks-frontend',
  },
};
