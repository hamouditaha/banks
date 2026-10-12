export const environment = {
  // Empty: requests go to /api on the same origin (dev proxy or nginx forward them to the gateway).
  apiUrl: '',
  // Keycloak login (realm imported from keycloak/realm-export.json). The gateway requires the token.
  auth: {
    enabled: true,
    url: 'http://localhost:8180',
    realm: 'banks',
    clientId: 'banks-frontend',
  },
};
