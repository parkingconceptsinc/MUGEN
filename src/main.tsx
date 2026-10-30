import React from "react";
import { createRoot } from "react-dom/client";
import { Player } from "./Player";
import "./styles.css";
import { AppRecovery, AppUpdate } from "./AppRecovery";

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <AppRecovery><Player /><AppUpdate /></AppRecovery>
  </React.StrictMode>
);
