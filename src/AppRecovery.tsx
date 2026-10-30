import { Component, useEffect, useState, type ReactNode } from "react";

export class AppRecovery extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (!this.state.failed) return this.props.children;
    return <main className="screen" role="alert"><h1>MUGEN needs to restart</h1>
      <p>Something interrupted this screen. Your saved library has not been cleared.</p>
      <button className="btn primary" onClick={() => window.location.reload()}>Restart app</button></main>;
  }
}

export function AppUpdate() {
  const [waiting, setWaiting] = useState<ServiceWorker | null>(null);
  useEffect(() => {
    if (!("serviceWorker" in navigator) || !import.meta.env.PROD) return;
    let disposed = false;
    let registration: ServiceWorkerRegistration | undefined;
    const inspect = () => { if (!disposed && registration?.waiting) setWaiting(registration.waiting); };
    const onUpdate = () => {
      const worker = registration?.installing;
      worker?.addEventListener("statechange", inspect);
    };
    void navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`, { updateViaCache: "none" }).then((r) => {
      if (disposed) return;
      registration = r;
      inspect();
      r.addEventListener("updatefound", onUpdate);
      void r.update().catch(() => {});
    }).catch(() => {});
    const check = () => { if (document.visibilityState === "visible") void registration?.update().catch(() => {}); };
    document.addEventListener("visibilitychange", check);
    return () => { disposed = true; registration?.removeEventListener("updatefound", onUpdate); document.removeEventListener("visibilitychange", check); };
  }, []);
  if (!waiting) return null;
  return <aside className="appUpdate" role="status"><span>A new MUGEN version is ready. Restart when your work is saved.</span>
    <button className="btn primary small" onClick={() => {
      // Flush playback position through the app's existing visibility handler.
      window.dispatchEvent(new Event("pagehide"));
      navigator.serviceWorker.addEventListener("controllerchange", () => window.location.reload(), { once: true });
      waiting.postMessage({ type: "SKIP_WAITING" });
    }}>Update and restart</button></aside>;
}
