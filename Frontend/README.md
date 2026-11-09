# DocChat (plain React state, no Redux)

React + TypeScript + Vite + SCSS + axios.

    npm install
    npm run dev      # http://localhost:5173
    npm test
    npm run build

Backend must be running on http://localhost:8080. Change the address in `.env` (VITE_API_URL).

Structure:
- src/api/      axios instance, document calls, streaming chat, SSE parser (+ test)
- src/components/sidepanel/sidePanel.tsx   document list
- src/components/Main/UploadView.tsx       upload screen
- src/components/Main/ChatView.tsx         chat + sources
- src/App.tsx   owns the shared state (documents, open document, messages)
