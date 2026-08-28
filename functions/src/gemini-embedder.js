const EMBEDDING_MODEL = "gemini-embedding-2";
const EMBEDDING_DIMENSIONS = 768;
const TRANSIENT_NETWORK_CODES = new Set([
  "EAI_AGAIN",
  "ECONNREFUSED",
  "ECONNRESET",
  "EHOSTUNREACH",
  "ENETDOWN",
  "ENETUNREACH",
  "ETIMEDOUT",
]);

export function createGeminiEmbedder(ai) {
  async function embed(text) {
    let response;
    try {
      response = await ai.models.embedContent({
        model: EMBEDDING_MODEL,
        contents: text,
        config: { outputDimensionality: EMBEDDING_DIMENSIONS },
      });
    } catch (error) {
      if (isTransient(error)) throw new TransientEmbeddingError(error);
      throw error;
    }
    const values = response.embeddings?.[0]?.values;
    if (
      !Array.isArray(values) ||
      values.length !== EMBEDDING_DIMENSIONS ||
      values.some((value) => typeof value !== "number" || !Number.isFinite(value))
    ) {
      throw new InvalidEmbeddingResponseError();
    }
    return values;
  }

  return {
    embedItem: embed,
    embedQuery: embed,
  };
}

function isTransient(error) {
  const status = Number(error?.status ?? error?.code);
  if (status === 408 || status === 429 || status >= 500) return true;
  let cause = error;
  while (cause !== undefined && cause !== null) {
    if (TRANSIENT_NETWORK_CODES.has(cause.code)) return true;
    cause = cause.cause;
  }
  return false;
}

export class TransientEmbeddingError extends Error {
  constructor(cause) {
    super("Gemini embedding failed transiently.", { cause });
    this.name = "TransientEmbeddingError";
  }
}

export class InvalidEmbeddingResponseError extends Error {
  constructor() {
    super("Gemini returned an invalid embedding.");
    this.name = "InvalidEmbeddingResponseError";
  }
}
