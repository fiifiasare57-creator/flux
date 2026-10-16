import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { FluxProvider } from "./lib/store";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <FluxProvider>
      <App />
    </FluxProvider>
  </React.StrictMode>,
);
