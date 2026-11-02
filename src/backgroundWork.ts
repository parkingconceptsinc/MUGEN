/** Optional enrichment shares one lane and yields to playback/imports. */
export class BackgroundWork {
  private queue: Array<() => Promise<void>> = [];
  private blockers = new Set<string>();
  private running = false;
  setPaused(reason: string, paused: boolean) {
    if (paused) this.blockers.add(reason); else this.blockers.delete(reason);
    this.pump();
  }
  enqueue(job: () => Promise<void>) { this.queue.push(job); this.pump(); }
  private pump() {
    if (this.running || this.blockers.size || !this.queue.length) return;
    this.running = true;
    void (async () => {
      // Let the current input/render commit before beginning optional work.
      await new Promise(resolve => setTimeout(resolve, 50));
      while (this.queue.length && !this.blockers.size) {
        try { await this.queue.shift()!(); } catch { /* Retry stale metadata on next launch. */ }
        await new Promise(resolve => setTimeout(resolve, 50));
      }
    })().finally(() => { this.running = false; this.pump(); });
  }
}
