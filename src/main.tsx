import React from "react";
import { createRoot } from "react-dom/client";
import { Player } from "./Player";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <Player />
  </React.StrictMode>
);

if ("serviceWorker" in navigator && import.meta.env.PROD) {
  // Activate updates promptly and reload the shell once the new worker takes
  // control. Without this, an old JS bundle can remain alive after deployment
  // even though the service worker has updated successfully.
  let refreshing = false;
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (refreshing) return;
    refreshing = true;
    window.location.reload();
  });

  window.addEventListener("load", () => {
    void navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`, {
      updateViaCache: "none"
    }).then((registration) => registration.update()).catch(() => {});
  });
}
