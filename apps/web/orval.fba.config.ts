import {defineConfig} from 'orval'

// V65: run ONLY after the actual Spring Boot /v3/api-docs/fba-inbound
// has been normalized into openapi/fba-inbound.json. No design-only schemas.
export default defineConfig({
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
