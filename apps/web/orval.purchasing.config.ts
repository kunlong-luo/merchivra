import {defineConfig} from 'orval'

/** V114 generated at build/test/typecheck time; never hand-edit generated files. */
export default defineConfig({
 purchasing:{
  input:{target:'./openapi/purchasing-v114.json'},
  output:{
   target:'./src/generated/purchasing-v114.ts',
   schemas:'./src/generated/purchasing-models',
   client:'react-query',
   httpClient:'fetch',
   mode:'single',
   override:{
    mutator:{path:'./src/lib/merchivra-mutator.ts',name:'merchivraFetch'}
   }
  }
 }
})
