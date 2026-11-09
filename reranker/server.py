"""Cross-encoder rerank sidecar (docs/specs/04-rerank-rewrite.md §3).

POST /v1/rerank {"query": str, "documents": [str], "top_n": int}
  -> {"model": str, "results": [{"index": int, "relevance_score": float}]}  (raw logits, sorted descending)
GET /health -> 200 once the model is loaded.

The model is the official Hugging Face checkpoint pinned by RERANKER_MODEL and RERANKER_REVISION. Standard library
HTTP server, one request at a time: the container has 2 CPUs and requests are small batches.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

import torch
from sentence_transformers import CrossEncoder

MODEL = os.environ["RERANKER_MODEL"]
REVISION = os.environ["RERANKER_REVISION"]
torch.set_num_threads(int(os.environ.get("RERANKER_THREADS", "2")))
ENCODER = CrossEncoder(MODEL, revision=REVISION, device="cpu", max_length=512,
                       activation_fn=torch.nn.Identity())


class Handler(BaseHTTPRequestHandler):

    def _send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok", "model": MODEL, "revision": REVISION})
        else:
            self._send(404, {"error": "not found"})

    def _body(self):
        if self.headers.get("transfer-encoding", "").lower() == "chunked":
            parts = []
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip(), 16)
                if size == 0:
                    self.rfile.readline()
                    return b"".join(parts)
                parts.append(self.rfile.read(size))
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("content-length", "0")))

    def do_POST(self):
        if self.path != "/v1/rerank":
            self._send(404, {"error": "not found"})
            return
        try:
            req = json.loads(self._body())
            query, docs = req["query"], req["documents"]
            if not isinstance(query, str) or not isinstance(docs, list) or not all(isinstance(d, str) for d in docs):
                raise ValueError("query must be a string and documents a list of strings")
        except (ValueError, KeyError, TypeError) as e:
            self._send(400, {"error": str(e)})
            return
        top_n = int(req.get("top_n", len(docs)))
        scores = [float(s) for s in ENCODER.predict([(query, d) for d in docs])] if docs else []
        ranked = sorted(range(len(docs)), key=lambda i: -scores[i])[:top_n]
        self._send(200, {"model": MODEL, "results": [{"index": i, "relevance_score": scores[i]} for i in ranked]})

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
