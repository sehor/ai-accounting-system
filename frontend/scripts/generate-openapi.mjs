import { writeFile } from 'node:fs/promises'
import openapiTS, { astToString } from 'openapi-typescript'

const source = process.env.OPENAPI_URL ?? 'http://127.0.0.1:18080/v1/openapi.json'
const ast = await openapiTS(new URL(source))
await writeFile(new URL('../src/api/generated.ts', import.meta.url), astToString(ast), 'utf8')
