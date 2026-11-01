import {defineConfig} from 'orval'
export default defineConfig({
 operations:{
  input:{target:'./openapi/operations-v67.json'},
  output:{
   target:'./src/generated/operations-v67.ts',
   schemas:'./src/generated/operations-models',
   client:'react-query',
   httpClient:'fetch',
   mode:'single',
   override:{mutator:{path:'./src/lib/merchivra-mutator.ts',name:'merchivraFetch'}}
  }
 }
})
