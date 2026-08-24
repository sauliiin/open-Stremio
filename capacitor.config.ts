/// <reference types="@capacitor/app" />
/// <reference types="@capacitor/status-bar" />
/// <reference types="@capacitor/splash-screen" />

import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.mdblisthub.app',
  appName: 'mdblist hub',
  // O site vive no repo irmão ../mdblist-hub; `cap sync` lê o build dele daqui.
  webDir: '../../mdblist-hub/dist/mdblist-hub/browser',
  backgroundColor: '#07080c',
  android: {
    // Some personal Stremio addons return direct HTTP video URLs.
    allowMixedContent: true,
  },
  plugins: {
    // Routes Angular's fetch/XHR calls through the native network stack. Besides
    // avoiding WebView CORS restrictions, this lets mdblist JSON writes work in
    // the APK without the development proxy used by the browser build.
    CapacitorHttp: {
      enabled: true,
    },
    StatusBar: {
      style: 'DARK',
      backgroundColor: '#07080c',
    },
    SplashScreen: {
      launchAutoHide: true,
      launchShowDuration: 800,
      backgroundColor: '#07080c',
      showSpinner: false,
    },
  },
};

export default config;
