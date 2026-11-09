# DocChat

Upload a document, ask questions about it, and see which passages each answer came from.

DocChat is a React app where you chat with your own files (PDF, TXT or Markdown). Answers appear word by word as they are written, and every answer lists the passages it used so you can check it yourself.

## What you can do

- Upload a PDF, TXT or Markdown file (up to 10 MB), with a progress bar while it is processed
- Keep several documents in the sidebar and switch between them, each with its own chat history
- Ask questions and watch the answer stream in, with a Stop button to cancel
- See the passages behind each answer, with a match score, next to the chat
- Delete a document you no longer need
- Clear messages for every problem: wrong file type, file too large, server not reachable

## Built with

- React 19 and TypeScript
- Vite
- SCSS
- Axios for requests

## Running it locally

You need Node.js 20 or newer.

```bash
git clone <your-repo-url>
cd docchat
npm install
npm run dev
```

Open http://localhost:5173.

The app talks to a REST API. By default it looks for one at `http://localhost:8080/api/docchat`. To use a different address, edit `.env`:

```
VITE_API_URL=http://localhost:8080/api/docchat
```

### Other commands

```bash
npm test        # run the tests
npm run build   # production build in dist/
```

## Project structure

```
src/
  api/
    http.ts          shared axios instance and error handling
    documents.ts     list, upload and delete documents
    chat.ts          streaming question and answer
    sse.ts           reads the streamed response piece by piece
    sse.test.ts
  components/
    sidepanel/       document list
    Main/
      UploadView.tsx upload screen
      ChatView.tsx   chat and sources
  App.tsx            holds the shared state
  App.scss           styles
  types.ts
```

## How a few things work

**State.** `App.tsx` owns the shared state (the document list, the open document, and all messages) and passes it down as props. The app is small enough that this stays easy to follow, and nothing needs a state library.

**Streaming answers.** Axios waits for a whole response, so the streamed answer uses `fetch` and reads the body as it arrives. A chunk of data can end in the middle of an event, so `sse.ts` keeps the unfinished part and adds the next chunk to it before reading again. The Stop button uses an `AbortController` to cancel the request.

**Errors.** One axios interceptor turns any failed request into a plain `Error` with a readable message, so components only show `error.message`.

## What I would add next

- Keep chat history after a page refresh
- A loading skeleton for the document list
- Component tests for the upload and chat screens

## Author

Praveena Penumadi, React developer based in Hyderabad.
[LinkedIn](https://linkedin.com/in/praveenapenumadi)
