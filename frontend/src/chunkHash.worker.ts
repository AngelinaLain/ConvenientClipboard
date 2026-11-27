import {hashBytes} from './chunkDigest'

type HashRequest = {index: number; bytes: ArrayBuffer}

self.onmessage = async (event: MessageEvent<HashRequest>) => {
    const {index, bytes} = event.data
    try {
        self.postMessage({index, hash: await hashBytes(bytes)})
    } catch {
        self.postMessage({index, error: '文件分片校验失败，请重试'})
    }
}
