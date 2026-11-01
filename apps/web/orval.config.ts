import {defineConfig} from 'orval'

export default defineConfig({
  financeProfits:{
    input:{target:'./openapi/finance-profits.json'},
    output:{
      target:'./src/generated/finance-profits.ts',
      schemas:'./src/generated/finance-models',
      client:'react-query',
      httpClient:'fetch',
      mode:'single',
      override:{
        mutator:{path:'./src/lib/merchivra-mutator.ts',name:'merchivraFetch'},
        // Defaults: GET => useQuery; POST => useMutation (no unsafe GET mutations)
      }
    }
  },
  fbaInbound:{
    input:{target:'./openapi/fba-inbound.json'},
    output:{
      target:'./src/generated/fba-inbound.ts',
      schemas:'./src/generated/fba-models',
      client:'react-query',
      httpClient:'fetch',
      mode:'single',
      override:{
        mutator:{path:'./src/lib/merchivra-mutator.ts',name:'merchivraFetch'}
      }
    }
  }
})
