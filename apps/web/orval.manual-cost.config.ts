import {defineConfig} from 'orval'

/** V228: generated on pnpm test/build/typecheck from bounded Owner-only API. */
export default defineConfig({
 manualCostSources:{
  input:{target:'./openapi/manual-cost-v228.json'},
  output:{
   target:'./src/generated/manual-cost-v228.ts',
   schemas:'./src/generated/manual-cost-models',
   client:'react-query',
   httpClient:'fetch',
   mode:'single',
   override:{mutator:{path:'./src/lib/merchivra-mutator.ts',name:'merchivraFetch'}}
  }
 }
})
