import {sha256} from '@noble/hashes/sha2.js'

export async function hashBytes(bytes: ArrayBuffer): Promise<string> {
    // WebCrypto is available on secure origins. HTTP sites use the JS digest in a worker.
    const digest = globalThis.crypto?.subtle
        ? new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))
        : sha256(new Uint8Array(bytes))
    return Array.from(digest, value => value.toString(16).padStart(2, '0')).join('')
}
