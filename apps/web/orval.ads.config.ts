import {defineConfig} from 'orval'
export default defineConfig({
 adsReports:{
  input:{target:'./openapi/ads-reports.json'},
  output:{
   target:'./src/generated/ads-reports.ts',
   schemas:'./src/generated/ads-models',
   client:'react-query',
   httpClient:'fetch',
   mode:'single',
   override:{mutator:{path:'./src/lib/merchivra-mutator.ts',name:'merchivraFetch'}}
  }
 }
})
