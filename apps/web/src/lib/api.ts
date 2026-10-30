import { z } from 'zod'

// External responses are untrusted, even when served from our own API.
// Keep this schema in sync with the implemented (not merely proposed) ping DTO.
export const pingSchema = z.object({
  service: z.literal('merchivra'),
  status: z.enum(['ok']),
  stage: z.string().min(1)
})

export type PingResponse = z.infer<typeof pingSchema>

export function parsePingResponse(input: unknown): PingResponse {
  return pingSchema.parse(input)
}

export async function fetchPing(): Promise<PingResponse> {
  const response = await fetch('/api/v1/ping', {
    headers: { Accept: 'application/json' },
    credentials: 'same-origin'
  })
  if (!response.ok) {
    throw new Error(`ERP API returned HTTP ${response.status}`)
  }
  // Never log the raw response; future business endpoints may carry sensitive data.
  return parsePingResponse(await response.json())
}
