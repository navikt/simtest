import { resolve } from 'node:path';
import { defineConfig } from 'vite';

export default defineConfig({
  root: resolve(process.cwd(), 'src/main/resources/gui'),
  cacheDir: resolve(process.cwd(), 'node_modules/.vite'),
  base: '/internal/gui/',
  publicDir: false,
  esbuild: {
    jsx: 'automatic',
  },
  build: {
    outDir: resolve(process.cwd(), 'build/generated/gui'),
    emptyOutDir: true,
  },
});
