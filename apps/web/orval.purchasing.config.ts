import {defineConfig} from 'orval'

export default defineConfig({
 purchasingPages:{
  input:{target:'./openapi/purchasing-pages.json'},
  output:{
   target:'./src/generated/purchasing-pages.ts',
   schemas:'./src/generated/purchasing-models',
   client:'react-query',
   httpClient:'fetch',
   mode:'single',
   override:{mutator:{path:'./src/lib/merchivra-mutator.ts',name:'merchivraFetch'}}
  }
 }
})
