import { createHash } from "node:crypto";

const DIMENSIONS = 768;

export function createDeterministicEmbedder() {
  async function embed(text) {
    const content = text.includes("| query:") ? text.split("| query:", 2)[1] : text;
    const values = Array(DIMENSIONS).fill(0);
    const tokens = content.toLocaleLowerCase("en").match(/[\p{L}\p{N}]+/gu) ?? [];
    for (const token of tokens) {
      const digest = createHash("sha256").update(token, "utf8").digest();
      values[digest.readUInt16BE(0) % DIMENSIONS] += 1;
    }
    const magnitude = Math.sqrt(values.reduce((sum, value) => sum + value * value, 0));
    if (magnitude === 0) values[0] = 1;
    else values.forEach((value, index) => {
      values[index] = value / magnitude;
    });
    return values;
  }

  return { embedItem: embed, embedQuery: embed };
}
