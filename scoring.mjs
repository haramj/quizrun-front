export function scoreAnswer(elapsedMs, durationMs) {
 return 1000 + Math.round(500 * Math.max(0, Math.min(1, 1 - elapsedMs / durationMs)));
}
